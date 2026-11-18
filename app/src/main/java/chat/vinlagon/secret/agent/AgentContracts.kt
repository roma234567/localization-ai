package chat.vinlagon.secret.agent

import chat.vinlagon.secret.security.SecurityGate
import chat.vinlagon.secret.security.ToolType
import chat.vinlagon.secret.workspace.WorkspaceManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AgentTool {
    val name: String
    val description: String
    val type: ToolType
    val parametersSchema: JsonObject
    suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult
}

data class ToolExecutionContext(
    val workspace: WorkspaceManager,
    val securityGate: SecurityGate,
    val applicationContext: android.content.Context? = null
)

data class ToolResult(
    val ok: Boolean,
    val content: String,
    val metadata: JsonObject = buildJsonObject { }
)

data class ModelToolCall(val id: String, val name: String, val arguments: JsonObject)

data class ToolReply(val callId: String, val result: ToolResult)

/** Provider adapters (OpenAI-compatible, Anthropic, OpenRouter) emit normalized tool calls here. */
interface StreamingModelRuntime {
    suspend fun stream(userMessage: String, onText: suspend (String) -> Unit): List<ModelToolCall>
}

class ToolOrchestrator(
    tools: List<AgentTool>,
    private val context: ToolExecutionContext
) {
    private val registry = tools.associateBy { it.name }

    suspend fun dispatch(calls: List<ModelToolCall>): List<ToolReply> = calls.map { call ->
        val tool = registry[call.name]
        val result = when {
            tool == null -> ToolResult(false, "Unknown tool: ${call.name}")
            !context.securityGate.authorize(tool.type, tool.name, call.arguments) -> ToolResult(false, "Action blocked or not approved")
            else -> try {
                tool.execute(context, call.arguments)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ToolResult(false, failure.message ?: "Tool failed")
            }
        }
        ToolReply(call.id, result)
    }
}

class ReadFileTool : AgentTool {
    override val name = "read_file"
    override val description = "Read a UTF-8 file from the active isolated workspace"
    override val type = ToolType.FILE_READ
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
        put("required", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val path = args["path"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: return ToolResult(false, "Missing path")
        val bytes = context.workspace.read(path)
        val text = bytes.toString(Charsets.UTF_8)
        return ToolResult(true, text.take(16_000), buildJsonObject { put("truncated", text.length > 16_000); put("bytesRead", bytes.size) })
    }
}

class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "Write a UTF-8 file with a backup in the active workspace"
    override val type = ToolType.FILE_WRITE
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("path", buildJsonObject { put("type", "string") })
            put("content", buildJsonObject { put("type", "string") })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("path")); add(kotlinx.serialization.json.JsonPrimitive("content"))
        })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        fun string(key: String) = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val path = string("path") ?: return ToolResult(false, "Missing path")
        val content = string("content") ?: return ToolResult(false, "Missing content")
        val changed = context.workspace.write(path, content.toByteArray(Charsets.UTF_8))
        return ToolResult(true, changed.preview, buildJsonObject {
            put("path", changed.path); put("bytesWritten", changed.bytesWritten)
            changed.backupPath?.let { put("backupPath", it) }
        })
    }
}
