package chat.vinlagon.secret.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SecurityGateTest {
    @Test fun strictModeSuspendsUntilApproval() = runBlocking {
        val gate = SecurityGate(SecurityLevel.STRICT)
        val request = CompletableDeferred<ApprovalRequest>()
        gate.observeApprovals { request.complete(it) }
        val check = async { gate.authorize(ToolType.FILE_WRITE, "write_file", buildJsonObject { put("path", "note.txt") }) }
        val approval = request.await()
        gate.resolveApproval(approval.id, true)
        assertTrue(check.await())
    }

    @Test fun dangerousPatternsAreDeniedEvenWhenTrusted() = runBlocking {
        val gate = SecurityGate(SecurityLevel.TRUST)
        assertFalse(gate.authorize(ToolType.COMMAND, "bash_used", buildJsonObject { put("command", "rm -rf /") }))
    }
}
