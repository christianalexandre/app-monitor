package appmonitor.desktop.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Every gap and padding a screen uses; a value missing here is added here, not written in the screen. */
object Spacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 24.dp
}

/** Corner radii: [small] for badges and marks, [medium] for controls, fields and cards (ADR-0202), [large] for the palette. */
object Radius {
    val none: Dp = 0.dp
    val small: Dp = 4.dp
    val medium: Dp = 6.dp
    val large: Dp = 12.dp
}

/** Heights of the window's fixed bars (ADR-0202). */
object BarHeight {
    val title: Dp = 40.dp
    val detail: Dp = 38.dp
    val status: Dp = 24.dp
}

object Elevation {
    val dialog: Dp = 8.dp
}

/** Widths of [DialogCard], from a two-button question to a table of endpoints. */
enum class DialogWidth(
    val width: Dp,
) {
    Narrow(420.dp),
    Medium(520.dp),
    Large(640.dp),
    Wide(720.dp),
    Table(1180.dp),
}

/** Every color a component paints, with a light and a dark value; Material's colors are built from them. */
data class AppColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val primary: Color,
    val primaryVariant: Color,
    val onPrimary: Color,
    val secondary: Color,
    val secondaryVariant: Color,
    val onSecondary: Color,
    val error: Color,
    val onError: Color,
    // The window's layers behind the content ([surface]): its title bar, sidebar, request list and status bar, and the rule between them.
    val titleBar: Color,
    val sidebar: Color,
    val list: Color,
    val statusBar: Color,
    val border: Color,
    // Text quieter than [neutral]: a section's label, a URL's query.
    val muted: Color,
    // The row a list shows as selected, and what dims the window under a dialog.
    val selected: Color,
    val scrim: Color,
    // A badge's text in each tone; its background is the same color, faded ([Badge]).
    val success: Color,
    val info: Color,
    val warning: Color,
    val neutral: Color,
    // A code block's background, a search match behind its text (the current one stronger), and JSON's keys, strings and values ([CodeText]).
    val code: Color,
    val highlight: Color,
    val highlightCurrent: Color,
    val syntaxKey: Color,
    val syntaxString: Color,
    val syntaxValue: Color,
)

internal val LightAppColors =
    AppColors(
        background = AppGray.gray25,
        surface = AppGray.gray0,
        onSurface = AppGray.gray875,
        primary = AppBlue.blue500,
        primaryVariant = AppBlue.blue600,
        onPrimary = AppGray.gray0,
        secondary = AppBlue.blue500,
        secondaryVariant = AppBlue.blue600,
        onSecondary = AppGray.gray0,
        error = AppRed.red600,
        onError = AppGray.gray0,
        titleBar = AppGray.gray50,
        sidebar = AppGray.gray75,
        list = AppGray.gray25,
        statusBar = AppGray.gray75,
        border = AppGray.gray150,
        muted = AppGray.gray350,
        selected = AppBlue.blue500.copy(alpha = 0.10f),
        scrim = AppGray.gray950.copy(alpha = 0.32f),
        success = AppGreen.green600,
        info = AppBlue.blue600,
        warning = AppAmber.amber700,
        neutral = AppGray.gray550,
        code = AppGray.gray25,
        highlight = AppAmber.amber300.copy(alpha = 0.5f),
        highlightCurrent = AppAmber.amber300,
        syntaxKey = AppBlue.blue600,
        syntaxString = AppGreen.green700,
        syntaxValue = AppAmber.amber800,
    )

internal val DarkAppColors =
    AppColors(
        background = AppGray.gray850,
        surface = AppGray.gray825,
        onSurface = AppGray.gray50,
        primary = AppBlue.blue400,
        primaryVariant = AppBlue.blue300,
        onPrimary = AppGray.gray0,
        secondary = AppBlue.blue400,
        secondaryVariant = AppBlue.blue300,
        onSecondary = AppGray.gray0,
        error = AppRed.red300,
        onError = AppGray.gray950,
        titleBar = AppGray.gray875,
        sidebar = AppGray.gray900,
        list = AppGray.gray850,
        statusBar = AppGray.gray925,
        border = AppGray.gray750,
        muted = AppGray.gray650,
        selected = AppBlue.blue400.copy(alpha = 0.13f),
        scrim = AppGray.gray950.copy(alpha = 0.52f),
        success = AppGreen.green300,
        info = AppBlue.blue300,
        warning = AppAmber.amber200,
        neutral = AppGray.gray450,
        code = AppGray.gray800,
        highlight = AppAmber.amber300.copy(alpha = 0.35f),
        highlightCurrent = AppAmber.amber300.copy(alpha = 0.8f),
        syntaxKey = AppBlue.blue300,
        syntaxString = AppGreen.green200,
        syntaxValue = AppAmber.amber300,
    )
