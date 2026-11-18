package chat.vinlagon.secret.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.vinlagon.secret.theme.Paper

@Composable
fun MarkdownText(
    markdown: String,
    textScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val lines = markdown.lines()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Code block
            if (trimmed.startsWith("```")) {
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                i++ // skip closing ```
                Surface(
                    color = Paper.paper3,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Paper.line0),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = codeLines.joinToString("\n"),
                        color = Paper.ink0,
                        fontSize = (12.5f * textScale).sp,
                        lineHeight = (19f * textScale).sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
                    )
                }
                continue
            }

            // Markdown table
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                val tableRows = mutableListOf<List<String>>()
                while (i < lines.size) {
                    val rowTrimmed = lines[i].trim()
                    if (!rowTrimmed.startsWith("|") || !rowTrimmed.endsWith("|")) break
                    val isSeparator = rowTrimmed.removePrefix("|").removeSuffix("|").split("|")
                        .all { cell -> cell.trim().all { it == '-' || it == ':' } && cell.trim().isNotEmpty() }
                    if (!isSeparator) {
                        val cells = rowTrimmed.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                        tableRows.add(cells)
                    }
                    i++
                }
                if (tableRows.isNotEmpty()) {
                    Surface(
                        color = Paper.paper2,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Paper.line0),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                            tableRows.forEachIndexed { rIdx, row ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    row.forEach { cell ->
                                        Text(
                                            text = parseInlineMarkdown(cell),
                                            color = Paper.ink0,
                                            fontSize = (13.5f * textScale).sp,
                                            fontWeight = if (rIdx == 0) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.width(130.dp).padding(horizontal = 6.dp)
                                        )
                                    }
                                }
                                if (rIdx == 0 && tableRows.size > 1) {
                                    HorizontalDivider(color = Paper.line0, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }
                continue
            }

            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(2.dp))
                trimmed.startsWith("### ") -> Text(
                    text = parseInlineMarkdown(trimmed.drop(4)),
                    color = Paper.ink0,
                    fontSize = (16f * textScale).sp,
                    lineHeight = (22f * textScale).sp,
                    fontWeight = FontWeight.SemiBold
                )
                trimmed.startsWith("## ") -> Text(
                    text = parseInlineMarkdown(trimmed.drop(3)),
                    color = Paper.ink0,
                    fontSize = (18f * textScale).sp,
                    lineHeight = (25f * textScale).sp,
                    fontWeight = FontWeight.SemiBold
                )
                trimmed.startsWith("# ") -> Text(
                    text = parseInlineMarkdown(trimmed.drop(2)),
                    color = Paper.ink0,
                    fontSize = (21f * textScale).sp,
                    lineHeight = (28f * textScale).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row(Modifier.fillMaxWidth()) {
                    Text("•", color = Paper.ink2, fontSize = (15f * textScale).sp, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        text = parseInlineMarkdown(trimmed.drop(2)),
                        color = Paper.ink0,
                        fontSize = (15f * textScale).sp,
                        lineHeight = (23f * textScale).sp
                    )
                }
                Regex("^\\d+\\.\\s+").containsMatchIn(trimmed) -> {
                    val num = trimmed.substringBefore(".")
                    val rest = trimmed.substringAfter(". ")
                    Row(Modifier.fillMaxWidth()) {
                        Text("$num.", color = Paper.ink2, fontSize = (15f * textScale).sp, modifier = Modifier.padding(end = 8.dp))
                        Text(
                            text = parseInlineMarkdown(rest),
                            color = Paper.ink0,
                            fontSize = (15f * textScale).sp,
                            lineHeight = (23f * textScale).sp
                        )
                    }
                }
                trimmed.startsWith("> ") -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Box(Modifier.width(2.dp).height(20.dp).background(Paper.line1))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = parseInlineMarkdown(trimmed.drop(2)),
                        color = Paper.ink1,
                        fontSize = (14.5f * textScale).sp,
                        lineHeight = (22f * textScale).sp
                    )
                }
                trimmed == "---" || trimmed == "***" -> HorizontalDivider(color = Paper.line0, thickness = 1.dp)
                else -> Text(
                    text = parseInlineMarkdown(line),
                    color = Paper.ink0,
                    fontSize = (15f * textScale).sp,
                    lineHeight = (23f * textScale).sp
                )
            }
            i++
        }
    }
}

private fun parseInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var idx = 0
    while (idx < text.length) {
        when {
            text.startsWith("**", idx) -> {
                val end = text.indexOf("**", idx + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Paper.ink0)) {
                        append(text.substring(idx + 2, end))
                    }
                    idx = end + 2
                } else {
                    append("**")
                    idx += 2
                }
            }
            text.startsWith("`", idx) -> {
                val end = text.indexOf("`", idx + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Paper.paper3, color = Paper.ink0)) {
                        append(" ${text.substring(idx + 1, end)} ")
                    }
                    idx = end + 1
                } else {
                    append("`")
                    idx += 1
                }
            }
            text.startsWith("*", idx) -> {
                val end = text.indexOf("*", idx + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Paper.ink0)) {
                        append(text.substring(idx + 1, end))
                    }
                    idx = end + 1
                } else {
                    append("*")
                    idx += 1
                }
            }
            else -> {
                append(text[idx])
                idx++
            }
        }
    }
}
