package chat.vinlagon.secret.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ApiChatClient(
    private val http: HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        engine { config { followRedirects = false } }
    }
) {
    suspend fun stream(
        config: ApiModelConfig,
        history: List<ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        onText: suspend (String) -> Unit
    ): ApiReply {
        require(config.apiKey.isNotBlank()) { "Сначала добавьте API-ключ в настройках модели" }
        require(config.endpoint.startsWith("https://")) { "Для API требуется HTTPS endpoint" }
        val toolCalls = linkedMapOf<Int, PartialToolCall>()
        val system = history.firstOrNull { it.role == "system" }
        val recent = history.filterNot { it.role == "system" }.takeLast(32).map { it.copy(content = it.content.take(16_000)) }
        val body = requestBody(config, listOfNotNull(system) + recent, tools)
        http.preparePost(config.endpoint) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/event-stream")
            if (config.provider == ApiProvider.ANTHROPIC) {
                header("x-api-key", config.apiKey)
                header("anthropic-version", "2023-06-01")
            } else header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            setBody(body.toString())
        }.execute { response ->
            if (response.status.value !in 200..299) {
                val safeBody = response.bodyAsText().take(1200)
                error("API вернул ${response.status.value}: $safeBody")
            }
            val channel = response.bodyAsChannel()
            var data = StringBuilder()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isEmpty()) {
                    consumeSseData(data.toString(), config.provider, onText, toolCalls)
                    data = StringBuilder()
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
            if (data.isNotEmpty()) consumeSseData(data.toString(), config.provider, onText, toolCalls)
        }
        val completed = toolCalls.values.mapNotNull { partial ->
            val name = partial.name
            if (name.isBlank() || partial.id.isBlank()) null else runCatching {
                ModelToolCall(partial.id, name, Json.parseToJsonElement(partial.arguments.toString()).jsonObject)
            }.getOrNull()
        }
        return ApiReply(completed)
    }

    private fun requestBody(config: ApiModelConfig, history: List<ChatMessage>, tools: List<AgentTool>): JsonObject = when (config.provider) {
        ApiProvider.OPENAI_COMPATIBLE -> buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("messages", buildJsonArray {
                history.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role)
                        if (message.role == "tool") {
                            put("tool_call_id", message.toolCallId.orEmpty())
                            put("content", message.content)
                        } else {
                            if (message.content.isNotEmpty()) put("content", message.content)
                            if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                                message.toolCalls.forEach { call -> add(buildJsonObject {
                                    put("id", call.id); put("type", "function")
                                    put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments.toString()) })
                                }) }
                            })
                        }
                    })
                }
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.name); put("description", tool.description); put("parameters", tool.parametersSchema)
                    })
                }) }
            })
        }
        ApiProvider.ANTHROPIC -> buildJsonObject {
            put("model", config.model); put("max_tokens", 4096); put("stream", true)
            put("messages", buildJsonArray {
                history.filter { it.role == "user" || it.role == "assistant" || it.role == "tool" }.forEach { message ->
                    when (message.role) {
                        "tool" -> add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray { add(buildJsonObject {
                                put("type", "tool_result"); put("tool_use_id", message.toolCallId.orEmpty()); put("content", message.content)
                            }) })
                        })
                        "assistant" -> add(buildJsonObject {
                            put("role", "assistant")
                            if (message.toolCalls.isEmpty()) put("content", message.content) else put("content", buildJsonArray {
                                if (message.content.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", message.content) })
                                message.toolCalls.forEach { call -> add(buildJsonObject {
                                    put("type", "tool_use"); put("id", call.id); put("name", call.name); put("input", call.arguments)
                                }) }
                            })
                        })
                        else -> add(buildJsonObject { put("role", "user"); put("content", message.content) })
                    }
                }
            })
            history.firstOrNull { it.role == "system" }?.let { put("system", it.content) }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEach { tool -> add(buildJsonObject {
                    put("name", tool.name); put("description", tool.description); put("input_schema", tool.parametersSchema)
                }) }
            })
        }
    }

    private suspend fun consumeSseData(
        data: String,
        provider: ApiProvider,
        onText: suspend (String) -> Unit,
        toolCalls: MutableMap<Int, PartialToolCall>
    ) {
        if (data.isBlank() || data == "[DONE]") return
        val event = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        if (event["error"] != null) error("Провайдер сообщил об ошибке")
        when (provider) {
            ApiProvider.OPENAI_COMPATIBLE -> {
                val delta = runCatching { event["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject }.getOrNull() ?: return
                val text = runCatching { delta["content"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                if (!text.isNullOrEmpty()) onText(text)
                runCatching { delta["tool_calls"]?.jsonArray }.getOrNull()?.forEach { item ->
                    val call = item.jsonObject
                    val index = call["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
                    val partial = toolCalls.getOrPut(index) { PartialToolCall() }
                    call["id"]?.jsonPrimitive?.contentOrNull?.let { partial.id = it }
                    call["function"]?.jsonObject?.let { function ->
                        function["name"]?.jsonPrimitive?.contentOrNull?.let { partial.name += it }
                        function["arguments"]?.jsonPrimitive?.contentOrNull?.let { fragment ->
                            if (partial.arguments.length + fragment.length <= 64_000) partial.arguments.append(fragment)
                        }
                    }
                }
            }
            ApiProvider.ANTHROPIC -> {
                val type = event["type"]?.jsonPrimitive?.contentOrNull
                val index = event["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (type == "content_block_start" && index != null) {
                    val block = event["content_block"]?.jsonObject
                    if (block?.get("type")?.jsonPrimitive?.contentOrNull == "tool_use") {
                        val partial = toolCalls.getOrPut(index) { PartialToolCall() }
                        partial.id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        partial.name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                } else if (type == "content_block_delta") {
                    val delta = event["delta"]?.jsonObject
                    val text = runCatching { delta?.get("text")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    if (!text.isNullOrEmpty()) onText(text)
                    val partialJson = runCatching { delta?.get("partial_json")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    if (index != null && partialJson != null) {
                        val partial = toolCalls.getOrPut(index) { PartialToolCall() }
                        if (partial.arguments.length + partialJson.length <= 64_000) partial.arguments.append(partialJson)
                    }
                }
            }
        }
    }

    fun close() = http.close()
}

private class PartialToolCall(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())
data class ApiReply(val toolCalls: List<ModelToolCall>)
data class ChatMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ModelToolCall> = emptyList()
)
