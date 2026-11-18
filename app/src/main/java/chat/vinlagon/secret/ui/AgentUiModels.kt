package chat.vinlagon.secret.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshots.SnapshotStateList

@Immutable
data class ToolChild(
    val prefix: String,
    val title: String,
    val chip: String = "",
    val meta: String = "",
    val action: String = "",
    val detail: String = ""
)

@Immutable
data class ToolEvent(
    val title: String,
    val count: String = "",
    val children: List<ToolChild> = emptyList(),
    val running: Boolean = false,
    val failed: Boolean = false
)

@Immutable
data class UiMessage(
    val role: String,
    val text: String,
    val tools: List<ToolEvent> = emptyList(),
    val fileName: String? = null,
    val fileBody: String = "",
    val stopped: Boolean = false,
    val vote: Int = 0
)

data class HistoryEntry(val id: String, val title: String, val group: String, val messages: List<UiMessage> = emptyList())

data class DemoFile(val path: String, val content: String, val size: Int = content.toByteArray().size) {
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "FILE").uppercase()
}

data class FolderNode(val name: String, val files: List<DemoFile> = emptyList(), val folders: List<FolderNode> = emptyList())
