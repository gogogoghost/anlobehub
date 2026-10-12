package cc.jaxy.anlobehub.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import coil3.compose.AsyncImage
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import cc.jaxy.anlobehub.core.data.chat.ChatMessage
import cc.jaxy.anlobehub.core.designsystem.component.InitialAvatar
import cc.jaxy.anlobehub.core.designsystem.component.MarkdownText
import cc.jaxy.anlobehub.core.designsystem.component.relativeTimeText
import cc.jaxy.anlobehub.core.designsystem.theme.spacing

/**
 * Assistant message: borderless, mirroring web mobile. Thinking trace and
 * tool calls fold into collapsible cards; body markdown renders directly.
 */
@Composable
fun AssistantMessageRow(
    message: ChatMessage,
    agentName: String?,
    agentAvatar: String? = null,
    isStreaming: Boolean = false,
    streamingText: String? = null,
    streamingReasoning: String? = null,
    streamingReasoningSecs: Double? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.spacing
    val body = streamingText ?: message.content
    val reasoning = streamingReasoning ?: message.reasoning
    val reasoningSecs = streamingReasoningSecs ?: message.reasoningDuration
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.s)
            // No animateContentSize while streaming: per-token layout
            // animation reads as flicker at 12fps updates.
            .then(if (isStreaming) Modifier else Modifier.animateContentSize()),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            InitialAvatar(
                name = agentName?.takeIf { it.isNotBlank() } ?: "AI",
                avatarUrl = agentAvatar?.takeIf { it.isNotBlank() },
                size = 28.dp,
            )
            Text(
                text = agentName?.takeIf { it.isNotBlank() }
                    ?: message.model?.takeIf { it.isNotBlank() }
                    ?: "AI",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val timeLabel = relativeTimeText(message.createdAt)
            if (timeLabel.isNotBlank()) {
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!reasoning.isNullOrBlank() || (isStreaming && body.isBlank())) {
            ThinkingCard(
                content = reasoning.orEmpty(),
                thinking = isStreaming && body.isBlank(),
                durationSecs = reasoningSecs,
            )
        }
        if (body.isNotBlank()) {
            var linkPreview by remember { mutableStateOf<ChatCitation?>(null) }
            val citations = remember(body) { parseCitations(body) }
            val citationByUrl = remember(citations) { citations.associateBy { it.url } }
            // Intercept all markdown link taps: show preview sheet first.
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides remember {
                    object : androidx.compose.ui.platform.UriHandler {
                        override fun openUri(uri: String) {
                            val hit = citationByUrl[uri]
                                ?: ChatCitation(index = "", title = uri, url = uri)
                            linkPreview = hit
                        }
                    }
                },
            ) {
                MarkdownText(markdown = body, streaming = isStreaming)
            }
            if (isStreaming) StreamingCursor()
            if (citations.isNotEmpty() && !isStreaming) {
                CitationRow(
                    citations = citations,
                    onOpen = { linkPreview = it },
                )
            }
            linkPreview?.let { citation ->
                LinkPreviewSheet(
                    citation = citation,
                    onDismiss = { linkPreview = null },
                )
            }
        } else if (!isStreaming) {
            Text(
                text = stringResource(R.string.chat_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (reasoning.isNullOrBlank()) {
            // Reasoning-only phase with nothing yet: show cursor alone.
            StreamingCursor()
        }
        if (!message.error.isNullOrBlank()) {
            Text(
                text = message.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!isStreaming && body.isNotBlank()) {
            MessageActions(text = body)
        }
    }
}

/** Collapsible deep-thinking trace. Collapsed by default (web parity). */
@Composable
private fun ThinkingCard(
    content: String,
    thinking: Boolean,
    durationSecs: Double? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.m, vertical = spacing.s)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                if (thinking) {
                    ThinkingDots()
                } else {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = if (thinking) {
                        stringResource(R.string.chat_thinking)
                    } else if (durationSecs != null) {
                        stringResource(R.string.chat_thought_secs, durationSecs)
                    } else {
                        stringResource(R.string.chat_thought_done)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (thinking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded && content.isNotBlank()) {
                MarkdownText(
                    markdown = content,
                    modifier = Modifier.padding(top = spacing.s),
                )
            }
        }
    }
}

/** Folded tool-call record (memories/search/RAG dumps stay out of the flow). */
@Composable
fun ToolMessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    val summary = toolSummary(message.content)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.m, vertical = spacing.s)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                ToolContent(
                    content = message.content,
                    modifier = Modifier.padding(top = spacing.s),
                )
            }
        }
    }
}

