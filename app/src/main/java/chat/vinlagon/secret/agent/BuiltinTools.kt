package chat.vinlagon.secret.agent

import chat.vinlagon.secret.security.ToolType
import chat.vinlagon.secret.workspace.WorkspaceMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URI

private fun JsonObject.string(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

class SearchInWebTool(private val client: HttpClient = HttpClient(OkHttp)) : AgentTool {
    override val name = "search_in_web"
    override val description = "Поиск в DuckDuckGo Instant Answer API. Возвращает найденные заголовки, описания и ссылки."
    override val type = ToolType.WEB_READ
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
    }

    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val query = args.string("query")?.take(300) ?: return ToolResult(false, "Укажите поисковый запрос")
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
        val response = client.get(url).bodyAsText()
        val json = Json.parseToJsonElement(response).jsonObject
        val rows = mutableListOf<String>()
        json["AbstractText"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { rows += "${json["Heading"]?.jsonPrimitive?.contentOrNull.orEmpty()}\n$it\n${json["AbstractURL"]?.jsonPrimitive?.contentOrNull.orEmpty()}" }
        fun collect(value: kotlinx.serialization.json.JsonElement) {
            when (value) {
                is kotlinx.serialization.json.JsonObject -> {
                    val text = value["Text"]?.jsonPrimitive?.contentOrNull
                    val first = value["FirstURL"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrBlank() && !first.isNullOrBlank()) rows += "$text\n$first"
                    value.values.forEach(::collect)
                }
                is kotlinx.serialization.json.JsonArray -> value.forEach(::collect)
                else -> Unit
            }
        }
        json["RelatedTopics"]?.let(::collect)
        return ToolResult(true, rows.distinct().take(8).joinToString("\n\n").ifBlank { "Поиск не вернул кратких результатов. Попробуйте read_page с известной HTTPS-ссылкой." })
    }
}

class ReadPageTool(private val client: HttpClient = HttpClient(OkHttp) { followRedirects = false }) : AgentTool {
    override val name = "read_page"
    override val description = "Загружает общедоступную HTTPS-страницу и извлекает основной текст в Markdown-подобном виде."
    override val type = ToolType.WEB_READ
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("url", buildJsonObject { put("type", "string") }) })
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("url")) })
    }

    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val raw = args.string("url") ?: return ToolResult(false, "Укажите URL")
        val uri = runCatching { URI(raw) }.getOrNull() ?: return ToolResult(false, "Некорректный URL")
        val host = uri.host?.lowercase() ?: return ToolResult(false, "В URL отсутствует host")
        if (uri.scheme != "https" || uri.userInfo != null || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return ToolResult(false, "Разрешены только публичные HTTPS-ссылки")
        }
        val privateAddress = withContext(Dispatchers.IO) { runCatching {
            InetAddress.getAllByName(host).any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }
        }.getOrDefault(true) }
        if (privateAddress) return ToolResult(false, "URL разрешается в локальный/непубличный адрес")
        val response = client.get(uri.toASCIIString()) { headers.append("User-Agent", "vinlagonChat/0.1 (reader)") }
        val declaredLength = response.headers["Content-Length"]?.toLongOrNull()
        if (declaredLength != null && declaredLength > 2_000_000) return ToolResult(false, "Страница больше лимита 2 МБ")
        if (response.status.value !in 200..299) return ToolResult(false, "HTTP ${response.status.value}")
        val packet = response.bodyAsChannel().readRemaining(2_000_001L)
        val bytes = packet.readBytes()
        packet.close()
        if (bytes.size > 2_000_000) return ToolResult(false, "Страница больше лимита 2 МБ")
        val html = bytes.toString(Charsets.UTF_8)
        val doc = Jsoup.parse(html, uri.toString())
        doc.select("script, style, nav, footer, header, noscript, iframe, form, svg").remove()
        val root = doc.selectFirst("article, main") ?: doc.body()
        val title = doc.title().take(180)
        val content = root?.text().orEmpty().take(12_000)
        return ToolResult(response.status.value in 200..299, "# $title\n\n$content")
    }
}

/** A deliberately small virtual command interpreter: never invokes /bin/sh or ProcessBuilder. */
class SandboxCommandTool : AgentTool {
    override val name = "bash_used"
    override val description = "Выполняет только pwd, ls и cat внутри активной sandbox-папки. Это не системный shell."
    override val type = ToolType.COMMAND
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("command", buildJsonObject { put("type", "string") }) })
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("command")) })
    }

    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val command = args.string("command")?.trim().orEmpty()
        val parts = command.split(Regex("\\s+"), limit = 2)
        if (parts.isEmpty() || parts[0] !in setOf("pwd", "ls", "cat")) return ToolResult(false, "Команда недоступна. Разрешены только: pwd, ls, cat.")
        val sandbox = context.workspace.mode as? WorkspaceMode.Sandbox ?: return ToolResult(false, "Команды доступны только в sandbox workspace")
        return when (parts[0]) {
            "pwd" -> ToolResult(true, sandbox.isolatedPath.canonicalPath)
            "ls" -> {
                val path = context.workspace.resolveSandboxPath(parts.getOrNull(1) ?: ".")
                if (!path.isDirectory) ToolResult(false, "Это не каталог") else ToolResult(true, path.list()?.sorted()?.take(300)?.joinToString("\n").orEmpty())
            }
            else -> {
                val relative = parts.getOrNull(1) ?: return ToolResult(false, "Укажите путь")
                val path = context.workspace.resolveSandboxPath(relative)
                if (!path.isFile) ToolResult(false, "Файл не найден") else ToolResult(true, context.workspace.read(relative).toString(Charsets.UTF_8).take(16_000))
            }
        }
    }
}
