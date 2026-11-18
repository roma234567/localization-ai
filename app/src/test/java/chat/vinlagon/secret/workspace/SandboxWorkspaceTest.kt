package chat.vinlagon.secret.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxWorkspaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun writeCreatesBackupAndRoundTrips() = runBlocking {
        val workspace = SandboxWorkspace(temporary.newFolder("sandbox"))
        workspace.write("notes/a.txt", "first".toByteArray())
        val change = workspace.write("notes/a.txt", "second".toByteArray())
        assertEquals("second", workspace.read("notes/a.txt").decodeToString())
        assertNotNull(change.backupPath)
        assertEquals("first", java.io.File(change.backupPath!!).readText())
    }

    @Test(expected = SecurityException::class)
    fun traversalCannotLeaveSandbox() {
        SandboxWorkspace(temporary.newFolder("sandbox")).resolveSandboxPath("../outside")
    }
}
