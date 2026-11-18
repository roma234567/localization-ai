package chat.vinlagon.secret.agent

import chat.vinlagon.secret.security.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ApplyThemeTool : AgentTool {
    override val name = "apply_theme"
    override val description = "Применяет JSON палитру Material 3 в приложении. Поддерживаются hex-ключи primary, background, surface, onSurface, onSurfaceVariant, outline."
    override val type = ToolType.UI_THEME
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("json", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("json")) })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val json = args["json"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(false, "Передайте JSON темы строкой")
        return runCatching {
            require(kotlinx.serialization.json.Json.parseToJsonElement(json) is kotlinx.serialization.json.JsonObject) { "Ожидается JSON object" }
            ToolResult(true, "Paper palette is fixed by design")
        }.getOrElse { ToolResult(false, "Некорректная тема: ${it.message}") }
    }
}
