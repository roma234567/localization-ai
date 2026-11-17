package chat.vinlagon.secret.ui

import androidx.compose.animation.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardBorder = Color(0xFFE5E2DE)
private val CodeSurface = Color(0xFFFAF9F7)

@Composable
fun CollapsibleStepCard(
    title: String,
    kind: StepKind,
    details: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "step-chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("›", Modifier.width(26.dp).rotate(rotation), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 24.sp)
            Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(14.dp))
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            HorizontalDivider(color = CardBorder)
            Text(details, Modifier.fillMaxWidth().padding(start = 43.dp, top = 12.dp, bottom = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

enum class StepKind(val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    WEB(Icons.Outlined.Language), COMMAND(Icons.Outlined.Terminal), FILES(Icons.Outlined.List)
}

@Composable
fun FileArtifactCard(
    fileName: String,
    language: String,
    code: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {}
) {
    val lines = remember(code) { code.lines() }
    val hasOverflow = lines.size > 8
    Column(
        modifier.fillMaxWidth().clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, contentDescription = "Файл", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Text(fileName, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, CardBorder), color = Color.Transparent) {
                Text(language.uppercase(), Modifier.padding(horizontal = 11.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (code.isNotBlank()) {
            Box(Modifier.fillMaxWidth().heightIn(max = 224.dp).background(CodeSurface, RoundedCornerShape(8.dp)).padding(14.dp)) {
                Text(lines.take(8).joinToString("\n"), color = Color(0xFF4C4946), fontSize = 14.sp, lineHeight = 22.sp,
                    fontFamily = FontFamily.Monospace, maxLines = 8, overflow = TextOverflow.Clip)
                if (hasOverflow) {
                    Box(Modifier.fillMaxWidth().height(40.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, CodeSurface))))
                }
            }
        }
    }
}

@Composable
fun ArtifactCard(fileName: String, language: String, code: String, onOpen: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorder),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) { FileArtifactCard(fileName, language, code) }
    }
}

@Composable
fun ExecutionFooter(
    duration: String,
    relativeTime: String,
    modifier: Modifier = Modifier,
    onCopy: () -> Unit = {}
) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Скопировать", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("$duration | $relativeTime", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
