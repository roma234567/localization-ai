package chat.vinlagon.secret.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.vinlagon.secret.theme.Paper

private val Mono = FontFamily.Monospace
private val Serif = FontFamily.Serif

@Composable
fun ToolGroup(event: ToolEvent, reducedMotion: Boolean, onChildAction: (String) -> Unit = {}) {
    var expanded by remember(event.title) { mutableStateOf(false) }
    val rotation by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, animationSpec = tween(if (reducedMotion) 1 else 220), label = "tool-chevron")
    Column(Modifier.fillMaxWidth().animateContentSize(tween(if (reducedMotion) 1 else 220))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when {
                event.title.contains("web", true) || event.title.contains("search", true) -> Icons.Outlined.Language
                event.title.contains("list", true) || event.title.contains("file", true) -> Icons.Outlined.List
                event.title.contains("command", true) -> Icons.Outlined.Terminal
                else -> Icons.Outlined.Description
            }
            Text("›", color = Paper.ink3, fontSize = 20.sp, modifier = Modifier.width(20.dp))
            Icon(icon, contentDescription = null, tint = Paper.ink1, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(event.title, color = Paper.ink1, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (event.count.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(event.count, color = Paper.ink2, fontSize = 12.sp, fontFamily = Mono)
            }
            Spacer(Modifier.weight(1f))
            when {
                event.running -> PulseLabel("…", !reducedMotion)
                event.failed -> Text("· сбой", color = Paper.ink2, fontSize = 12.sp)
                else -> Text("⌄", Modifier.rotate(rotation), color = Paper.ink2, fontSize = 16.sp)
            }
        }
        AnimatedVisibility(expanded, enter = if (reducedMotion) fadeIn(tween(1)) else fadeIn(tween(160)) + expandVertically(tween(220)), exit = if (reducedMotion) fadeOut(tween(1)) else fadeOut(tween(120)) + shrinkVertically(tween(180))) {
            Column(Modifier.padding(start = 24.dp, bottom = 6.dp)) {
                event.children.forEach { child ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(child.prefix.ifBlank { "›" }, color = Paper.ink3, fontSize = 13.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(child.title, color = Paper.ink1, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (child.chip.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(6.dp), color = Paper.paper3) {
                                Text(child.chip, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Paper.ink1, fontSize = 12.sp, fontFamily = Mono, maxLines = 1)
                            }
                        }
                        if (child.meta.isNotBlank()) {
                            Spacer(Modifier.width(7.dp))
                            Text(child.meta, color = Paper.ink2, fontSize = 12.sp, fontFamily = Mono, maxLines = 1)
                        }
                        if (child.action.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(child.action, Modifier.clickable { onChildAction(child.action) }, color = Paper.ink1, fontSize = 13.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                        }
                    }
                    if (child.detail.isNotBlank()) {
                        Text(child.detail, Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp), color = Paper.ink2, fontSize = 12.sp, lineHeight = 18.sp, fontFamily = Mono, maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun PulseLabel(text: String, animate: Boolean = true) {
    if (!animate) {
        Text(text, color = Paper.ink2, fontSize = 13.sp, fontFamily = Mono)
    } else {
        val transition = rememberInfiniteTransition(label = "working-pulse")
        val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "working-alpha")
        Text(text, Modifier.alpha(alpha), color = Paper.ink2, fontSize = 13.sp, fontFamily = Mono)
    }
}

@Composable
fun FileCard(fileName: String, body: String, reducedMotion: Boolean, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).animateContentSize(tween(if (reducedMotion) 1 else 320)),
        shape = RoundedCornerShape(16.dp),
        color = Paper.paper2,
        border = BorderStroke(1.dp, Paper.line1),
        shadowElevation = 2.dp
    ) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fileName, Modifier.weight(1f), color = Paper.ink0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(6.dp), color = Paper.paper2, border = BorderStroke(1.dp, Paper.line1)) {
                    Text(fileName.substringAfterLast('.', "FILE").uppercase(), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Paper.ink1, fontSize = 11.sp, fontFamily = Mono)
                }
            }
            HorizontalDivider(color = Paper.line0)
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                MarkdownText(body, textScale = 0.94f)
            }
        }
    }
}

@Composable
fun AssistantActions(vote: Int, onCopy: () -> Unit, onRetry: () -> Unit, onVote: (Int) -> Unit, en: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (en) "Copy" else "Копировать", Modifier.clickable(onClick = onCopy).padding(end = 16.dp), color = Paper.ink2, fontSize = 13.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        Text(if (en) "Retry" else "Повторить", Modifier.clickable(onClick = onRetry).padding(end = 16.dp), color = Paper.ink2, fontSize = 13.sp, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.Check,
            contentDescription = if (en) "Like" else "Нравится",
            tint = if (vote == 1) Paper.ink0 else Paper.ink2,
            modifier = Modifier.clickable { onVote(if (vote == 1) 0 else 1) }.padding(horizontal = 8.dp, vertical = 4.dp).size(16.dp)
        )
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.Close,
            contentDescription = if (en) "Dislike" else "Не нравится",
            tint = if (vote == -1) Paper.ink0 else Paper.ink2,
            modifier = Modifier.clickable { onVote(if (vote == -1) 0 else -1) }.padding(horizontal = 8.dp, vertical = 4.dp).size(16.dp)
        )
    }
}

@Composable
fun Composer(
    value: String,
    onValue: (String) -> Unit,
    busy: Boolean,
    model: String,
    hint: String,
    caption: String,
    canSend: Boolean,
    onModel: () -> Unit,
    onAttach: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    en: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(20.dp), color = Paper.paper1, border = BorderStroke(1.dp, Paper.line0)) {
            Column(Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = onValue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 200.dp),
                    placeholder = { Text(hint, color = Paper.ink2, fontSize = 16.sp) },
                    textStyle = LocalTextStyle.current.copy(color = Paper.ink0, fontSize = 16.sp, lineHeight = 23.sp),
                    maxLines = 7,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        cursorColor = Paper.ink0
                    )
                )
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+", Modifier.clickable(onClick = onAttach).padding(horizontal = 10.dp, vertical = 8.dp), color = Paper.ink1, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("$model ⌄", Modifier.weight(1f).clickable(onClick = onModel).padding(horizontal = 4.dp, vertical = 10.dp), color = Paper.ink2, fontSize = 12.5.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    if (busy) {
                        OutlinedButton(onClick = onStop, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Paper.line1), colors = ButtonDefaults.outlinedButtonColors(containerColor = Paper.paper1, contentColor = Paper.ink0)) { Text("■", fontSize = 14.sp) }
                    } else {
                        androidx.compose.material3.IconButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier.size(40.dp).background(if (canSend) Paper.ink0 else Paper.paper3, RoundedCornerShape(12.dp))
                        ) { Text("→", color = if (canSend) Paper.paper2 else Paper.ink3, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
        Text(caption, Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 4.dp), color = Paper.ink3, fontSize = 11.sp)
    }
}

@Composable
fun UserBubble(text: String, attachments: List<String> = emptyList(), modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = Paper.paper3, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (attachments.isNotEmpty()) {
                    Text(attachments.joinToString(" · "), color = Paper.ink2, fontSize = 11.5.sp, fontFamily = Mono, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(text, color = Paper.ink0, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
fun PaperSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = Paper.ink2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
}
