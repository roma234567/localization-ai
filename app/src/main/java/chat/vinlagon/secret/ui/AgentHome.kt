package chat.vinlagon.secret.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.documentfile.provider.DocumentFile
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.vinlagon.secret.agent.ApiChatClient
import chat.vinlagon.secret.agent.ApiModelConfig
import chat.vinlagon.secret.agent.ApiProvider
import chat.vinlagon.secret.agent.BuildRunnerTool
import chat.vinlagon.secret.agent.ChatDatabase
import chat.vinlagon.secret.agent.ChatMessage
import chat.vinlagon.secret.agent.saveTranscript
import chat.vinlagon.secret.agent.ReadFileTool
import chat.vinlagon.secret.agent.PythonExpressionTool
import chat.vinlagon.secret.agent.RunAppTool
import chat.vinlagon.secret.agent.ReadPageTool
import chat.vinlagon.secret.agent.ScheduleReminderTool
import chat.vinlagon.secret.agent.SandboxCommandTool
import chat.vinlagon.secret.agent.ApplyThemeTool
import chat.vinlagon.secret.agent.SearchInWebTool
import chat.vinlagon.secret.agent.SecureModelConfigStore
import chat.vinlagon.secret.agent.ToolExecutionContext
import chat.vinlagon.secret.agent.ToolOrchestrator
import chat.vinlagon.secret.agent.WriteFileTool
import chat.vinlagon.secret.security.ApprovalRequest
import chat.vinlagon.secret.security.PermissionPolicy
import chat.vinlagon.secret.security.SecurityGate
import chat.vinlagon.secret.security.SecurityLevel
import chat.vinlagon.secret.security.ToolType
import chat.vinlagon.secret.workspace.GitHubConfig
import chat.vinlagon.secret.workspace.GitHubTreeWorkspace
import chat.vinlagon.secret.workspace.SafTreeWorkspace
import chat.vinlagon.secret.workspace.SecureGitHubConfigStore
import chat.vinlagon.secret.workspace.SandboxWorkspace
import chat.vinlagon.secret.workspace.WorkspaceManager
import chat.vinlagon.secret.workspace.WorkspaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

