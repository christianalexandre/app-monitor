package appmonitor.desktop.designsystem

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Colors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Surface
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

private val AppShapes =
    Shapes(
        small = RoundedCornerShape(Radius.medium),
        medium = RoundedCornerShape(Radius.medium),
        large = RoundedCornerShape(Radius.none),
    )

/** Light unless [dark]; every screen and component of the desktop app runs under it. */
@Composable
fun AppMonitorTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colors = colors.toMaterial(!dark), typography = AppTypography, shapes = AppShapes) {
            content()
        }
    }
}

private fun AppColors.toMaterial(isLight: Boolean) =
    Colors(
        primary = primary,
        primaryVariant = primaryVariant,
        secondary = secondary,
        secondaryVariant = secondaryVariant,
        background = background,
        surface = surface,
        error = error,
        onPrimary = onPrimary,
        onSecondary = onSecondary,
        onBackground = onSurface,
        onSurface = onSurface,
        onError = onError,
        isLight = isLight,
    )

object AppMonitorTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable
        get() = LocalAppColors.current
}

/** The window's background, filling it. */
@Composable
fun AppSurface(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), content = content)
}

/** What a piece of text is; its size, weight and font come from the role (ADR-0202). */
enum class TextRole {
    Title,
    Heading,
    Body,
    BodyStrong,
    Small,
    SmallStrong,
    Caption,

    /** A section's label, in capitals and the muted color ([Text] does both). */
    Label,
    Code,
    CodeCaption,
}

// Without Trim.None, Compose drops the leading above a text's first line and below its last.
private val Untrimmed = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    size: Double,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    family: FontFamily = FontFamily.Default,
    spacing: Double = 0.0,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = spacing.em,
    lineHeightStyle = Untrimmed,
)

private val Styles: Map<TextRole, TextStyle> =
    mapOf(
        TextRole.Title to style(14.0, 18, FontWeight.SemiBold),
        TextRole.Heading to style(13.0, 17, FontWeight.SemiBold),
        TextRole.Body to style(12.5, 17),
        TextRole.BodyStrong to style(12.5, 17, FontWeight.Medium),
        TextRole.Small to style(11.5, 15),
        TextRole.SmallStrong to style(11.5, 15, FontWeight.Medium),
        TextRole.Caption to style(10.5, 14),
        TextRole.Label to style(10.0, 13, FontWeight.SemiBold, spacing = 0.08),
        TextRole.Code to style(11.5, 18, family = FontFamily.Monospace),
        TextRole.CodeCaption to style(10.5, 14, family = FontFamily.Monospace),
    )

internal fun styleOf(role: TextRole): TextStyle = Styles.getValue(role)

// Material's own components (buttons, fields, menus) read these slots; no slot keeps Material's default.
private val AppTypography =
    Typography(
        h1 = styleOf(TextRole.Title),
        h2 = styleOf(TextRole.Title),
        h3 = styleOf(TextRole.Title),
        h4 = styleOf(TextRole.Title),
        h5 = styleOf(TextRole.Title),
        h6 = styleOf(TextRole.Title),
        subtitle1 = styleOf(TextRole.Body),
        subtitle2 = styleOf(TextRole.Heading),
        body1 = styleOf(TextRole.Body),
        body2 = styleOf(TextRole.Small),
        button = styleOf(TextRole.Small),
        caption = styleOf(TextRole.Caption),
        overline = styleOf(TextRole.Label),
    )
