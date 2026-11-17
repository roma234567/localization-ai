package chat.vinlagon.secret.workspace

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface WorkspaceMode {
    data class Sandbox(val isolatedPath: File) : WorkspaceMode
    data class AgentTask(val targetDirUri: Uri, val targetPath: String, val privilege: PrivilegeLevel) : WorkspaceMode
    /** Keep this value in memory only; never serialize the access token into preferences or logs. */
    data class GitHub(val token: String, val repo: String, val branch: String) : WorkspaceMode
}

enum class PrivilegeLevel { STANDARD, SHIZUKU, ROOT_SU }

interface WorkspaceManager {
    val mode: WorkspaceMode
    suspend fun read(path: String): ByteArray
    suspend fun write(path: String, content: ByteArray): FileChange
    fun resolveSandboxPath(path: String): File
}

data class FileChange(val path: String, val bytesWritten: Long, val backupPath: String?, val preview: String)

/** Local-only sandbox implementation. Canonical path checks also reject symlink escapes. */
class SandboxWorkspace(private val root: File) : WorkspaceManager {
    override val mode: WorkspaceMode = WorkspaceMode.Sandbox(root.canonicalFile)
    private val canonicalRoot = root.canonicalFile

    override fun resolveSandboxPath(path: String): File {
        val candidate = File(canonicalRoot, path).canonicalFile
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        if (candidate != canonicalRoot && !candidate.path.startsWith(rootPrefix)) {
            throw SecurityException("Path escapes the sandbox")
        }
        return candidate
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = resolveSandboxPath(path)
        if (file.length() > MAX_FILE_BYTES) throw IllegalArgumentException("File exceeds 2 MiB read limit")
        file.readBytes()
    }

    override suspend fun write(path: String, content: ByteArray): FileChange = withContext(Dispatchers.IO) {
        require(content.size <= MAX_FILE_BYTES) { "File exceeds 2 MiB write limit" }
        val target = resolveSandboxPath(path)
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() > MAX_FILE_BYTES) throw IllegalArgumentException("Cannot back up file over 2 MiB")
        val backup = if (target.exists()) File(target.parentFile, "${target.name}.bak").also { target.copyTo(it, overwrite = true) } else null
        target.writeBytes(content)
        val text = content.toString(Charsets.UTF_8)
        FileChange(path, content.size.toLong(), backup?.absolutePath, text.lineSequence().take(8).joinToString("\n").take(4_000))
    }

    companion object { const val MAX_FILE_BYTES = 2 * 1024 * 1024 }
}

/** Agent-task and GitHub implementations are separate adapters; SAF grants are not raw paths. */
interface AgentTaskWorkspace : WorkspaceManager {
    suspend fun requestAccess(): Boolean
}

interface GitTreeWorkspace : WorkspaceManager {
    suspend fun listTree(path: String): List<String>
}
