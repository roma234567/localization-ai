package chat.vinlagon.secret.agent

import chat.vinlagon.secret.security.ToolType
import com.chaquo.python.Python
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Safe starter evaluator: arithmetic expressions only, no imports, attributes, files, or statements. */
class PythonExpressionTool : AgentTool {
    override val name = "interpolation_script"
    override val description = "Выполняет арифметическое Python-выражение (не произвольные statements/imports) в Chaquopy."
    override val type = ToolType.SCRIPT
    override val parametersSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("expression", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("expression")) })
    }
    override suspend fun execute(context: ToolExecutionContext, args: JsonObject): ToolResult {
        val expression = args["expression"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (expression.isEmpty() || expression.length > 256 || expression.contains("**") || !expression.matches(Regex("[0-9\\s+\\-*/%().]+"))) {
            return ToolResult(false, "Разрешены только короткие арифметические Python-выражения с цифрами и + - * / % скобками")
        }
        return runCatching {
            val result = Python.getInstance().getModule("builtins").callAttr("eval", expression)
            ToolResult(true, result.toString().take(1000))
        }.getOrElse { ToolResult(false, "Ошибка выражения: ${it.message}") }
    }
}
