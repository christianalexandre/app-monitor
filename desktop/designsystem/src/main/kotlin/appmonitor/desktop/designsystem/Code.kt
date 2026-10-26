package appmonitor.desktop.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material.Text as MaterialText

/** What a [SyntaxSpan] marks, each kind in its own color in light and dark. */
enum class SyntaxKind { Key, String, Value }

/** [kind] from [start] until [end] of a [CodeText]'s text. */
data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val kind: SyntaxKind,
)

/** Where a piece of a code block sits; the block's first and last pieces carry its rounded corners and padding. */
enum class BlockEdge { Only, First, Middle, Last }

/** Each case-insensitive match of [query] in [text], left to right, not overlapping; none for a blank [query]. */
fun matchesOf(
    text: String,
    query: String,
): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val found = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) return found
        found += at until at + query.length
        from = at + query.length
    }
}

/** Monospaced [text]: [syntax] in its colors, each [query] match on the highlight color, all of it [muted] or [error] when asked (ADR-0152). */
@Composable
fun CodeText(
    text: String,
    modifier: Modifier = Modifier,
    query: String = "",
    syntax: List<SyntaxSpan> = emptyList(),
    role: TextRole = TextRole.Code,
    muted: Boolean = false,
    error: Boolean = false,
    softWrap: Boolean = true,
) {
    val colors = AppMonitorTheme.colors
    val annotated =
        remember(text, query, syntax, colors) {
            buildAnnotatedString {
                append(text)
                syntax.forEach { addStyle(SpanStyle(color = colorOf(it.kind, colors)), it.start, it.end) }
                matchesOf(text, query).forEach { addStyle(SpanStyle(background = colors.highlight), it.first, it.last + 1) }
            }
        }
    MaterialText(
        annotated,
        modifier,
        color =
            when {
                error -> colors.error
                muted -> colors.neutral
                else -> Color.Unspecified
            },
        softWrap = softWrap,
        style = styleOf(role),
    )
}

/** One piece of a code block: [content] on the code background, rounded and padded at the block's [edge]. */
@Composable
fun CodeBlock(
    edge: BlockEdge = BlockEdge.Only,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val top = edge == BlockEdge.Only || edge == BlockEdge.First
    val bottom = edge == BlockEdge.Only || edge == BlockEdge.Last
    val shape =
        RoundedCornerShape(
            topStart = if (top) Radius.small else Radius.none,
            topEnd = if (top) Radius.small else Radius.none,
            bottomStart = if (bottom) Radius.small else Radius.none,
            bottomEnd = if (bottom) Radius.small else Radius.none,
        )
    Box(
        modifier
            .fillMaxWidth()
            .background(AppMonitorTheme.colors.code, shape)
            .padding(
                start = Spacing.md,
                end = Spacing.md,
                top = if (top) Spacing.md else Spacing.none,
                bottom = if (bottom) Spacing.md else Spacing.none,
            ),
    ) {
        content()
    }
}

private fun colorOf(
    kind: SyntaxKind,
    colors: AppColors,
): Color =
    when (kind) {
        SyntaxKind.Key -> colors.syntaxKey
        SyntaxKind.String -> colors.syntaxString
        SyntaxKind.Value -> colors.syntaxValue
    }
