package chat.vinlagon.secret.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import chat.vinlagon.secret.agent.ApiChatClient
import chat.vinlagon.secret.agent.ApiModelConfig
import chat.vinlagon.secret.agent.ApiProvider
import chat.vinlagon.secret.agent.SecureModelConfigStore
import chat.vinlagon.secret.theme.Paper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

private val UiMono = FontFamily.Monospace
private val UiSerif = FontFamily.Serif
private fun phrase(en: Boolean, english: String, russian: String) = if (en) english else russian

private val docsRoot = FolderNode(name = "Workspace")

private fun loadRealWorkspaceNode(context: android.content.Context, subPath: List<String>, safUriString: String?): FolderNode {
    if (!safUriString.isNullOrBlank()) {
        val tree = runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(safUriString)) }.getOrNull()
        if (tree != null && tree.exists()) {
            var current: androidx.documentfile.provider.DocumentFile = tree
            for (segment in subPath) {
                current = current.findFile(segment) ?: break
            }
            val children = current.listFiles()
            val folders = children.filter { it.isDirectory }.map { FolderNode(it.name ?: "folder") }
            val files = children.filter { it.isFile }.map { doc ->
                val text = runCatching {
                    context.contentResolver.openInputStream(doc.uri)?.bufferedReader()?.use { it.readText() } ?: ""
                }.getOrDefault("")
                DemoFile(doc.name ?: "file", text)
            }
            return FolderNode(current.name ?: "Workspace", files = files, folders = folders)
        }
    }
    var dir = java.io.File(context.filesDir, "workspace").apply { mkdirs() }
    for (segment in subPath) {
        val next = java.io.File(dir, segment)
        if (next.exists() && next.isDirectory) dir = next
    }
    val entries = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
    val folders = entries.filter { it.isDirectory }.map { FolderNode(it.name) }
    val files = entries.filter { it.isFile }.map { file ->
        val text = runCatching { if (file.length() <= 256_000) file.readText() else "(File too large to preview)" }.getOrDefault("")
        DemoFile(file.name, text)
    }
    return FolderNode(if (subPath.isEmpty()) "Workspace" else dir.name, files = files, folders = folders)
}

