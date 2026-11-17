package chat.vinlagon.secret.workspace

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.Base64

/** GitHub Contents API adapter; token must remain in memory and must never be logged. */
class GitHubTreeWorkspace(
    token: String,
    repo: String,
    branch: String,
    private val http: HttpClient = HttpClient(OkHttp)
) : GitTreeWorkspace {
    private val secret = token.also { require(it.isNotBlank()) }
    private val repository = repo.also {
        val parts = it.split('/')
        require(parts.size == 2 && parts.all { part -> part.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) })
    }
    private val ref = branch.also { require(it.isNotBlank() && it.length <= 255 && !it.contains("..") && it.none { character -> character.code < 0x20 || character.code == 0x7f }) }
    override val mode = WorkspaceMode.GitHub(secret, repository, ref)
    private val base = "https://api.github.com/repos/$repository"

    override fun resolveSandboxPath(path: String) = throw SecurityException("GitHub VFS paths are repository-relative")
    private fun path(value: String): String {
        val parts = value.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) throw SecurityException("Invalid repository path")
        return parts.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
    private suspend fun requestText(url: String): String = http.get(url) {
        header(HttpHeaders.Authorization, "Bearer $secret")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "vinlagonChat")
    }.let { response ->
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) error("GitHub API ${response.status.value}: ${body.take(600)}")
        body
    }

    override suspend fun read(path: String): ByteArray {
        val encoded = path(path)
        val response = Json.parseToJsonElement(requestText("$base/contents/$encoded?ref=${URLEncoder.encode(ref, "UTF-8")}")).jsonObject
        val encodedContent = response["content"]?.jsonPrimitive?.contentOrNull ?: error("GitHub returned no file content")
        val bytes = Base64.getMimeDecoder().decode(encodedContent)
        require(bytes.size <= SandboxWorkspace.MAX_FILE_BYTES) { "File exceeds 2 MiB read limit" }
        return bytes
    }

    override suspend fun write(path: String, content: ByteArray): FileChange {
        require(content.size <= SandboxWorkspace.MAX_FILE_BYTES) { "File exceeds 2 MiB write limit" }
        val encodedPath = path(path)
        val url = "$base/contents/$encodedPath"
        val current = runCatching { Json.parseToJsonElement(requestText("$url?ref=${URLEncoder.encode(ref, "UTF-8")}")).jsonObject }.getOrNull()
        val sha = current?.get("sha")?.jsonPrimitive?.contentOrNull
        val payload = buildJsonObject {
            put("message", "Update $path via vinlagonChat")
            put("content", Base64.getEncoder().encodeToString(content))
            put("branch", ref)
            sha?.let { put("sha", it) }
        }
        val response = http.put(url) {
            header(HttpHeaders.Authorization, "Bearer $secret")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("User-Agent", "vinlagonChat")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) error("GitHub write failed ${response.status.value}: ${body.take(600)}")
        return FileChange(path, content.size.toLong(), null, content.toString(Charsets.UTF_8).lineSequence().take(8).joinToString("\n").take(4_000))
    }

    override suspend fun listTree(path: String): List<String> {
        val url = "$base/git/trees/${URLEncoder.encode(ref, "UTF-8")}?recursive=1"
        val tree = Json.parseToJsonElement(requestText(url)).jsonObject["tree"]?.jsonArray.orEmpty()
        val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return tree.mapNotNull { item -> item.jsonObject["path"]?.jsonPrimitive?.contentOrNull }
            .filter { it.startsWith(prefix) }
            .take(300)
    }
}