private data class ModelOption(val name: String, val category: String, val hint: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHome() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { SecureModelConfigStore(context) }
    val githubStore = remember { SecureGitHubConfigStore(context) }
    var config by remember { mutableStateOf(runCatching { store.load() }.getOrDefault(ApiModelConfig())) }
    var githubConfig by remember { mutableStateOf(runCatching { githubStore.load() }.getOrDefault(GitHubConfig())) }
    var showGitHubSettings by remember { mutableStateOf(false) }
    var githubActive by remember { mutableStateOf(context.getSharedPreferences("workspace", 0).getBoolean("github_active", false)) }
    val options = listOf(
        ModelOption("OpenAI-compatible API", "API CONFIGURATIONS", "OpenAI, OpenRouter и совместимые endpoints"),
        ModelOption("Anthropic API", "API CONFIGURATIONS", "Messages API с потоковой передачей SSE"),
        ModelOption("Выбрать локальный файл (.gguf)", "LOCAL .GGUF FILES", "Выберите модель через Android SAF; inference runtime готовится")
    )
    var localModelName by remember { mutableStateOf(context.getSharedPreferences("models", 0).getString("gguf_name", null)) }
    var localModelUri by remember { mutableStateOf(context.getSharedPreferences("models", 0).getString("gguf_uri", null)) }
    var selected by remember { mutableStateOf(localModelName?.let { "Local: $it" } ?: if (config.provider == ApiProvider.ANTHROPIC) options[1].name else options[0].name) }
    var showModels by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lastDuration by remember { mutableStateOf("—") }
    var lastFinishedAt by remember { mutableLongStateOf(0L) }
    var showSecurityMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var historyLoaded by remember { mutableStateOf(false) }
    val database = remember { ChatDatabase.get(context) }
    LaunchedEffect(database) {
        runCatching { database.chatLog().loadAll() }.onSuccess { rows ->
            if (messages.isEmpty()) messages.addAll(rows.map { ChatMessage(it.role, it.content) })
        }.onFailure { error = "История недоступна: ${it.message}" }
        historyLoaded = true
    }
    val scope = rememberCoroutineScope()
    val api = remember { ApiChatClient() }
    DisposableEffect(api) { onDispose { api.close() } }

    val sandbox = remember(context) { File(context.filesDir, "sandbox").apply { mkdirs() } }
    var workspaceUri by remember { mutableStateOf(context.getSharedPreferences("workspace", 0).getString("tree_uri", null)) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.getSharedPreferences("workspace", 0).edit().putString("tree_uri", uri.toString()).apply()
            workspaceUri = uri.toString()
        }.onFailure { error = it.message ?: "Не удалось получить доступ к папке" }
    }
    LaunchedEffect(localModelUri) {
        val uri = localModelUri?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
        if (uri != null && context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) {
            localModelName = null
            localModelUri = null
            context.getSharedPreferences("models", 0).edit().remove("gguf_uri").remove("gguf_name").apply()
            selected = if (config.provider == ApiProvider.ANTHROPIC) options[1].name else options[0].name
        }
    }
    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment.orEmpty()
            require(displayName.endsWith(".gguf", ignoreCase = true)) { "Выберите файл с расширением .gguf" }
            localModelUri = uri.toString()
            localModelName = displayName
            context.getSharedPreferences("models", 0).edit().putString("gguf_uri", localModelUri).putString("gguf_name", displayName).apply()
            selected = "Local: $displayName"
            error = "Файл выбран, но llama.cpp runtime ещё не включён в эту сборку"
        }.onFailure { error = it.message ?: "Не удалось открыть GGUF" }
    }
    val workspace: WorkspaceManager = remember(sandbox, workspaceUri, githubActive, githubConfig) {
        when {
            githubActive && githubConfig.token.isNotBlank() && githubConfig.repository.isNotBlank() -> GitHubTreeWorkspace(githubConfig.token, githubConfig.repository, githubConfig.branch)
            !githubActive && workspaceUri != null -> SafTreeWorkspace(context, android.net.Uri.parse(workspaceUri!!))
            else -> SandboxWorkspace(sandbox)
        }
    }
    val gate = remember {
        SecurityGate(SecurityLevel.CUSTOM, mapOf(
            ToolType.WEB_READ to PermissionPolicy.ALLOW,
            ToolType.FILE_READ to PermissionPolicy.ALLOW,
            ToolType.FILE_WRITE to PermissionPolicy.REQUIRE_APPROVAL,
            ToolType.COMMAND to PermissionPolicy.ALLOW
        ))
    }
    val agentTools = remember(context) { listOf(ReadFileTool(), WriteFileTool(), SearchInWebTool(), ReadPageTool(), SandboxCommandTool(), RunAppTool(), ScheduleReminderTool(), BuildRunnerTool(), ApplyThemeTool(), PythonExpressionTool()) }
    val orchestrator = remember(workspace, gate, agentTools) {
        ToolOrchestrator(agentTools, ToolExecutionContext(workspace, gate, context.applicationContext))
    }
    LaunchedEffect(workspace) {
        if (workspace is SafTreeWorkspace && !workspace.requestAccess()) {
            workspaceUri = null
            context.getSharedPreferences("workspace", 0).edit().remove("tree_uri").apply()
            error = "Доступ SAF отозван; возвращена sandbox"
        }
    }
    var pendingApproval by remember { mutableStateOf<ApprovalRequest?>(null) }
    var filesVersion by remember { mutableIntStateOf(0) }
    val workspaceFiles = remember { mutableStateListOf<String>() }
    val workspacePreviews = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(workspace, filesVersion) {
        val discovered = runCatching {
            withContext(Dispatchers.IO) {
                when (workspace) {
                    is SandboxWorkspace -> sandbox.walkTopDown().filter { it.isFile }.map { it.relativeTo(sandbox).path.replace(File.separatorChar, '/') }.toList()
                    is GitHubTreeWorkspace -> workspace.listTree("").filterNot { it.endsWith("/") }
                    is SafTreeWorkspace -> {
                        val result = mutableListOf<String>()
                        val uri = (workspace.mode as WorkspaceMode.AgentTask).targetDirUri
                        val root = DocumentFile.fromTreeUri(context, uri)
                        fun walk(dir: DocumentFile, prefix: String, depth: Int) {
                            if (depth > 5 || result.size >= 300) return
                            dir.listFiles().forEach { child ->
                                val path = if (prefix.isEmpty()) child.name.orEmpty() else "$prefix/${child.name.orEmpty()}"
                                if (child.isDirectory) walk(child, path, depth + 1) else if (child.isFile) result += path
                            }
                        }
                        root?.let { walk(it, "", 0) }
                        result
                    }
                    else -> emptyList()
                }
            }
        }.getOrElse { error = it.message ?: "Не удалось получить список файлов"; emptyList() }
        workspaceFiles.clear()
        workspaceFiles.addAll(discovered.sorted())
        workspacePreviews.clear()
        discovered.take(12).forEach { path ->
            val preview = runCatching { withContext(Dispatchers.IO) { workspace.read(path).toString(Charsets.UTF_8).lineSequence().take(8).joinToString("\n") } }.getOrDefault("")
            workspacePreviews[path] = preview
        }
    }
    LaunchedEffect(gate) { gate.observeApprovals { pendingApproval = it } }
    LaunchedEffect(sandbox) {
        if (sandbox.listFiles().isNullOrEmpty()) {
            File(sandbox, "tracker.py").writeText("from pathlib import Path\n\ndef main():\n    print('Hello from vinlagonChat sandbox')\n\nif __name__ == '__main__':\n    main()\n")
            File(sandbox, "install.bat").writeText("@echo off\nREM Sample file preview; not executed by Android\necho vinlagonChat workspace\n")
        }
        filesVersion++
    }
    var editorFile by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("vinlagonChat", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (githubActive) "AI агент · GitHub ${githubConfig.repository}" else if (workspaceUri == null) "AI агент · sandbox" else "AI агент · SAF-папка", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                TextButton(onClick = {
                    if (githubConfig.token.isBlank()) showGitHubSettings = true else {
                        githubActive = !githubActive
                        context.getSharedPreferences("workspace", 0).edit().putBoolean("github_active", githubActive).apply()
                    }
                }) { Text(if (githubActive) "GitHub ✓" else "GitHub") }
                TextButton(onClick = {
                    githubActive = false
                    context.getSharedPreferences("workspace", 0).edit().putBoolean("github_active", false).apply()
                    treePicker.launch(null)
                }) { Text("Папка") }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, contentDescription = "Настройки API") }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) {
                    item {
                        Text("Начните диалог", Modifier.padding(top = 24.dp, bottom = 10.dp), fontSize = 25.sp, fontWeight = FontWeight.Medium)
                        Text("Ответы поступают потоково. Ключ API хранится в Android Keystore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        CollapsibleStepCard("Searched the web", StepKind.WEB, "DuckDuckGo Instant Answer + HTTPS page extraction are available as model tools.")
                        CollapsibleStepCard("Ran commands 0", StepKind.COMMAND, "Allowlist: pwd, ls, cat in app sandbox only. No shell or root process is started.")
                        CollapsibleStepCard("Edited files 0", StepKind.FILES, "Файлы ограничены внутренней sandbox-папкой приложения")
                        Spacer(Modifier.height(12.dp))
                    }
                } else {
                    itemsIndexed(messages) { _, message ->
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = if (message.role == "user") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(when (message.role) { "user" -> "Вы"; "tool" -> "Результат инструмента"; else -> "vinlagonChat" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(5.dp))
                                Text(message.content.ifEmpty { if (busy) "…" else "" })
                            }
                        }
                    }
                }
                if (!busy && messages.isNotEmpty() && lastFinishedAt > 0L) {
                    item {
                        ExecutionFooter(lastDuration, relativeAge(lastFinishedAt), onCopy = {
                            clipboard.setText(AnnotatedString(messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()))
                        })
                    }
                }
                item {
                    workspaceFiles.forEach { path ->
                        val language = path.substringAfterLast('.', "PLAIN").uppercase()
                        ArtifactCard(path.substringAfterLast('/'), language, workspacePreviews[path].orEmpty()) {
                            scope.launch {
                                runCatching { workspace.read(path).toString(Charsets.UTF_8) }.onSuccess { content ->
                                    editorFile = path
                                    editorText = content
                                }.onFailure { error = it.message ?: "Не удалось прочитать файл" }
                            }
                        }
                    }
                    if (error != null) {
                        Text(error.orEmpty(), Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(onClick = { showModels = true }, label = { Text(selected, maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.Memory, null, Modifier.size(18.dp)) })
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { showSecurityMenu = true }) { Text(if (busy) "Генерация…" else gate.level.name) }
                            DropdownMenu(expanded = showSecurityMenu, onDismissRequest = { showSecurityMenu = false }) {
                                SecurityLevel.values().forEach { level ->
                                    DropdownMenuItem(text = { Text(level.name) }, onClick = { gate.level = level; showSecurityMenu = false })
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Сообщение агенту") }, maxLines = 4, shape = RoundedCornerShape(14.dp), enabled = !busy && historyLoaded && localModelName == null)
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = {
                            val text = prompt.trim()
                            if (text.isNotEmpty() && !busy) {
                                prompt = ""
                                error = null
                                messages.add(ChatMessage("user", text))
                                messages.add(ChatMessage("assistant", ""))
                                val startedAt = System.currentTimeMillis()
                                busy = true
                                scope.launch {
                                    try {
                                        var history = listOf(ChatMessage("system", "Ты полезный AI-ассистент. Используй инструменты только по необходимости; изменяй файлы только в активном workspace и не заявляй о невыполненной работе. Команды разрешены только внутри sandbox allowlist.")) + messages.dropLast(1)
                                        for (round in 0..3) {
                                            val reply = api.stream(
                                                config,
                                                history,
                                                agentTools
                                            ) { chunk ->
                                                val last = messages.lastIndex
                                                if (last >= 0 && messages[last].role == "assistant") {
                                                    val current = messages[last].content
                                                    val remaining = (60_000 - current.length).coerceAtLeast(0)
                                                    if (remaining > 0) messages[last] = messages[last].copy(content = current + chunk.take(remaining))
                                                }
                                            }
                                            if (reply.toolCalls.isEmpty()) break
                                            val assistantIndex = messages.lastIndex
                                            if (assistantIndex >= 0 && messages[assistantIndex].role == "assistant") {
                                                messages[assistantIndex] = messages[assistantIndex].copy(toolCalls = reply.toolCalls)
                                            }
                                            history = history + ChatMessage("assistant", messages.getOrNull(assistantIndex)?.content.orEmpty(), toolCalls = reply.toolCalls)
                                            val results = orchestrator.dispatch(reply.toolCalls)
                                            filesVersion++
                                            results.forEach { toolReply ->
                                                val resultMessage = ChatMessage("tool", toolReply.result.content, toolCallId = toolReply.callId)
                                                messages.add(resultMessage)
                                                history = history + resultMessage
                                            }
                                            if (round < 3) {
                                                messages.add(ChatMessage("assistant", ""))
                                            }
                                        }
                                        if (messages.lastOrNull()?.role == "tool") {
                                            messages.add(ChatMessage("assistant", "Достигнут предел последовательных вызовов инструментов. Продолжите новым сообщением."))
                                        }
                                    } catch (t: Throwable) {
                                        error = t.message ?: "Ошибка запроса"
                                        if (messages.isNotEmpty() && messages.last().role == "assistant" && messages.last().content.isBlank()) messages.removeAt(messages.lastIndex)
                                    } finally {
                                        runCatching { database.saveTranscript(messages.toList()) }
                                        val finished = System.currentTimeMillis()
                                        val elapsed = ((finished - startedAt).coerceAtLeast(0) / 1000)
                                        lastDuration = "${elapsed / 60}m ${elapsed % 60}s"
                                        lastFinishedAt = finished
                                        busy = false
                                    }
                                }
                            }
                        }, enabled = prompt.isNotBlank() && !busy && historyLoaded && localModelName == null) {
                            Icon(Icons.Outlined.ArrowUpward, contentDescription = "Отправить")
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }) {
            Text("Выберите модель", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            options.groupBy { it.category }.forEach { (category, entries) ->
                Text(category, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option.name) }, supportingContent = { Text(option.hint) },
                        leadingContent = { Icon(if (option.category.startsWith("LOCAL")) Icons.Outlined.Memory else Icons.Outlined.Add, contentDescription = null) },
                        trailingContent = { RadioButton(selected == option.name, onClick = null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).clickable {
                            if (option.category.startsWith("LOCAL")) {
                                showModels = false
                                ggufPicker.launch(arrayOf("application/octet-stream", "*/*"))
                            } else {
                                localModelName = null
                                localModelUri = null
                                context.getSharedPreferences("models", 0).edit().remove("gguf_uri").remove("gguf_name").apply()
                                selected = option.name
                                if (option.name == options[1].name) config = config.copy(provider = ApiProvider.ANTHROPIC, endpoint = "https://api.anthropic.com/v1/messages", model = "claude-3-5-sonnet-latest")
                                if (option.name == options[0].name) config = config.copy(provider = ApiProvider.OPENAI_COMPATIBLE, endpoint = "https://api.openai.com/v1/chat/completions", model = "gpt-4o-mini")
                                showModels = false
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showGitHubSettings) {
        GitHubSettingsDialog(githubConfig, onDismiss = { showGitHubSettings = false }, onSave = { next ->
            runCatching { githubStore.save(next) }.onSuccess {
                githubConfig = next
                githubActive = true
                context.getSharedPreferences("workspace", 0).edit().putBoolean("github_active", true).apply()
                showGitHubSettings = false
                error = null
            }.onFailure { error = it.message ?: "GitHub config invalid" }
        })
    }

    if (showSettings) {
        ModelSettingsDialog(config, onDismiss = { showSettings = false }, onSave = { next ->
            runCatching { store.save(next) }.onSuccess {
                config = next
                selected = if (next.provider == ApiProvider.ANTHROPIC) options[1].name else options[0].name
                error = null
                showSettings = false
            }.onFailure { error = it.message ?: "Не удалось сохранить настройки" }
        })
    }

    pendingApproval?.let { approval ->
        AlertDialog(
            onDismissRequest = {
                scope.launch { gate.resolveApproval(approval.id, false) }
                pendingApproval = null
            },
            title = { Text("Подтвердить действие?") },
            text = { Text("${approval.tool}\n${approval.summary}") },
            confirmButton = { TextButton(onClick = {
                pendingApproval = null
                scope.launch { gate.resolveApproval(approval.id, true) }
            }) { Text("Разрешить") } },
            dismissButton = { TextButton(onClick = {
                pendingApproval = null
                scope.launch { gate.resolveApproval(approval.id, false) }
            }) { Text("Отклонить") } }
        )
    }

    if (editorFile != null) {
        AlertDialog(
            onDismissRequest = { editorFile = null },
            title = { Text(editorFile ?: "Файл") },
            text = { OutlinedTextField(editorText, { editorText = it }, Modifier.fillMaxWidth().heightIn(min = 220.dp), minLines = 8, maxLines = 18, textStyle = LocalTextStyle.current.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) },
            confirmButton = { TextButton(onClick = {
                val path = editorFile
                if (path != null) scope.launch {
                    runCatching { workspace.write(path, editorText.toByteArray(Charsets.UTF_8)); filesVersion++ }
                        .onFailure { error = it.message ?: "Не удалось сохранить файл" }
                }
                editorFile = null
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { editorFile = null }) { Text("Закрыть") } }
        )
    }
}

@Composable
private fun ModelSettingsDialog(config: ApiModelConfig, onDismiss: () -> Unit, onSave: (ApiModelConfig) -> Unit) {
    var provider by remember(config) { mutableStateOf(config.provider) }
    var endpoint by remember(config) { mutableStateOf(config.endpoint) }
    var model by remember(config) { mutableStateOf(config.model) }
    var key by remember(config) { mutableStateOf(config.apiKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки модели") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    FilterChip(provider == ApiProvider.OPENAI_COMPATIBLE, { provider = ApiProvider.OPENAI_COMPATIBLE }, label = { Text("OpenAI / Router") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(provider == ApiProvider.ANTHROPIC, { provider = ApiProvider.ANTHROPIC }, label = { Text("Anthropic") })
                }
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("HTTPS endpoint") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("API key") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("Ключ шифруется Android Keystore на устройстве.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(config.copy(provider = provider, endpoint = endpoint, model = model, apiKey = key)) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun GitHubSettingsDialog(config: GitHubConfig, onDismiss: () -> Unit, onSave: (GitHubConfig) -> Unit) {
    var repository by remember(config) { mutableStateOf(config.repository) }
    var branch by remember(config) { mutableStateOf(config.branch) }
    var token by remember(config) { mutableStateOf(config.token) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(repository, { repository = it }, label = { Text("owner/repository") }, singleLine = true)
                OutlinedTextField(branch, { branch = it }, label = { Text("Branch") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("Fine-grained token") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("Токен хранится зашифрованным на устройстве. Нужны Contents: read/write для выбранного репозитория.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(GitHubConfig(repository, branch, token)) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun relativeAge(timestamp: Long): String {
    val seconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 1000)
    return when {
        seconds < 60 -> "только что"
        seconds < 3600 -> "${seconds / 60} мин назад"
        seconds < 86400 -> "${seconds / 3600} ч назад"
        else -> "${seconds / 86400} д назад"
    }
}