@Composable
fun AgentHome() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { context.getSharedPreferences("vinlagon-ui-v2", 0) }
    val scope = rememberCoroutineScope()
    var en by remember { mutableStateOf(prefs.getBoolean("english", true)) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var skillsScreenOpen by remember { mutableStateOf(false) }
    var workspaceOpen by remember { mutableStateOf(false) }
    var workspaceWideOpen by remember { mutableStateOf(false) }
    var currentFolder by remember { mutableStateOf(emptyList<String>()) }
    var currentFile by remember { mutableStateOf<DemoFile?>(null) }
    val generatedFiles = remember { mutableStateListOf<DemoFile>() }
    var sourceMode by remember { mutableStateOf(false) }
    var showHistoryMenu by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var responseJob by remember { mutableStateOf<Job?>(null) }
    var statusToast by remember { mutableStateOf<String?>(null) }
    var toastUndo by remember { mutableStateOf<(() -> Unit)?>(null) }
    var modelDialog by remember { mutableStateOf(false) }
    var modelConfig by remember { mutableStateOf(runCatching { SecureModelConfigStore(context).load() }.getOrDefault(ApiModelConfig())) }
    var selectedModel by remember { mutableStateOf(prefs.getString("model_label", "claude-opus-4.6") ?: "claude-opus-4.6") }
    var selectedMode by remember { mutableStateOf(prefs.getString("mode", "Agent") ?: "Agent") }
    var compact by remember { mutableStateOf(prefs.getBoolean("compact", false)) }
    var animations by remember { mutableStateOf(prefs.getBoolean("animations", true)) }
    var textScale by remember { mutableFloatStateOf(prefs.getFloat("text_scale", 1f)) }
    var connectedGitHub by remember { mutableStateOf(prefs.getBoolean("github", false)) }
    var connectedDrive by remember { mutableStateOf(prefs.getBoolean("drive", false)) }
    var toolToggles by remember { mutableStateOf(mapOf("web_search" to true, "read_page" to true, "read_file" to true, "write_file" to true, "edit_file" to true, "list_files" to true, "run_command" to false, "github" to false)) }
    var devModeUnlocked by remember { mutableStateOf(prefs.getBoolean("dev_mode", false)) }
    var devPasswordDialog by remember { mutableStateOf(false) }
    var devPasswordInput by remember { mutableStateOf("") }
    var customSystemPrompt by remember { mutableStateOf(prefs.getString("custom_system_prompt", "") ?: "") }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }
    val profiles = remember { mutableStateListOf(*defaultProviderPresets().toTypedArray()) }
    var activeProfileId by remember { mutableStateOf(profiles.first().id) }
    val skills = remember {
        val saved = prefs.getString("saved_skills_json", null)
        val loaded = if (!saved.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(saved)
                (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    AgentSkill(
                        id = o.getString("id"),
                        command = o.getString("command"),
                        titleEn = o.getString("titleEn"),
                        titleRu = o.getString("titleRu"),
                        descriptionEn = o.getString("descriptionEn"),
                        descriptionRu = o.getString("descriptionRu"),
                        promptTemplate = o.getString("promptTemplate"),
                        enabled = o.optBoolean("enabled", true),
                        custom = o.optBoolean("custom", false)
                    )
                }
            }.getOrNull()
        } else null
        mutableStateListOf(*(loaded ?: defaultClaudeCodeSkills()).toTypedArray())
    }
    LaunchedEffect(skills.toList()) {
        val arr = org.json.JSONArray()
        skills.forEach { s ->
            arr.put(org.json.JSONObject().apply {
                put("id", s.id); put("command", s.command); put("titleEn", s.titleEn); put("titleRu", s.titleRu)
                put("descriptionEn", s.descriptionEn); put("descriptionRu", s.descriptionRu)
                put("promptTemplate", s.promptTemplate); put("enabled", s.enabled); put("custom", s.custom)
            })
        }
        prefs.edit().putString("saved_skills_json", arr.toString()).apply()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            attachments.add(ChatAttachment(id = "${System.currentTimeMillis()}-${attachments.size}", name = name, sizeLabel = "file"))
        }
        if (uris.isNotEmpty()) statusToast = phrase(en, "Attached ${uris.size} file(s)", "Прикреплено файлов: ${uris.size}")
    }
    var drawerExpanded by remember { mutableStateOf(true) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<UiMessage>>(emptyList()) }
    var previousMessages by remember { mutableStateOf<List<UiMessage>?>(null) }
    val history = remember {
        val saved = prefs.getString("saved_history_json", null)
        val loaded = if (!saved.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(saved)
                (0 until arr.length()).map { idx ->
                    val o = arr.getJSONObject(idx)
                    val msgsArr = o.getJSONArray("messages")
                    val msgs = (0 until msgsArr.length()).map { mIdx ->
                        val mo = msgsArr.getJSONObject(mIdx)
                        UiMessage(mo.getString("role"), mo.getString("text"))
                    }
                    HistoryEntry(o.getString("id"), o.getString("title"), o.optString("group", "TODAY"), msgs)
                }
            }.getOrNull()
        } else null
        mutableStateListOf(*(loaded ?: emptyList()).toTypedArray())
    }
    LaunchedEffect(history.toList()) {
        val arr = org.json.JSONArray()
        history.take(30).forEach { h ->
            val msgsArr = org.json.JSONArray()
            h.messages.forEach { m ->
                msgsArr.put(org.json.JSONObject().apply { put("role", m.role); put("text", m.text) })
            }
            arr.put(org.json.JSONObject().apply {
                put("id", h.id); put("title", h.title); put("group", h.group); put("messages", msgsArr)
            })
        }
        prefs.edit().putString("saved_history_json", arr.toString()).apply()
    }
    fun showMessageToast(text: String, undo: (() -> Unit)? = null) {
        toastUndo = undo
        statusToast = text
        scope.launch { delay(5000); if (statusToast == text) { statusToast = null; toastUndo = null } }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                prefs.edit().putString("workspace_uri", uri.toString()).apply()
                showMessageToast(phrase(en, "Workspace folder linked", "Папка рабочего пространства подключена"))
            }.onFailure { showMessageToast(it.message ?: "Folder access failed") }
        }
    }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val selectedFile = currentFile
        if (uri != null && selectedFile != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(selectedFile.content.toByteArray()) }
            showMessageToast(phrase(en, "File exported", "Файл сохранён"))
        }.onFailure { showMessageToast(it.message ?: "Export failed") }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            val json = messages.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { "  {\"role\":\"${it.role}\",\"text\":\"${it.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\"}" }
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            showMessageToast(phrase(en, "Conversation exported", "Диалог экспортирован"))
        }.onFailure { showMessageToast(it.message ?: "Export failed") }
    }
    fun setLanguage(next: Boolean) { en = next; prefs.edit().putBoolean("english", next).apply() }
    fun saveChatToHistory() {
        val id = currentId ?: return
        if (messages.isNotEmpty()) {
            val old = history.indexOfFirst { it.id == id }
            val title = messages.firstOrNull { it.role == "user" }?.text?.take(36) ?: phrase(en, "New chat", "Новый чат")
            val entry = HistoryEntry(id, title, "TODAY", messages.toList())
            if (old >= 0) history[old] = entry else history.add(0, entry)
        }
    }
    fun newChat() {
        saveChatToHistory()
        messages = emptyList()
        currentId = null
        prompt = ""
        settingsOpen = false
        sidebarOpen = false
    }
    fun openHistory(entry: HistoryEntry) {
        saveChatToHistory()
        currentId = entry.id
        messages = entry.messages
        sidebarOpen = false
        settingsOpen = false
    }
    fun setVote(index: Int, value: Int) {
        messages = messages.toMutableList().also { if (index in it.indices) it[index] = it[index].copy(vote = value) }
    }
    fun replaceFileCard(file: DemoFile, newMessage: UiMessage) {
        messages = messages.map { if (it.fileName != null) it.copy(fileName = null, fileBody = "") else it } + newMessage.copy(fileName = file.name, fileBody = file.content)
    }
    fun sendMessage(raw: String = prompt) {
        val text = raw.trim()
        val attachedNames = attachments.map { it.name }
        if ((text.isBlank() && attachedNames.isEmpty()) || busy) return
        if (currentId == null) currentId = UUID.randomUUID().toString()
        val matchedSkill = skills.firstOrNull { it.enabled && text.startsWith(it.command, ignoreCase = true) }
        val effectiveText = if (matchedSkill != null) "${matchedSkill.promptTemplate}${text.removePrefix(matchedSkill.command).trim()}" else text
        val next = messages + UiMessage("user", text.ifBlank { attachedNames.joinToString(", ") }, attachments = attachedNames) + UiMessage("assistant", "")
        messages = next
        prompt = ""
        attachments.clear()
        busy = true
        val activeId = currentId
        responseJob = scope.launch {
            try {
                if (modelConfig.apiKey.isNotBlank()) {
                    val api = ApiChatClient()
                    val workspaceDir = java.io.File(context.filesDir, "workspace").apply { mkdirs() }
                    try {
                        val baseSystemPrompt = "You are a concise, careful assistant. When you need to execute a tool, wrap the JSON strictly in <tool_call>...</tool_call> tags on its own line, e.g. <tool_call>{\"tool\":\"write|read|edit|list|bash\",\"path\":\"...\",\"content\":\"...\",\"old_text\":\"...\",\"new_text\":\"...\",\"command\":\"...\"}</tool_call>. Never wrap example JSON in <tool_call> tags when just explaining tools. Give brief progress updates, not private reasoning."
                        val systemPrompt = if (devModeUnlocked && customSystemPrompt.isNotBlank()) "$baseSystemPrompt\n\n$customSystemPrompt" else baseSystemPrompt
                        val conversation = mutableListOf(
                            chat.vinlagon.secret.agent.ChatMessage("system", systemPrompt),
                            *messages.dropLast(1).dropLast(1).map { chat.vinlagon.secret.agent.ChatMessage(it.role, it.text) }.toTypedArray(),
                            chat.vinlagon.secret.agent.ChatMessage("user", effectiveText)
                        )
                        repeat(3) { step ->
                            val rawBuffer = StringBuilder()
                            api.stream(modelConfig, conversation) { chunk ->
                                rawBuffer.append(chunk)
                                val visible = rawBuffer.toString().replace(Regex("\\{\\s*\"tool\"\\s*:[^}]*\\}"), "").trim()
                                val last = messages.lastIndex
                                if (last >= 0 && messages[last].role == "assistant") {
                                    messages = messages.toMutableList().also { it[last] = it[last].copy(text = visible.take(60_000)) }
                                }
                            }
                            val fullText = rawBuffer.toString()
                            val toolMatch = Regex("\\{\\s*\"tool\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}").find(fullText) ?: return@repeat
                            val toolJson = toolMatch.value
                            val toolName = toolMatch.groupValues[1]
                            val pathArg = Regex("\"path\"\\s*:\\s*\"([^\"]*)\"").find(toolJson)?.groupValues?.get(1) ?: ""
                            val contentArg = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(toolJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                            val cmdArg = Regex("\"command\"\\s*:\\s*\"([^\"]*)\"").find(toolJson)?.groupValues?.get(1) ?: ""

                            val (toolEvent, toolResult, writtenFile) = when (toolName) {
                                "write" -> {
                                    val target = java.io.File(workspaceDir, pathArg.ifBlank { "note.md" })
                                    target.parentFile?.mkdirs()
                                    target.writeText(contentArg)
                                    val ev = ToolEvent("Write", target.name, listOf(ToolChild("↳", target.name, action = "Open")))
                                    Triple(ev, "Written ${target.name} (${contentArg.length} chars)", DemoFile(target.name, contentArg))
                                }
                                "read" -> {
                                    val target = java.io.File(workspaceDir, pathArg)
                                    val body = if (target.exists() && target.isFile) target.readText().take(8000) else "File not found: $pathArg"
                                    Triple(ToolEvent("Read", pathArg, listOf(ToolChild("↳", pathArg))), body, null)
                                }
                                "edit" -> {
                                    val oldArg = Regex("\"old_text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(toolJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                                    val newArg = Regex("\"new_text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(toolJson)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                                    val target = java.io.File(workspaceDir, pathArg)
                                    if (target.exists() && target.isFile && oldArg.isNotEmpty()) {
                                        val currentText = target.readText()
                                        if (currentText.contains(oldArg)) {
                                            val updated = currentText.replaceFirst(oldArg, newArg)
                                            target.writeText(updated)
                                            val ev = ToolEvent("Edit", target.name, listOf(
                                                ToolChild("-", oldArg.lines().firstOrNull()?.take(60) ?: oldArg.take(60)),
                                                ToolChild("+", newArg.lines().firstOrNull()?.take(60) ?: newArg.take(60))
                                            ))
                                            Triple(ev, "Edited ${target.name}", DemoFile(target.name, updated))
                                        } else {
                                            Triple(ToolEvent("Edit", pathArg, failed = true), "old_text not found in $pathArg", null)
                                        }
                                    } else {
                                        Triple(ToolEvent("Edit", pathArg, failed = true), "File not found: $pathArg", null)
                                    }
                                }
                                "list" -> {
                                    val list = workspaceDir.listFiles()?.joinToString("\n") { it.name } ?: "(empty)"
                                    Triple(ToolEvent("List", "workspace", listOf(ToolChild("↳", list.lines().size.toString() + " items"))), list, null)
                                }
                                "bash" -> {
                                    val out = runCatching {
                                        val proc = ProcessBuilder("sh", "-c", cmdArg).directory(workspaceDir).redirectErrorStream(true).start()
                                        proc.inputStream.bufferedReader().readText().take(4000)
                                    }.getOrElse { it.message ?: "Command failed" }
                                    Triple(ToolEvent("Bash", cmdArg, listOf(ToolChild("$", cmdArg))), out, null)
                                }
                                else -> Triple(ToolEvent(toolName, pathArg), "Unsupported tool: $toolName", null)
                            }
                            val last = messages.lastIndex
                            if (last >= 0 && messages[last].role == "assistant") {
                                messages = messages.toMutableList().also {
                                    it[last] = it[last].copy(
                                        tools = it[last].tools + toolEvent,
                                        fileName = writtenFile?.name ?: it[last].fileName,
                                        fileBody = writtenFile?.content ?: it[last].fileBody
                                    )
                                }
                            }
                            if (writtenFile != null) generatedFiles.add(writtenFile)
                            conversation.add(chat.vinlagon.secret.agent.ChatMessage("assistant", fullText))
                            conversation.add(chat.vinlagon.secret.agent.ChatMessage("user", "Tool '$toolName' result:\n$toolResult\nContinue your answer concisely without raw JSON."))
                        }
                    } finally { api.close() }
                } else {
                    val wantsFile = listOf("write", "create", "draft", "файл", "создай", "напиши", "составь", "черновик").any { text.contains(it, true) }
                    if (wantsFile) {
                        val plan = ToolEvent("Plan", "3 steps", listOf(ToolChild("1.", "Clarify the goal"), ToolChild("2.", "Write a reviewable draft"), ToolChild("3.", "Save it to the workspace")))
                        messages = messages.toMutableList().also { val i = it.lastIndex; it[i] = it[i].copy(text = "", tools = listOf(plan)) }
                        val stages = listOf("[agent] planning → drafting…", "[agent] writing → checking…")
                        stages.forEach { stage -> messages = messages.toMutableList().also { val i = it.lastIndex; it[i] = it[i].copy(text = stage) }; delay(520) }
                        val safeTitle = text.take(54).replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().ifBlank { "Workspace draft" }
                        val body = "# $safeTitle\n\n## Purpose\nA practical first draft based on your request. Review the details and add any dates or names before sharing.\n\n## Next steps\n- Confirm the audience and goal\n- Check the details\n- Share only when ready\n"
                        val file = DemoFile("output/draft-${System.currentTimeMillis() % 10000}.md", body)
                        File(context.filesDir, "sandbox/${file.path}").apply { parentFile?.mkdirs(); writeText(file.content) }
                        generatedFiles.add(file)
                        val tools = listOf(
                            plan,
                            ToolEvent("Wrote", "", listOf(ToolChild("›", "Saved", file.name, "${body.lines().size} lines", "open", "${body.lines().take(4).joinToString("\\n")}"))),
                            ToolEvent("Listed files", "2 items", listOf(ToolChild("›", "output", meta = "folder"), ToolChild("›", "README.md", meta = "1.2 KB")))
                        )
                        val result = "I made a first-pass draft and saved it in the workspace. Open the file card to review or edit it before using it."
                        var streamed = ""
                        result.split(Regex("(?<= )")).forEach { word ->
                            streamed += word
                            messages = messages.toMutableList().also { val i = it.lastIndex; it[i] = it[i].copy(text = streamed, tools = tools) }
                            delay(38)
                        }
                        replaceFileCard(file, messages.last().copy(text = streamed, tools = tools))
                    } else {
                        val answer = phrase(en,
                            "You’re in preview mode. I can organize a draft, show the workspace, and demonstrate file review. Add your provider key in Settings → Model to use live AI replies.",
                            "Сейчас включён режим предпросмотра. Я могу подготовить черновик и показать работу с файлами. Добавьте ключ провайдера в Настройки → Модель, чтобы включить ответы AI.")
                        var streamed = ""
                        answer.split(Regex("(?<= )")).forEach { word ->
                            streamed += word
                            val last = messages.lastIndex
                            messages = messages.toMutableList().also { it[last] = it[last].copy(text = streamed) }
                            delay(35)
                        }
                    }
                }
                saveChatToHistory()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                val last = messages.lastIndex
                if (last >= 0 && messages[last].role == "assistant") messages = messages.toMutableList().also { it[last] = it[last].copy(stopped = true) }
                throw cancel
            } catch (failure: Exception) {
                showMessageToast(failure.message ?: phrase(en, "Something went wrong", "Произошла ошибка"))
            } finally {
                busy = false
                responseJob = null
                saveChatToHistory()
            }
        }
    }
    fun stopGeneration() { responseJob?.cancel(); busy = false }
    fun clearHistory() {
        previousMessages = messages.toList()
        val oldHistory = history.toList()
        history.clear()
        messages = emptyList()
        currentId = null
        showMessageToast(phrase(en, "History cleared", "История очищена")) {
            history.addAll(oldHistory)
            messages = previousMessages.orEmpty()
            previousMessages = null
        }
    }

    val listState = rememberLazyListState()
    val showBack = listState.canScrollForward
    val backAction = when {
        currentFile != null -> { { currentFile = null; sourceMode = false } }
        settingsOpen -> { { settingsOpen = false } }
        workspaceOpen -> { { workspaceOpen = false } }
        sidebarOpen -> { { sidebarOpen = false } }
        workspaceWideOpen -> { { workspaceWideOpen = false } }
        else -> null
    }
    BackHandler(enabled = backAction != null) { backAction?.invoke() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Paper.paper0).onPreviewKeyEvent { if (it.key == Key.Escape && backAction != null) { backAction.invoke(); true } else false }) {
        val isWide = maxWidth >= 1024.dp
        val sheetHeight = maxHeight * 0.65f
        val sideWidth = if (isWide) 300.dp else maxWidth * 0.65f
        val visibleSidebar = isWide && sidebarOpen || !isWide && sidebarOpen
        val visibleWideWorkspace = isWide && workspaceWideOpen
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = maxOf(size.width, size.height) * 0.84f
                drawRect(Brush.radialGradient(listOf(Paper.paper1.copy(alpha = 0.22f), Paper.paper0), center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.12f), radius = radius))
                val random = Random(4821)
                repeat(320) {
                    val x = random.nextFloat() * size.width
                    val y = random.nextFloat() * size.height
                    drawCircle(Paper.ink0.copy(alpha = 0.018f), radius = random.nextFloat() * 0.7f + 0.25f, center = androidx.compose.ui.geometry.Offset(x, y))
                }
            }
            Row(Modifier.fillMaxSize()) {
                if (visibleSidebar && isWide) {
                    Sidebar(history, currentId, true, en, onClose = { sidebarOpen = false }, onNew = ::newChat, onSettings = { settingsOpen = true; sidebarOpen = false }, onOpenChat = ::openHistory, onDelete = { id -> history.removeAll { it.id == id } }, onRename = { id, title -> val index = history.indexOfFirst { it.id == id }; if (index >= 0) history[index] = history[index].copy(title = title) })
                }
                Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                    TopBar(
                        en = en, title = messages.firstOrNull { it.role == "user" }?.text,
                        onMenu = { sidebarOpen = !sidebarOpen }, onWorkspace = { if (isWide) workspaceWideOpen = !workspaceWideOpen else workspaceOpen = true },
                        onSettings = { settingsOpen = true }, onTitleClick = { if (messages.isNotEmpty()) showMessageToast(phrase(en, "Chat title", "Название чата")) },
                        wide = isWide && sidebarOpen
                    )
                    if (skillsScreenOpen) {
                        SkillsScreen(
                            en = en,
                            skills = skills,
                            onBack = { skillsScreenOpen = false },
                            onToggleSkill = { id, enabled ->
                                val idx = skills.indexOfFirst { it.id == id }
                                if (idx >= 0) skills[idx] = skills[idx].copy(enabled = enabled)
                            },
                            onAddSkill = { newSkill ->
                                skills.add(newSkill)
                                showMessageToast(phrase(en, "Skill added: ${newSkill.command}", "Скилл добавлен: ${newSkill.command}"))
                            },
                            onDeleteSkill = { id ->
                                skills.removeAll { it.id == id }
                            }
                        )
                    } else if (settingsOpen) {
                        SettingsScreen(
                            en = en, config = modelConfig, selectedModel = selectedModel, selectedMode = selectedMode,
                            tools = toolToggles, animations = animations, compact = compact, textScale = textScale,
                            connectedGitHub = connectedGitHub, connectedDrive = connectedDrive,
                            onBack = { settingsOpen = false }, onLanguage = ::setLanguage,
                            onModel = { modelDialog = true }, onMode = { selectedMode = it; prefs.edit().putString("mode", it).apply() },
                            onTool = { key, enabled -> toolToggles = toolToggles + (key to enabled) },
                            onAnimation = { animations = it; prefs.edit().putBoolean("animations", it).apply() },
                            onCompact = { compact = it; prefs.edit().putBoolean("compact", it).apply() },
                            onTextScale = { textScale = it; prefs.edit().putFloat("text_scale", it).apply() },
                            onGitHub = { connectedGitHub = !connectedGitHub; prefs.edit().putBoolean("github", connectedGitHub).apply(); if (!connectedGitHub) selectedMode = "Agent" },
                            onDrive = { connectedDrive = !connectedDrive; prefs.edit().putBoolean("drive", connectedDrive).apply() },
                            onFolder = { treePicker.launch(null) },
                            onClear = ::clearHistory,
                            onExport = { exportLauncher.launch("vinlagon-conversation.json") },
                            onAboutLink = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                            profiles = profiles, activeProfileId = activeProfileId,
                            onSelectProfile = { profile ->
                                activeProfileId = profile.id
                                selectedModel = profile.model
                                prefs.edit().putString("model_label", profile.model).apply()
                                modelConfig = modelConfig.copy(endpoint = profile.endpoint, model = profile.model)
                                showMessageToast(phrase(en, "Switched to ${profile.name}", "Профиль: ${profile.name}"))
                            },
                            skills = skills,
                            onToggleSkill = { id, enabled ->
                                val idx = skills.indexOfFirst { it.id == id }
                                if (idx >= 0) skills[idx] = skills[idx].copy(enabled = enabled)
                            },
                            devModeUnlocked = devModeUnlocked,
                            customSystemPrompt = customSystemPrompt,
                            onUnlockDevMode = { devPasswordDialog = true },
                            onLockDevMode = {
                                devModeUnlocked = false
                                prefs.edit().putBoolean("dev_mode", false).apply()
                                showMessageToast(phrase(en, "Developer mode disabled", "Режим разработчика выключен"))
                            },
                            onSaveSystemPrompt = { nextPrompt ->
                                customSystemPrompt = nextPrompt
                                prefs.edit().putString("custom_system_prompt", nextPrompt).apply()
                                showMessageToast(phrase(en, "System prompt saved", "Системный промпт сохранён"))
                            },
                            onOpenSkills = { skillsScreenOpen = true }
                        )
                    } else {
                        if (messages.isEmpty()) {
                            Landing(en, compact, onSuggestion = { sendMessage(it) })
                        } else {
                            ChatTimeline(
                                messages = messages, listState = listState, busy = busy, en = en, textScale = textScale, compact = compact,
                                reducedMotion = !animations, onCopy = { clipboard.setText(AnnotatedString(it)); showMessageToast(phrase(en, "Copied", "Скопировано")) },
                                onRetry = { val lastUser = messages.lastOrNull { it.role == "user" }?.text; if (lastUser != null) { messages = messages.dropLast(2); sendMessage(lastUser) } },
                                onVote = ::setVote, onOpenFile = { name, body ->
                                    val match = docsRoot.allFiles().firstOrNull { it.name == name } ?: DemoFile("output/$name", body)
                                    currentFile = match; sourceMode = false; if (!workspaceOpen && !isWide) workspaceOpen = true else workspaceWideOpen = true
                                }, modifier = Modifier.weight(1f)
                            )
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                            if (showBack && messages.isNotEmpty() && !settingsOpen) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    IconButton(onClick = { scope.launch { listState.animateScrollToItem(messages.lastIndex) } }, modifier = Modifier.size(36.dp).background(Paper.paper1, RoundedCornerShape(10.dp))) { Icon(Icons.Outlined.ArrowDownward, null, tint = Paper.ink1, modifier = Modifier.size(18.dp)) }
                                }
                            }
                            if (attachments.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    attachments.toList().forEach { att ->
                                        Surface(color = Paper.paper3, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Paper.line0)) {
                                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(att.name, color = Paper.ink1, fontSize = 12.sp, fontFamily = UiMono, maxLines = 1)
                                                Spacer(Modifier.width(6.dp))
                                                Text("×", Modifier.clickable { attachments.remove(att) }, color = Paper.ink2, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            if (prompt.startsWith("/")) {
                                val matchingSkills = skills.filter { it.enabled && it.command.startsWith(prompt.substringBefore(" "), ignoreCase = true) }.take(4)
                                if (matchingSkills.isNotEmpty()) {
                                    Surface(color = Paper.paper2, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Paper.line0), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                        Column(Modifier.padding(6.dp)) {
                                            matchingSkills.forEach { skill ->
                                                Row(
                                                    Modifier.fillMaxWidth().clickable { prompt = "${skill.command} " }.padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(skill.command, color = Paper.ink0, fontSize = 12.5.sp, fontFamily = UiMono, fontWeight = FontWeight.SemiBold)
                                                    Spacer(Modifier.width(10.dp))
                                                    Text(if (en) skill.descriptionEn else skill.descriptionRu, color = Paper.ink2, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Composer(
                                value = prompt, onValue = { prompt = it }, busy = busy, model = selectedModel,
                                hint = phrase(en, if (messages.isEmpty()) "Ask anything…" else "What would you like to do?", if (messages.isEmpty()) "Спросите о чём угодно…" else "Что хотите сделать?"),
                                caption = "~${(messages.sumOf { it.text.length } + prompt.length) / 4} tok · " + phrase(en, "AI can make mistakes — check important details", "AI может ошибаться — проверяйте важное"),
                                canSend = prompt.isNotBlank() || attachments.isNotEmpty(), onModel = { settingsOpen = true; modelDialog = true },
                                onAttach = { filePicker.launch(arrayOf("*/*")) },
                                onSend = { sendMessage() }, onStop = ::stopGeneration, en = en
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                if (visibleWideWorkspace) {
                    WorkspacePanel(
                        height = this@BoxWithConstraints.maxHeight, en = en, currentFolder = currentFolder, currentFile = currentFile, sourceMode = sourceMode, extraFiles = generatedFiles,
                        animations = animations, onClose = { workspaceWideOpen = false; currentFile = null }, onBack = { if (currentFile != null) currentFile = null else currentFolder = currentFolder.dropLast(1) }, onFolder = { currentFolder = it },
                        onFile = { currentFile = it; sourceMode = false }, onSource = { sourceMode = it },
                        onDownload = { currentFile?.let { downloadLauncher.launch(it.name) } },
                        onCopy = { currentFile?.let { clipboard.setText(AnnotatedString(it.content)); showMessageToast(phrase(en, "Copied", "Скопировано")) } },
                        onPickFolder = { treePicker.launch(null) },
                        modifier = Modifier.width(420.dp).fillMaxHeight()
                    )
                }
            }
            if (!isWide) {
                AnimatedVisibility(sidebarOpen, modifier = Modifier.fillMaxSize(), enter = fadeIn(tween(if (animations) 240 else 1)), exit = fadeOut(tween(if (animations) 180 else 1))) {
                    PanelScrim(Modifier.fillMaxSize(), animations) { sidebarOpen = false }
                }
                AnimatedVisibility(
                    sidebarOpen,
                    modifier = Modifier.fillMaxHeight().width(sideWidth).align(Alignment.CenterStart),
                    enter = slideInHorizontally(tween(if (animations) 360 else 1)) { -it },
                    exit = slideOutHorizontally(tween(if (animations) 360 else 1)) { -it }
                ) {
                    Sidebar(
                        history, currentId, false, en, onClose = { sidebarOpen = false }, onNew = ::newChat,
                        onSettings = { settingsOpen = true; sidebarOpen = false }, onOpenChat = ::openHistory,
                        onDelete = { id -> history.removeAll { it.id == id } }, onRename = { id, title -> val index = history.indexOfFirst { it.id == id }; if (index >= 0) history[index] = history[index].copy(title = title) },
                        modifier = Modifier.fillMaxHeight().width(sideWidth)
                    )
                }
                AnimatedVisibility(workspaceOpen, modifier = Modifier.fillMaxSize(), enter = fadeIn(tween(if (animations) 240 else 1)), exit = fadeOut(tween(if (animations) 180 else 1))) {
                    PanelScrim(Modifier.fillMaxSize(), animations) { workspaceOpen = false; currentFile = null }
                }
                AnimatedVisibility(
                    workspaceOpen,
                    modifier = Modifier.fillMaxWidth().height(sheetHeight).align(Alignment.BottomCenter),
                    enter = slideInVertically(tween(if (animations) 360 else 1)) { it },
                    exit = slideOutVertically(tween(if (animations) 360 else 1)) { it }
                ) {
                    WorkspacePanel(
                        height = sheetHeight, en = en, currentFolder = currentFolder, currentFile = currentFile, sourceMode = sourceMode, extraFiles = generatedFiles,
                        animations = animations, onClose = { workspaceOpen = false; currentFile = null }, onBack = { if (currentFile != null) currentFile = null else currentFolder = currentFolder.dropLast(1) }, onFolder = { currentFolder = it },
                        onFile = { currentFile = it; sourceMode = false }, onSource = { sourceMode = it },
                        onDownload = { currentFile?.let { downloadLauncher.launch(it.name) } },
                        onCopy = { currentFile?.let { clipboard.setText(AnnotatedString(it.content)); showMessageToast(phrase(en, "Copied", "Скопировано")) } },
                        onPickFolder = { treePicker.launch(null) },
                        modifier = Modifier.fillMaxWidth().height(sheetHeight)
                    )
                }
            }
            statusToast?.let { text ->
                LaunchedEffect(text) {
                    delay(2500)
                    if (statusToast == text) { statusToast = null; toastUndo = null }
                }
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 148.dp).navigationBarsPadding().background(Paper.ink0, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, color = Paper.paper2, fontSize = 14.sp)
                    toastUndo?.let { undo ->
                        Spacer(Modifier.width(16.dp))
                        Text(phrase(en, "Undo", "Отменить"), Modifier.clickable { undo(); statusToast = null; toastUndo = null }, color = Paper.paper2, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
                    }
                }
            }
        }
    }

    if (modelDialog) {
        ModelConfigDialog(modelConfig, en, selectedModel, onDismiss = { modelDialog = false }, onSave = { next, label ->
            runCatching { SecureModelConfigStore(context).save(next); modelConfig = next; selectedModel = label; prefs.edit().putString("model_label", label).apply(); modelDialog = false }
                .onFailure { showMessageToast(it.message ?: "Could not save model settings") }
        })
    }
    if (devPasswordDialog) {
        AlertDialog(
            onDismissRequest = { devPasswordDialog = false; devPasswordInput = "" },
            containerColor = Paper.paper1,
            titleContentColor = Paper.ink0,
            textContentColor = Paper.ink0,
            title = { Text(phrase(en, "Developer settings", "Настройки разработчика"), fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = devPasswordInput,
                    onValueChange = { devPasswordInput = it },
                    singleLine = true,
                    label = { Text(phrase(en, "Password", "Пароль")) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (devPasswordInput == "ADMIN2012") {
                        devModeUnlocked = true
                        prefs.edit().putBoolean("dev_mode", true).apply()
                        devPasswordDialog = false
                        devPasswordInput = ""
                        showMessageToast(phrase(en, "Developer mode enabled", "Режим разработчика включён"))
                    } else {
                        showMessageToast(phrase(en, "Incorrect password", "Неверный пароль"))
                    }
                }) { Text(phrase(en, "Unlock", "Открыть"), color = Paper.ink0) }
            },
            dismissButton = {
                TextButton(onClick = { devPasswordDialog = false; devPasswordInput = "" }) {
                    Text(phrase(en, "Cancel", "Отмена"), color = Paper.ink2)
                }
            }
        )
    }
}

@Composable
private fun TopBar(en: Boolean, title: String?, onMenu: () -> Unit, onWorkspace: () -> Unit, onSettings: () -> Unit, onTitleClick: () -> Unit, wide: Boolean) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!wide) {
            OutlinedGlyphButton("☰", phrase(en, "Open chats", "Открыть чаты"), onMenu)
        } else Spacer(Modifier.width(40.dp))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (!title.isNullOrBlank()) Row(Modifier.widthIn(max = 240.dp).clickable(onClick = onTitleClick), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Paper.ink1, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(" ⌄", color = Paper.ink2, fontSize = 14.sp)
            }
        }
        OutlinedIconButton(onClick = onWorkspace, modifier = Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.7.dp, Paper.line1), colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Paper.ink0)) { Icon(Icons.Outlined.FolderOpen, phrase(en, "Workspace", "Рабочая папка"), modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun OutlinedGlyphButton(glyph: String, description: String, onClick: () -> Unit) {
    Surface(Modifier.size(40.dp).clickable(onClick = onClick), color = Color.Transparent, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.7.dp, Paper.line1)) {
        Box(contentAlignment = Alignment.Center) { Text(glyph, color = Paper.ink0, fontSize = 20.sp, modifier = Modifier.semanticsDescription(description)) }
    }
}

private fun Modifier.semanticsDescription(value: String): Modifier = this.semantics { contentDescription = value }

@Composable
private fun Landing(en: Boolean, compact: Boolean, onSuggestion: (String) -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(bottom = if (compact) 0.dp else 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Vinlagon", color = Paper.ink0, fontFamily = UiSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            Spacer(Modifier.height(20.dp))
            Text(phrase(en, "What would you\nlike to do?", "Что хотите\nсделать?"), color = Paper.ink1, fontFamily = UiSerif, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 43.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(phrase(en, "A thoughtful assistant for work in progress.", "Спокойный помощник для ваших задач."), color = Paper.ink2, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LandingSuggestion(phrase(en, "Draft a file", "Создать черновик")) { onSuggestion(phrase(en, "Create a short project brief", "Создай короткий проектный бриф")) }
                LandingSuggestion(phrase(en, "Explore notes", "Изучить заметки")) { onSuggestion(phrase(en, "Summarize my workspace notes", "Суммируй заметки рабочего пространства")) }
            }
        }
    }
}

@Composable
private fun RowScope.LandingSuggestion(text: String, onClick: () -> Unit) {
    Surface(Modifier.weight(1f).heightIn(min = 44.dp).clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), color = Paper.paper1, border = BorderStroke(1.dp, Paper.line0)) {
        Box(contentAlignment = Alignment.Center) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 10.dp), color = Paper.ink1, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ChatTimeline(
    messages: List<UiMessage>, listState: androidx.compose.foundation.lazy.LazyListState, busy: Boolean, en: Boolean, textScale: Float, compact: Boolean,
    reducedMotion: Boolean, onCopy: (String) -> Unit, onRetry: () -> Unit, onVote: (Int, Int) -> Unit,
    onOpenFile: (String, String) -> Unit, modifier: Modifier = Modifier
) {
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 20.dp), state = listState, contentPadding = PaddingValues(top = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
        itemsIndexed(messages) { index, message ->
            when (message.role) {
                "user" -> UserBubble(message.text, message.attachments)
                "assistant" -> Column(Modifier.fillMaxWidth()) {
                    if (message.tools.isNotEmpty()) message.tools.forEach { event -> ToolGroup(event, reducedMotion) { action -> if (action.isNotBlank() && message.fileName != null) onOpenFile(message.fileName, message.fileBody) } }
                    if (busy && index == messages.lastIndex) {
                        Row(verticalAlignment = Alignment.CenterVertically) { PulseLabel("[agent] search → draft → check…", !reducedMotion) }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (message.text.isNotBlank()) MarkdownText(message.text + if (busy && index == messages.lastIndex) " ▍" else "", textScale = textScale)
                    if (message.stopped) Text("■ ${phrase(en, "stopped", "остановлено")}", Modifier.padding(top = 6.dp), color = Paper.ink2, fontSize = 12.sp)
                    if (message.fileName != null) {
                        Spacer(Modifier.height(12.dp))
                        FileCard(message.fileName, message.fileBody, reducedMotion) { onOpenFile(message.fileName, message.fileBody) }
                    }
                    if (!busy && index == messages.lastIndex && message.text.isNotBlank()) AssistantActions(message.vote, { onCopy(message.text) }, onRetry, { onVote(index, it) }, en)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Sidebar(
    history: SnapshotStateList<HistoryEntry>, selected: String?, wide: Boolean, en: Boolean,
    onClose: () -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onOpenChat: (HistoryEntry) -> Unit,
    onDelete: (String) -> Unit, onRename: (String, String) -> Unit, modifier: Modifier = Modifier
) {
    var renameEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    Column(modifier.fillMaxHeight().background(Paper.paper1).then(if (wide) Modifier.width(300.dp) else Modifier.statusBarsPadding()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Arena", Modifier.weight(1f), color = Paper.ink0, fontFamily = UiSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)
            CloseButton(phrase(en, "Close chats", "Закрыть чаты"), onClose)
        }
        SidebarAction("＋", phrase(en, "New chat", "Новый чат"), false, onNew)
        Spacer(Modifier.height(4.dp))
        SidebarAction("⚙", phrase(en, "Settings", "Настройки"), false, onSettings)
        Spacer(Modifier.height(18.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            listOf("TODAY", "YESTERDAY", "OLDER").forEach { group ->
                val entries = history.filter { it.group == group }
                if (entries.isNotEmpty()) {
                    item { PaperSectionLabel(when (group) { "TODAY" -> phrase(en, "Today", "Сегодня"); "YESTERDAY" -> phrase(en, "Yesterday", "Вчера"); else -> phrase(en, "Older", "Более старые") }, Modifier.padding(top = 14.dp, bottom = 6.dp)) }
                    itemsIndexed(entries, key = { _, item -> item.id }) { _, item ->
                        var expanded by remember(item.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (selected == item.id) Paper.paper3 else Color.Transparent).clickable { onOpenChat(item) }.padding(start = 10.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.title, Modifier.weight(1f), color = Paper.ink0, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box {
                                androidx.compose.material3.IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreHoriz, phrase(en, "Chat options", "Действия с чатом"), tint = Paper.ink2) }
                                DropdownMenu(expanded, { expanded = false }) {
                                    DropdownMenuItem(text = { Text(phrase(en, "Rename", "Переименовать")) }, onClick = { renameEntry = item; expanded = false })
                                    DropdownMenuItem(text = { Text(phrase(en, "Delete", "Удалить")) }, onClick = { onDelete(item.id); expanded = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    renameEntry?.let { entry ->
        var title by remember(entry) { mutableStateOf(entry.title) }
        AlertDialog(onDismissRequest = { renameEntry = null }, title = { Text(phrase(en, "Rename chat", "Переименовать чат")) }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { onRename(entry.id, title); renameEntry = null }) { Text(phrase(en, "Save", "Сохранить")) } }, dismissButton = { TextButton(onClick = { renameEntry = null }) { Text(phrase(en, "Cancel", "Отмена")) } })
    }
}

@Composable
private fun SidebarAction(glyph: String, title: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Paper.paper3 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(glyph, color = Paper.ink1, fontSize = 19.sp, modifier = Modifier.width(28.dp))
        Text(title, color = Paper.ink0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CloseButton(description: String, onClick: () -> Unit) {
    Surface(Modifier.size(40.dp).clickable(onClick = onClick), color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Box(contentAlignment = Alignment.Center) { Text("×", color = Paper.ink0, fontSize = 27.sp, modifier = Modifier.semanticsDescription(description)) }
    }
}

@Composable
private fun PanelScrim(modifier: Modifier, animations: Boolean, onDismiss: () -> Unit) {
    Box(modifier.background(Paper.scrim).clickable(onClick = onDismiss).then(if (animations) Modifier else Modifier))
}

@Composable
private fun WorkspacePanel(
    height: androidx.compose.ui.unit.Dp, en: Boolean, currentFolder: List<String>, currentFile: DemoFile?, sourceMode: Boolean, extraFiles: List<DemoFile>, animations: Boolean,
    onClose: () -> Unit, onBack: () -> Unit, onFolder: (List<String>) -> Unit, onFile: (DemoFile) -> Unit, onSource: (Boolean) -> Unit,
    onDownload: () -> Unit, onCopy: () -> Unit, onPickFolder: () -> Unit = {}, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val safUri = context.getSharedPreferences("vinlagon-ui-v2", 0).getString("workspace_uri", null)
    val folder = remember(currentFolder, extraFiles, safUri) {
        loadRealWorkspaceNode(context, currentFolder, safUri)
    }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    Column(
        modifier.height(height).fillMaxWidth().navigationBarsPadding()
            .pointerInput(Unit) { detectVerticalDragGestures(onVerticalDrag = { _, amount -> dragTotal += amount }, onDragEnd = { if (dragTotal > 52f) onClose(); dragTotal = 0f }) }
            .background(Paper.paper1, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .padding(horizontal = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) { detectVerticalDragGestures(onVerticalDrag = { _, amount -> dragTotal += amount }, onDragEnd = { if (dragTotal > 32f) onClose(); dragTotal = 0f }) }, contentAlignment = Alignment.Center) {
            Box(Modifier.width(44.dp).height(4.dp).background(Paper.paper3, RoundedCornerShape(4.dp)))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (currentFile != null || currentFolder.isNotEmpty()) {
                Surface(Modifier.size(40.dp).clickable(onClick = onBack), color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ArrowBack, phrase(en, "Back", "Назад"), tint = Paper.ink1) }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(if (currentFile != null) currentFile.name else folder.name, color = Paper.ink0, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (currentFile == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Workspace", Modifier.clickable { onFolder(emptyList()) }, color = Paper.ink2, fontSize = 12.5.sp, fontFamily = UiMono)
                    currentFolder.forEachIndexed { index, part ->
                        Text(" › ", color = Paper.ink3, fontSize = 12.5.sp, fontFamily = UiMono)
                        Text(part, Modifier.clickable { onFolder(currentFolder.take(index + 1)) }, color = Paper.ink2, fontSize = 12.5.sp, fontFamily = UiMono, maxLines = 1)
                    }
                }
            }
            var showNewFileDialog by remember { mutableStateOf(false) }
            var newFileName by remember { mutableStateOf("") }
            var refreshTick by remember { mutableIntStateOf(0) }
            if (currentFile != null) {
                Text(phrase(en, "Delete", "Удалить"), Modifier.clickable {
                    val target = java.io.File(java.io.File(context.filesDir, "workspace"), (currentFolder + currentFile.name).joinToString("/"))
                    if (target.exists()) target.delete()
                    refreshTick++
                    onBack()
                }.padding(horizontal = 8.dp, vertical = 6.dp), color = Paper.ink2, fontSize = 12.sp, fontFamily = UiMono)
                IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Download, phrase(en, "Download", "Скачать"), tint = Paper.ink1) }
            } else {
                Text("+", Modifier.clickable { showNewFileDialog = true }.padding(horizontal = 8.dp, vertical = 6.dp), color = Paper.ink0, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(phrase(en, "Folder", "Папка"), Modifier.clickable(onClick = onPickFolder).padding(horizontal = 8.dp, vertical = 6.dp), color = Paper.ink2, fontSize = 12.sp, fontFamily = UiMono)
            }
            if (showNewFileDialog) {
                AlertDialog(
                    onDismissRequest = { showNewFileDialog = false; newFileName = "" },
                    containerColor = Paper.paper1,
                    titleContentColor = Paper.ink0,
                    textContentColor = Paper.ink0,
                    title = { Text(phrase(en, "New file", "Новый файл"), fontWeight = FontWeight.SemiBold) },
                    text = {
                        OutlinedTextField(
                            value = newFileName,
                            onValueChange = { newFileName = it },
                            singleLine = true,
                            label = { Text(phrase(en, "File name (e.g. notes.md)", "Имя файла (напр. notes.md)")) }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val clean = newFileName.trim()
                            if (clean.isNotEmpty()) {
                                val dir = java.io.File(java.io.File(context.filesDir, "workspace"), currentFolder.joinToString("/")).apply { mkdirs() }
                                val f = java.io.File(dir, clean)
                                if (!f.exists()) f.writeText("")
                                refreshTick++
                                showNewFileDialog = false
                                newFileName = ""
                                onFile(DemoFile(clean, ""))
                            }
                        }) { Text(phrase(en, "Create", "Создать"), color = Paper.ink0) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewFileDialog = false; newFileName = "" }) {
                            Text(phrase(en, "Cancel", "Отмена"), color = Paper.ink2)
                        }
                    }
                )
            }
            CloseButton(phrase(en, "Close workspace", "Закрыть рабочую папку"), onClose)
        }
        if (currentFile == null) {
            val files = folder.allFilesHere() + if (currentFolder.lastOrNull() == "output") extraFiles else emptyList()
            val allFiles = folder.allFiles() + if (currentFolder.isEmpty()) extraFiles else files.filter { it in extraFiles }
            val totalBytes = allFiles.sumOf { it.size }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatMB(totalBytes)} MB", color = Paper.ink2, fontSize = 12.5.sp, fontFamily = UiMono)
                Text("${allFiles.size.coerceAtLeast(0)}/${"10K"} ${phrase(en, "files", "файлов")}", color = Paper.ink2, fontSize = 12.5.sp, fontFamily = UiMono)
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Paper.line0)) { Box(Modifier.fillMaxWidth((allFiles.size / 10000f).coerceIn(0.01f, 1f)).fillMaxHeight().background(Paper.ink2)) }
            Column(Modifier.weight(1f).padding(top = 10.dp).verticalScroll(rememberScrollState())) {
                folder.folders.forEach { child ->
                    val count = child.allFiles().size + if (child.name == "output") extraFiles.size else 0
                    WorkspaceRow(child.name, "$count ${phrase(en, "items", "файла")}", "›") { onFolder(currentFolder + child.name) }
                }
                files.forEach { file -> WorkspaceRow(file.name, formatMB(file.size) + " MB", file.extension) { onFile(file) } }
                if (folder.folders.isEmpty() && files.isEmpty()) Text(phrase(en, "Nothing here yet — the agent can create files here.", "Пока пусто — агент создаст файлы здесь."), Modifier.fillMaxWidth().padding(vertical = 28.dp), color = Paper.ink2, fontSize = 14.sp)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp).background(Paper.paper3, RoundedCornerShape(12.dp)).padding(3.dp)) {
                Segment(phrase(en, "Preview", "Просмотр"), !sourceMode, Modifier.weight(1f)) { onSource(false) }
                Segment(phrase(en, "Source", "Исходник"), sourceMode, Modifier.weight(1f)) { onSource(true) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Paper.paper2).border(BorderStroke(1.dp, Paper.line0), RoundedCornerShape(12.dp)).padding(14.dp)) {
                if (sourceMode) Text(currentFile.content, Modifier.fillMaxSize().verticalScroll(rememberScrollState()), color = Paper.ink1, fontSize = 12.5.sp, lineHeight = 19.sp, fontFamily = UiMono)
                else if (currentFile.extension == "HTML") {
                    AndroidView(factory = { view -> WebView(view).apply { settings.javaScriptEnabled = false; webViewClient = WebViewClient(); loadDataWithBaseURL(null, currentFile.content.replace("<head>", "<head><style>body{font:16px system-ui;background:#FCFBF5;color:#221E15;padding:24px}a{color:#5F5947}</style>"), "text/html", "UTF-8", null) } }, update = { }, modifier = Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        currentFile.content.lines().forEach { line ->
                            when {
                                line.startsWith("# ") -> Text(line.drop(2), color = Paper.ink0, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, fontFamily = UiSerif, modifier = Modifier.padding(bottom = 8.dp))
                                line.startsWith("## ") -> Text(line.drop(3), color = Paper.ink0, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                                line.startsWith("- ") -> Text("• ${line.drop(2)}", color = Paper.ink0, fontSize = 15.sp, lineHeight = 23.sp)
                                line.startsWith("`") && line.endsWith("`") -> Text(line.trim('`'), Modifier.background(Paper.paper3, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), color = Paper.ink1, fontSize = 12.sp, fontFamily = UiMono)
                                line.isBlank() -> Spacer(Modifier.height(7.dp))
                                else -> Text(line, color = Paper.ink0, fontSize = 15.sp, lineHeight = 24.sp)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(phrase(en, "Download", "Скачать"), Modifier.clickable(onClick = onDownload).padding(horizontal = 8.dp, vertical = 12.dp), color = Paper.ink1, fontSize = 14.sp, textDecoration = TextDecoration.Underline)
                Spacer(Modifier.width(18.dp))
                Text(phrase(en, "Copy", "Копировать"), Modifier.clickable(onClick = onCopy).padding(horizontal = 8.dp, vertical = 12.dp), color = Paper.ink1, fontSize = 14.sp, textDecoration = TextDecoration.Underline)
            }
        }
    }
}

@Composable
private fun WorkspaceRow(title: String, meta: String, badge: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = Paper.ink0, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (badge != "›") Surface(color = Paper.paper1, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Paper.line1)) { Text(badge, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Paper.ink1, fontSize = 11.sp, fontFamily = UiMono) }
            Spacer(Modifier.width(10.dp))
            Text(meta, color = Paper.ink2, fontSize = 12.sp, fontFamily = UiMono, maxLines = 1)
            if (badge == "›") Text("  ›", color = Paper.ink2, fontSize = 18.sp)
        }
        HorizontalDivider(color = Paper.line0, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.height(36.dp).clickable(onClick = onClick), color = if (selected) Paper.paper2 else Color.Transparent, shape = RoundedCornerShape(9.dp), shadowElevation = if (selected) 1.dp else 0.dp) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = if (selected) Paper.ink0 else Paper.ink1, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal) }
    }
}

@Composable
private fun SettingsScreen(
    en: Boolean, config: ApiModelConfig, selectedModel: String, selectedMode: String, tools: Map<String, Boolean>, animations: Boolean, compact: Boolean, textScale: Float,
    connectedGitHub: Boolean, connectedDrive: Boolean, onBack: () -> Unit, onLanguage: (Boolean) -> Unit,
    onModel: () -> Unit, onMode: (String) -> Unit, onTool: (String, Boolean) -> Unit, onAnimation: (Boolean) -> Unit,
    onCompact: (Boolean) -> Unit, onTextScale: (Float) -> Unit, onGitHub: () -> Unit, onDrive: () -> Unit, onFolder: () -> Unit,
    onClear: () -> Unit, onExport: () -> Unit, onAboutLink: (String) -> Unit,
    profiles: List<ModelProfile>, activeProfileId: String, onSelectProfile: (ModelProfile) -> Unit,
    skills: List<AgentSkill>, onToggleSkill: (String, Boolean) -> Unit,
    devModeUnlocked: Boolean, customSystemPrompt: String,
    onUnlockDevMode: () -> Unit, onLockDevMode: () -> Unit, onSaveSystemPrompt: (String) -> Unit,
    onOpenSkills: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, phrase(en, "Back", "Назад"), tint = Paper.ink0) }
            Text(phrase(en, "Settings", "Настройки"), Modifier.weight(1f), color = Paper.ink0, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(if (en) "EN" else "RU", Modifier.clickable { onLanguage(!en) }.padding(horizontal = 12.dp, vertical = 10.dp), color = Paper.ink1, fontFamily = UiMono, fontSize = 12.sp)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            PaperSectionLabel(phrase(en, "Model", "Модель"), Modifier.padding(top = 18.dp, bottom = 4.dp))
            SettingRow(phrase(en, "Provider and credentials", "Провайдер и ключ"), "${config.provider.name} · ${selectedModel}", phrase(en, "Configure", "Настроить"), onModel)
            PaperSectionLabel(phrase(en, "Profiles & Presets", "Профили и пресеты"), Modifier.padding(top = 16.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().background(Paper.paper3, RoundedCornerShape(12.dp)).padding(3.dp)) {
                profiles.forEach { profile ->
                    Segment(profile.name, profile.id == activeProfileId, Modifier.weight(1f)) { onSelectProfile(profile) }
                }
            }
            PaperSectionLabel(phrase(en, "Mode", "Режим"), Modifier.padding(top = 20.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().background(Paper.paper3, RoundedCornerShape(12.dp)).padding(3.dp)) {
                listOf("Agent", "GitHub", "Sandbox").forEach { mode -> Segment(phrase(en, mode, when (mode) { "Agent" -> "Агент"; "GitHub" -> "GitHub"; else -> "Песочница" }), selectedMode.equals(mode, true), Modifier.weight(1f)) { onMode(mode) } }
            }
            PaperSectionLabel(phrase(en, "Tools", "Инструменты"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            val toolRows = listOf("web_search" to "Web search", "read_page" to "Read page", "read_file" to "Read files", "write_file" to "Write files", "edit_file" to "Edit files", "list_files" to "List files", "run_command" to "Run commands", "github" to "GitHub tools")
            toolRows.forEach { (key, name) -> SettingSwitchRow(phrase(en, name, when (key) { "web_search" -> "Поиск в интернете"; "read_page" -> "Читать страницы"; "read_file" -> "Читать файлы"; "write_file" -> "Создавать файлы"; "edit_file" -> "Редактировать файлы"; "list_files" -> "Список файлов"; "run_command" -> "Команды"; else -> "Инструменты GitHub" }), "${if (key == "github") 4 else 1} ${phrase(en, "tools", "инстр.")}", tools[key] ?: false) { onTool(key, it) } }
            PaperSectionLabel(phrase(en, "Skills (Claude Code)", "Скилы (Claude Code)"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            SettingRow(
                phrase(en, "Skills & SKILL.md", "Скилы и SKILL.md"),
                "${skills.count { it.enabled }}/${skills.size} ${phrase(en, "enabled", "вкл.")}",
                phrase(en, "Manage", "Открыть"),
                onOpenSkills
            )
            PaperSectionLabel(phrase(en, "Developer", "Разработчик"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            if (!devModeUnlocked) {
                SettingRow(
                    phrase(en, "Developer settings", "Настройки разработчика"),
                    phrase(en, "Locked", "Заблокировано"),
                    phrase(en, "Unlock", "Открыть"),
                    onUnlockDevMode
                )
            } else {
                var promptDraft by remember(customSystemPrompt) { mutableStateOf(customSystemPrompt) }
                SettingRow(
                    phrase(en, "Developer mode", "Режим разработчика"),
                    phrase(en, "Enabled", "Включён"),
                    phrase(en, "Disable", "Выключить"),
                    onLockDevMode
                )
                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    label = { Text(phrase(en, "Custom system prompt", "Свой системный промпт")) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 3,
                    maxLines = 6
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = { onSaveSystemPrompt(promptDraft) },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Paper.line1)
                    ) { Text(phrase(en, "Save prompt", "Сохранить промпт"), color = Paper.ink0) }
                }
            }
            PaperSectionLabel(phrase(en, "Connectors", "Коннекторы"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            SettingRow("GitHub", if (connectedGitHub) phrase(en, "Connected", "Подключён") else phrase(en, "Private repositories", "Приватные репозитории"), phrase(en, if (connectedGitHub) "Disconnect" else "Connect", if (connectedGitHub) "Отключить" else "Подключить"), onGitHub)
            SettingRow("Google Drive", if (connectedDrive) phrase(en, "Connected on this device", "Подключён на устройстве") else phrase(en, "Connect a folder", "Подключить папку"), phrase(en, if (connectedDrive) "Disconnect" else "Connect", if (connectedDrive) "Отключить" else "Подключить"), if (connectedDrive) onDrive else onFolder)
            PaperSectionLabel(phrase(en, "Appearance", "Внешний вид"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            SettingSwitchRow(phrase(en, "Compact spacing", "Компактные отступы"), "", compact, onCompact)
            SettingSwitchRow(phrase(en, "Animations", "Анимации"), phrase(en, "Reduced motion is also respected", "Учитывается системное снижение движения"), animations, onAnimation)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(phrase(en, "Text size", "Размер текста"), Modifier.weight(1f), color = Paper.ink0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("A−", color = Paper.ink2)
                Slider(value = textScale, onValueChange = onTextScale, valueRange = 0.9f..1.2f, steps = 2, modifier = Modifier.width(130.dp), colors = SliderDefaults.colors(thumbColor = Paper.ink0, activeTrackColor = Paper.ink1, inactiveTrackColor = Paper.line0))
                Text("A+", color = Paper.ink2)
            }
            PaperSectionLabel(phrase(en, "Data", "Данные"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            SettingRow(phrase(en, "Conversation history", "История диалогов"), phrase(en, "Clear local history", "Очистить локальную историю"), phrase(en, "Clear", "Очистить"), onClear)
            SettingRow(phrase(en, "Export", "Экспорт"), phrase(en, "Save this conversation as JSON", "Сохранить диалог в JSON"), phrase(en, "Export", "Экспорт"), onExport)
            PaperSectionLabel(phrase(en, "About", "О приложении"), Modifier.padding(top = 20.dp, bottom = 4.dp))
            SettingRow("Vinlagon", phrase(en, "Version 0.1.0 · preview", "Версия 0.1.0 · предпросмотр"), "", {})
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Terms" to "https://example.com/terms", "Privacy" to "https://example.com/privacy", "Cookies" to "https://example.com/cookies").forEachIndexed { index, pair ->
                    if (index > 0) Text(" · ", color = Paper.ink3)
                    Text(phrase(en, pair.first, when (pair.first) { "Terms" -> "Условия"; "Privacy" -> "Конфиденциальность"; else -> "Cookie" }), Modifier.clickable { onAboutLink(pair.second) }, color = Paper.ink1, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, action: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Paper.ink0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotBlank()) Text(detail, color = Paper.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (action.isNotBlank()) Text(action, color = Paper.ink1, fontSize = 13.sp, textDecoration = TextDecoration.Underline)
        }
        HorizontalDivider(color = Paper.line0)
    }
}

@Composable
private fun SettingSwitchRow(title: String, detail: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Paper.ink0, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (detail.isNotBlank()) Text(detail, color = Paper.ink2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = value, onCheckedChange = onChange, modifier = Modifier.height(44.dp), colors = SwitchDefaults.colors(checkedThumbColor = Paper.paper2, checkedTrackColor = Paper.ink0, checkedBorderColor = Paper.line1, uncheckedThumbColor = Paper.paper2, uncheckedTrackColor = Paper.paper3, uncheckedBorderColor = Paper.line1))
        }
        HorizontalDivider(color = Paper.line0)
    }
}

@Composable
private fun ModelConfigDialog(config: ApiModelConfig, en: Boolean, modelLabel: String, onDismiss: () -> Unit, onSave: (ApiModelConfig, String) -> Unit) {
    var provider by remember(config) { mutableStateOf(config.provider) }
    var endpoint by remember(config) { mutableStateOf(config.endpoint) }
    var model by remember(config) { mutableStateOf(config.model) }
    var key by remember(config) { mutableStateOf(config.apiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Paper.paper1,
        shape = RoundedCornerShape(20.dp),
        title = { Text(phrase(en, "Model settings", "Настройки модели"), color = Paper.ink0) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ApiProvider.OPENAI_COMPATIBLE, ApiProvider.ANTHROPIC).forEach { option -> FilterChip(selected = provider == option, onClick = { provider = option }, label = { Text(if (option == ApiProvider.ANTHROPIC) "Anthropic" else "OpenAI-compatible") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Paper.paper3, selectedLabelColor = Paper.ink0)) }
                }
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS endpoint") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text(phrase(en, "Model ID", "ID модели")) }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text(phrase(en, "No demo key is embedded. Credentials are encrypted with Android Keystore.", "Тестовый API-ключ не встроен. Данные шифруются Android Keystore."), color = Paper.ink2, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(config.copy(provider = provider, endpoint = endpoint, model = model, apiKey = key), model.ifBlank { modelLabel }) }) { Text(phrase(en, "Save", "Сохранить"), color = Paper.ink0) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(phrase(en, "Cancel", "Отмена"), color = Paper.ink1) } }
    )
}

private fun FolderNode.folderAt(path: List<String>): FolderNode = path.fold(this) { current, part -> current.folders.firstOrNull { it.name == part } ?: current }
private fun FolderNode.allFilesHere(): List<DemoFile> = files
private fun FolderNode.allFiles(): List<DemoFile> = files + folders.flatMap { it.allFiles() }
private fun formatMB(bytes: Int): String = DecimalFormat("0.0").format(bytes / (1024.0 * 1024.0))

@Composable
private fun SkillsScreen(
    en: Boolean,
    skills: List<AgentSkill>,
    onBack: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onAddSkill: (AgentSkill) -> Unit,
    onDeleteSkill: (String) -> Unit
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var cmdInput by remember { mutableStateOf("/") }
    var titleInput by remember { mutableStateOf("") }
    var descInput by remember { mutableStateOf("") }
    var promptInput by remember { mutableStateOf("") }

    val skillImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val fileName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "custom"
                val firstHeading = text.lines().firstOrNull { it.startsWith("# ") }?.drop(2)?.trim() ?: fileName
                val cmd = "/${fileName.lowercase().replace(Regex("[^a-z0-9_-]"), "")}"
                onAddSkill(
                    AgentSkill(
                        id = "custom-${System.currentTimeMillis()}",
                        command = if (cmd == "/") "/skill" else cmd,
                        titleEn = firstHeading,
                        titleRu = firstHeading,
                        descriptionEn = "Imported from SKILL.md",
                        descriptionRu = "Импортировано из SKILL.md",
                        promptTemplate = text.take(2000) + "\n\n",
                        enabled = true,
                        custom = true
                    )
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, phrase(en, "Back", "Назад"), tint = Paper.ink0) }
            Text(phrase(en, "Skills (Claude Code)", "Скилы (Claude Code)"), Modifier.weight(1f), color = Paper.ink0, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(phrase(en, "Import", "Импорт"), Modifier.clickable { skillImporter.launch(arrayOf("text/*", "text/markdown", "*/*")) }.padding(horizontal = 10.dp, vertical = 8.dp), color = Paper.ink1, fontFamily = UiMono, fontSize = 12.sp)
            Text("+", Modifier.clickable { showAddDialog = true }.padding(horizontal = 10.dp, vertical = 8.dp), color = Paper.ink0, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            skills.forEach { skill ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SettingSwitchRow(
                            "${skill.command} · ${if (en) skill.titleEn else skill.titleRu}",
                            if (en) skill.descriptionEn else skill.descriptionRu,
                            skill.enabled
                        ) { onToggleSkill(skill.id, it) }
                    }
                    if (skill.custom) {
                        Text("×", Modifier.clickable { onDeleteSkill(skill.id) }.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), color = Paper.ink2, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Paper.paper1,
            titleContentColor = Paper.ink0,
            textContentColor = Paper.ink0,
            title = { Text(phrase(en, "New skill", "Новый скилл"), fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cmdInput, { cmdInput = it }, label = { Text(phrase(en, "Command (e.g. /test)", "Команда (напр. /test)")) }, singleLine = true)
                    OutlinedTextField(titleInput, { titleInput = it }, label = { Text(phrase(en, "Title", "Название")) }, singleLine = true)
                    OutlinedTextField(descInput, { descInput = it }, label = { Text(phrase(en, "Description", "Описание")) }, singleLine = true)
                    OutlinedTextField(promptInput, { promptInput = it }, label = { Text(phrase(en, "Prompt template", "Шаблон промпта")) }, minLines = 3, maxLines = 5)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cmd = if (cmdInput.startsWith("/")) cmdInput.trim() else "/${cmdInput.trim()}"
                    if (cmd.length > 1 && titleInput.isNotBlank()) {
                        onAddSkill(
                            AgentSkill(
                                id = "custom-${System.currentTimeMillis()}",
                                command = cmd,
                                titleEn = titleInput.trim(),
                                titleRu = titleInput.trim(),
                                descriptionEn = descInput.ifBlank { "Custom skill" },
                                descriptionRu = descInput.ifBlank { "Пользовательский скилл" },
                                promptTemplate = promptInput.ifBlank { "$titleInput: " },
                                enabled = true,
                                custom = true
                            )
                        )
                        showAddDialog = false
                        cmdInput = "/"; titleInput = ""; descInput = ""; promptInput = ""
                    }
                }) { Text(phrase(en, "Add", "Добавить"), color = Paper.ink0) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(phrase(en, "Cancel", "Отмена"), color = Paper.ink2) }
            }
        )
    }
}
