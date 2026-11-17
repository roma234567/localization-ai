package chat.vinlagon.secret.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// Keep in sync with the user-visible confirmation sheet.
enum class SecurityLevel { TRUST, MEDIUM, STRICT, CUSTOM }
enum class ToolType { WEB_READ, FILE_READ, FILE_WRITE, COMMAND, SCRIPT, BUILD, APP_LAUNCH, SCHEDULE, UI_THEME }
enum class PermissionPolicy { ALLOW, DENY, REQUIRE_APPROVAL }

data class ApprovalRequest(val id: Long, val tool: String, val summary: String)

class SecurityGate(
    initialLevel: SecurityLevel = SecurityLevel.STRICT,
    private val customPolicies: Map<ToolType, PermissionPolicy> = emptyMap()
) {
    @Volatile var level: SecurityLevel = initialLevel
    private val mutex = Mutex()
    private val pending = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    private var nextId = 1L
    private val listeners = mutableListOf<(ApprovalRequest) -> Unit>()

    suspend fun observeApprovals(listener: (ApprovalRequest) -> Unit) = mutex.withLock { listeners += listener }

    /** Returns false for denied requests; STRICT suspends until explicitly approved or denied. */
    suspend fun authorize(type: ToolType, tool: String, args: JsonObject): Boolean {
        val summary = args.entries.joinToString(", ") { (key, value) ->
            val shown = when {
                key.contains("token", true) || key.contains("key", true) || key.contains("secret", true) -> "[скрыто]"
                key.contains("content", true) -> "[${value.toString().length} символов]"
                else -> value.toString().take(120)
            }
            "$key=$shown"
        }
        val decision = when (level) {
            SecurityLevel.TRUST -> PermissionPolicy.ALLOW
            SecurityLevel.MEDIUM -> if (type in setOf(ToolType.WEB_READ, ToolType.FILE_READ, ToolType.APP_LAUNCH) && !isDangerous(args)) PermissionPolicy.ALLOW else PermissionPolicy.REQUIRE_APPROVAL
            SecurityLevel.STRICT -> PermissionPolicy.REQUIRE_APPROVAL
            SecurityLevel.CUSTOM -> customPolicies[type] ?: PermissionPolicy.REQUIRE_APPROVAL
        }
        if (isDangerous(args)) return false
        return when (decision) {
            PermissionPolicy.ALLOW -> true
            PermissionPolicy.DENY -> false
            PermissionPolicy.REQUIRE_APPROVAL -> awaitUser(tool, summary)
        }
    }

    suspend fun resolveApproval(id: Long, approved: Boolean) {
        mutex.withLock { pending.remove(id) }?.complete(approved)
    }

    private suspend fun awaitUser(tool: String, summary: String): Boolean {
        val (id, deferred, callbacks) = mutex.withLock {
            val id = nextId++
            val deferred = CompletableDeferred<Boolean>()
            pending[id] = deferred
            Triple(id, deferred, listeners.toList())
        }
        val request = ApprovalRequest(id, tool, summary)
        callbacks.forEach { it(request) }
        return try { deferred.await() } finally { mutex.withLock { pending.remove(id) } }
    }

    private fun isDangerous(args: JsonObject): Boolean {
        val text = args.values.joinToString(" ") { value -> value.jsonPrimitiveOrText() }.lowercase()
        val blocked = listOf("rm -rf /", "mkfs", "dd if=", "fastboot", "flash", "/data/system/", "reboot bootloader")
        return blocked.any(text::contains)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrText(): String =
        runCatching { jsonPrimitive.contentOrNull ?: toString() }.getOrDefault(toString())
}
