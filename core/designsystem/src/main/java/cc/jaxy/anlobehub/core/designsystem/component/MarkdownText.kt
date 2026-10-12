package cc.jaxy.anlobehub.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration

/**
 * @param streaming true while tokens arrive: skips [SelectionContainer]
 * (its recomposition cost causes visible flicker at 12fps updates).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    if (markdown.isEmpty()) return
    // Resolve `[^n]` footnote citations to inline links (upstream has no
    // footnote extension); drop the definition block at the tail.
    val cited = remember(markdown) { markdown.resolveFootnotes() }
    val content = cited
    // Chat-sized headings: upstream maps h1-h3 to display styles which
    // explode in a message bubble (web renders h3 as a small section title).
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        h3 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        h4 = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
        h5 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        h6 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        quote = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        code = MaterialTheme.typography.bodySmall.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        ),
        textLink = TextLinkStyles(
            style = androidx.compose.ui.text.SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Bold,
            ),
        ),
    )
    val colors = markdownColor(
        codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    )
    val components = markdownComponents(
        blockQuote = { model ->
            // Rounded tint + primary rail, mirroring web quote cards.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.matchParentSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 2.dp)
                            .fillMaxHeight(),
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    // Strip one level of `> ` markers, then render nested
                    // markdown (links/emphasis) without re-entering quotes.
                    val inner = remember(model.node, model.content) {
                        model.node.getUnescapedTextInNode(model.content)
                            .lineSequence()
                            .joinToString(separator = "\n") { line -> line.removePrefix(">").trimStart() }
                            .resolveFootnotes()
                    }
                    Markdown(
                        content = inner,
                        typography = model.typography,
                        colors = markdownColor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
    // Settled content renders synchronously (parse + layout in one frame,
    // no pop-in on scroll). Only the live stream uses the incremental
    // state; the swap happens atomically with the list item swap.
    if (!streaming) {
        SelectionContainer {
            Markdown(
                content = content,
                typography = typography,
                colors = colors,
                components = components,
                modifier = modifier,
            )
        }
    } else {
        DisableSelectionIf(disabled = true) {
            UnifiedStreamingMarkdown(
                content = content,
                typography = typography,
                colors = colors,
                components = components,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DisableSelectionIf(disabled: Boolean, content: @Composable () -> Unit) {
    if (disabled) {
        androidx.compose.foundation.text.selection.DisableSelection { content() }
    } else {
        content()
    }
}

/**
 * Incremental streaming render: diffs against the last emitted text and
 * appends only the delta, so already-laid-out blocks never re-parse
 * (the main source of scroll jumping during generation).
 */
@Composable
private fun UnifiedStreamingMarkdown(
    content: String,
    typography: com.mikepenz.markdown.model.MarkdownTypography,
    colors: com.mikepenz.markdown.model.MarkdownColors,
    components: com.mikepenz.markdown.compose.components.MarkdownComponents,
    modifier: Modifier = Modifier,
) {
    val state = com.mikepenz.markdown.model.rememberStreamingMarkdownState()
    var emitted by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(content) {
        // Streaming text is append-only; a shorter value means restart.
        val delta = if (content.startsWith(emitted)) {
            content.substring(emitted.length)
        } else {
            emitted = ""
            content
        }
        // Fresh state per message instance; reset requires recomposition key.
        if (delta.isNotEmpty()) {
            state.append(delta)
            emitted = content
        }
    }
    // Key on nothing: the same state instance accumulates across chunks.
    Markdown(
        streamingMarkdownState = state,
        typography = typography,
        colors = colors,
        components = components,
        modifier = modifier,
    )
}

/**
 * Cuts an unclosed trailing construct that would otherwise re-layout the
 * whole message on every token: odd ``` fence count, unclosed `[`/`*`/`_`.
 */
private fun String.trimUnstableTail(): String {
    val fenceCount = Regex("```").findAll(this).count()
    var cut = length
    if (fenceCount % 2 == 1) {
        cut = minOf(cut, lastIndexOf("```"))
    }
    // Unclosed link/text emphasis in the last line: hold the line back.
    val lastNl = lastIndexOf('\n', cut - 1)
    val tail = substring(if (lastNl < 0) 0 else lastNl + 1, cut)
    if (tail.contains('[') && !tail.contains(']') ||
        tail.count { it == '*' } % 2 == 1 ||
        tail.count { it == '_' } % 2 == 1
    ) {
        cut = if (lastNl < 0) 0 else lastNl
    }
    return if (cut <= 0) this else substring(0, cut)
}

/**
 * `[^1]` -> `[1](url)` using trailing `[^1]: ...` definitions (plain URL or
 * `[title](url)` form). Unknown refs degrade to plain `[1]` text.
 */
private fun String.resolveFootnotes(): String {
    if (!contains("[^")) return this
    val defs = mutableMapOf<String, String>()
    // Definition lines: [^n]: <url> | [^n]: [title](url)
    val defPattern = Regex("""(?m)^\[\^(\d+)\]:\s*(.+)$""")
    var body = defPattern.replace(this) { match ->
        val url = Regex("""\((https?://[^)]+)\)""").find(match.groupValues[2])
            ?.groupValues?.getOrNull(1)
            ?: match.groupValues[2].trim().split(Regex("""\\s+""")).firstOrNull().orEmpty()
        if (url.startsWith("http")) defs[match.groupValues[1]] = url
        ""
    }
    body = Regex("""\[\^(\d+)\]""").replace(body) { match ->
        val url = defs[match.groupValues[1]]
        if (url != null) "[🔗]($url)" else "[${match.groupValues[1]}]"
    }
    return body.replace(Regex("\n{3,}"), "\n\n").trim()
}