private fun toolSummary(content: String): String {
    val firstTag = Regex("<(\\w+)").find(content)?.groupValues?.getOrNull(1)
    return when {
        firstTag == null -> content.lineSequence().firstOrNull()?.take(60) ?: "Tool"
        else -> "Tool: $firstTag"
    }
}

@Composable
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinkingAlpha",
    )
    Text(
        text = "● ● ●",
        modifier = modifier.alpha(alpha),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StreamingCursor(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        modifier = modifier.alpha(alpha),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MessageActions(
    text: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        IconButton(
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) } },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.chat_action_copy),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Collapsed execution trace ("Ran N steps"), mirroring web. Tapping expands
 * the prelude + tool rows inline.
 */
@Composable
fun StepsRow(
    steps: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            Text(
                text = stringResource(R.string.chat_steps_ran, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.padding(top = spacing.xs),
            ) {
                steps.forEach { step ->
                    when (step.role) {
                        "tool", "function" -> ToolMessageRow(message = step)
                        else -> Text(
                            text = step.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Parsed web citation from `[^n]: [title](url)` definitions. */
data class ChatCitation(val index: String, val title: String, val url: String) {
    val host: String get() = runCatching {
        android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)
}

private val FOOTNOTE_DEF = Regex("""(?m)^\[\^(\d+)\]:\s*(.+)$""")
private val MD_LINK = Regex("""\[([^\]]*)\]\((https?://[^)]+)\)""")

/** Extracts citations; URLs double as the inline-link map. */
fun parseCitations(markdown: String): List<ChatCitation> {
    if (!markdown.contains("[^")) return emptyList()
    return FOOTNOTE_DEF.findAll(markdown).mapNotNull { match ->
        val raw = match.groupValues[2].trim()
        val link = MD_LINK.find(raw)
        val url = link?.groupValues?.getOrNull(2)
            ?: raw.split(Regex("""\s+""")).firstOrNull().orEmpty()
        if (!url.startsWith("http")) return@mapNotNull null
        val title = link?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: url
        ChatCitation(index = match.groupValues[1], title = title, url = url)
    }.toList()
}

/**
 * Horizontal citation cards under the message, mirroring web search cards:
 * title + host, tapping opens the URL.
 */
@Composable
fun CitationRow(
    citations: List<ChatCitation>,
    modifier: Modifier = Modifier,
    onOpen: (ChatCitation) -> Unit = {},
) {
    if (citations.isEmpty()) return
    val spacing = MaterialTheme.spacing
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(citations.size, key = { citations[it].index }) { i ->
            val citation = citations[i]
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .width(150.dp)
                    .clickable { onOpen(citation) },
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = spacing.m, vertical = spacing.s),
                ) {
                    Text(
                        text = citation.title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = citation.host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Bottom preview for any tapped link: title, URL, host; jump on confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkPreviewSheet(
    citation: ChatCitation,
    onDismiss: () -> Unit,
) {
    val spacing = MaterialTheme.spacing
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l)
                .padding(bottom = spacing.xl),
            verticalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            // Favicon via Google S2 (best-effort; fails silent to letter).
            var faviconOk by remember(citation.url) { mutableStateOf(true) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.m),
            ) {
                if (faviconOk) {
                    AsyncImage(
                        model = "https://www.google.com/s2/favicons?domain=${citation.host}&sz=64",
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                        onError = { faviconOk = false },
                    )
                } else {
                    InitialAvatar(name = citation.host, size = 40.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = citation.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = citation.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = citation.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = {
                    runCatching { uriHandler.openUri(citation.url) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.chat_link_open))
            }
        }
    }
}

/**
 * Live turn: sealed prelude/tool steps collapse into a steps row while the
 * current answer streams borderless beneath.
 */
@Composable
fun StreamingTurn(
    steps: List<ChatViewModel.StreamSegment>,
    agentName: String?,
    agentAvatar: String? = null,
    streamingText: String,
    streamingReasoning: String,
    streamingReasoningSecs: Double?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (steps.isNotEmpty()) {
            StreamingStepsRow(steps = steps)
        }
        AssistantMessageRow(
            message = ChatMessage(role = "assistant"),
            agentName = agentName,
            agentAvatar = agentAvatar,
            isStreaming = true,
            streamingText = streamingText,
            streamingReasoning = streamingReasoning,
            streamingReasoningSecs = streamingReasoningSecs,
        )
    }
}

@Composable
private fun StreamingStepsRow(
    steps: List<ChatViewModel.StreamSegment>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val spacing = MaterialTheme.spacing
    val running = steps.any { it.toolName != null && !it.toolDone }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
        ) {
            if (running) {
                ThinkingDots()
            }
            Text(
                text = if (running) {
                    stringResource(R.string.chat_steps_running, steps.size)
                } else {
                    stringResource(R.string.chat_steps_ran, steps.size)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.padding(top = spacing.xs),
            ) {
                steps.forEach { step ->
                    if (step.toolName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.s),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = step.toolName.ifBlank {
                                    stringResource(R.string.chat_tool_unnamed)
                                } + if (step.toolDone) "" else " …",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = step.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Renders tool XML (`searchResults`/`crawlResults`/`memories`) as entries. */
@Composable
private fun ToolContent(content: String, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    val entries = remember(content) { parseToolEntries(content) }
    if (entries.isEmpty()) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    var preview by remember { mutableStateOf<ChatCitation?>(null) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (entry.url != null) {
                            Modifier.clickable {
                                preview = ChatCitation(
                                    index = "",
                                    title = entry.title,
                                    url = entry.url,
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
                horizontalArrangement = Arrangement.spacedBy(spacing.s),
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.url != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    entry.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    preview?.let {
        LinkPreviewSheet(citation = it, onDismiss = { preview = null })
    }
}

private data class ToolEntry(val title: String, val url: String?, val detail: String?)

private fun parseToolEntries(content: String): List<ToolEntry> {
    val out = mutableListOf<ToolEntry>()
    // <item title=".." url="..">body</item> / <page url="..">body</page>
    Regex("""<(item|page)\s+([^>]+)>([\s\S]*?)</\1>""").findAll(content).forEach { m ->
        val attrs = m.groupValues[2]
        val title = Regex("""title="([^"]*)"""").find(attrs)?.groupValues?.getOrNull(1).orEmpty()
        val url = Regex("""url="([^"]*)"""").find(attrs)?.groupValues?.getOrNull(1)
            ?.takeIf { it.startsWith("http") }
        val body = m.groupValues[3].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        if (title.isNotBlank() || url != null) {
            out.add(ToolEntry(title.ifBlank { url ?: "" }, url, body.take(200).takeIf { it.isNotBlank() }))
        }
    }
    // <status>..</status> / <memories query=".."> wrapper info
    Regex("""<status>([\s\S]*?)</status>""").findAll(content).forEach { m ->
        val text = m.groupValues[1].trim()
        if (text.isNotBlank()) out.add(0, ToolEntry(text, null, null))
    }
    if (out.isEmpty()) {
        Regex("""<(\w+)\s+query="([^"]*)"""").findAll(content).forEach { m ->
            out.add(ToolEntry("${m.groupValues[1]}: ${m.groupValues[2]}", null, null))
        }
    }
    return out
}
