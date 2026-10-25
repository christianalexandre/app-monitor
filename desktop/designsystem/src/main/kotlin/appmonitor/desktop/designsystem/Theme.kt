package appmonitor.desktop.designsystem

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Surface
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

private val LocalAppColors = staticCompositionLocalOf { LightAppColors }

private val AppShapes =
    Shapes(
        small = RoundedCornerShape(Radius.small),
        medium = RoundedCornerShape(Radius.small),
        large = RoundedCornerShape(Radius.none),
    )

/** Light unless [dark]; every screen and component of the desktop app runs under it. */
@Composable
fun AppMonitorTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkAppColors else LightAppColors) {
        MaterialTheme(colors = if (dark) darkColors() else lightColors(), typography = Typography(), shapes = AppShapes) {
            content()
        }
    }
}

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

/** What a piece of text is; its size, weight and font come from the role. */
enum class TextRole {
    Title,
    Heading,
    Body,
    BodyStrong,
    Small,
    SmallStrong,
    Caption,
    Code,
    CodeCaption,
}

@Composable
@ReadOnlyComposable
internal fun styleOf(role: TextRole): TextStyle {
    val type = MaterialTheme.typography
    return when (role) {
        TextRole.Title -> type.h6
        TextRole.Heading -> type.subtitle2
        TextRole.Body -> type.body1
        TextRole.BodyStrong -> type.body1.copy(fontWeight = FontWeight.Medium)
        TextRole.Small -> type.body2
        TextRole.SmallStrong -> type.body2.copy(fontWeight = FontWeight.Medium)
        TextRole.Caption -> type.caption
        TextRole.Code -> type.body1.copy(fontFamily = FontFamily.Monospace)
        TextRole.CodeCaption -> type.caption.copy(fontFamily = FontFamily.Monospace)
    }
}
