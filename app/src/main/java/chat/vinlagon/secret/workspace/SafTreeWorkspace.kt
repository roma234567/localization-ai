package chat.vinlagon.secret.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SAF-backed workspace. Construct only after ACTION_OPEN_DOCUMENT_TREE and persistable grant. */
class SafTreeWorkspace(
    private val context: Context,
    private val treeUri: Uri,
    private val displayPath: String = treeUri.toString()
) : AgentTaskWorkspace {
    override val mode: WorkspaceMode = WorkspaceMode.AgentTask(treeUri, displayPath, PrivilegeLevel.STANDARD)

    override suspend fun requestAccess(): Boolean = runCatching {
        val grants = context.contentResolver.persistedUriPermissions
        grants.any { it.uri == treeUri && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)

    override fun resolveSandboxPath(path: String) = throw SecurityException("SAF paths are URIs, not filesystem paths")

    private fun components(path: String): List<String> {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || it.contains('\u0000') }) throw SecurityException("Invalid workspace path")
        return parts
    }

    private fun locate(path: String, createParents: Boolean): Pair<DocumentFile, String> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw SecurityException("Invalid SAF tree grant")
        var folder = root
        val parts = components(path)
        parts.dropLast(1).forEach { name ->
            val next = folder.findFile(name) ?: if (createParents) folder.createDirectory(name) else null
            folder = next?.takeIf { it.isDirectory } ?: throw java.io.FileNotFoundException("Directory not found")
        }
        return folder to parts.last()
    }

    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        val (parent, name) = locate(path, false)
        val file = parent.findFile(name)?.takeIf { it.isFile } ?: throw java.io.FileNotFoundException(path)
        if (file.length() > SandboxWorkspace.MAX_FILE_BYTES) throw IllegalArgumentException("File exceeds 2 MiB read limit")
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= SandboxWorkspace.MAX_FILE_BYTES) { "File exceeds 2 MiB read limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw java.io.IOException("Cannot open SAF document")
    }

    override suspend fun write(path: String, content: ByteArray): FileChange = withContext(Dispatchers.IO) {
        require(content.size <= SandboxWorkspace.MAX_FILE_BYTES) { "File exceeds 2 MiB write limit" }
        val (parent, name) = locate(path, true)
        val old = parent.findFile(name)?.takeIf { it.isFile }
        var backup: String? = null
        if (old != null) {
            if (old.length() > SandboxWorkspace.MAX_FILE_BYTES) throw IllegalArgumentException("Cannot back up file over 2 MiB")
            val backupFile = parent.findFile("$name.bak") ?: parent.createFile("application/octet-stream", "$name.bak")
            if (backupFile != null) {
                context.contentResolver.openInputStream(old.uri)?.use { input ->
                    context.contentResolver.openOutputStream(backupFile.uri, "wt")?.use { output -> input.copyTo(output) }
                }
                backup = backupFile.uri.toString()
            }
        }
        val target = old ?: parent.createFile("application/octet-stream", name) ?: throw java.io.IOException("Cannot create SAF document")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(content) } ?: throw java.io.IOException("Cannot write SAF document")
        FileChange(path, content.size.toLong(), backup, content.toString(Charsets.UTF_8).lineSequence().take(8).joinToString("\n").take(4_000))
    }

    companion object {
        fun takePersistableGrant(context: Context, resultData: Intent) {
            val uri = resultData.data ?: return
            val flags = resultData.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }
}
