package appmonitor.desktop.designsystem

import androidx.compose.ui.graphics.Color

/** Raw tones, light to dark; only [LightAppColors] and [DarkAppColors] read them (ADR-0149). */
internal object AppGray {
    val gray0 = Color(0xFFFFFFFF)
    val gray50 = Color(0xFFF7F8FA)
    val gray100 = Color(0xFFEFF1F4)
    val gray200 = Color(0xFFE2E5EA)
    val gray300 = Color(0xFFCBD0D8)
    val gray400 = Color(0xFF9CA3AF)
    val gray500 = Color(0xFF6B7280)
    val gray600 = Color(0xFF4B5563)
    val gray700 = Color(0xFF343A44)
    val gray800 = Color(0xFF23272E)
    val gray850 = Color(0xFF1B1E23)
    val gray900 = Color(0xFF141619)
}

internal object AppBlue {
    val blue200 = Color(0xFFA9C1F5)
    val blue300 = Color(0xFF86A6EE)
    val blue400 = Color(0xFF6189E3)
    val blue500 = Color(0xFF3D6BD1)
    val blue600 = Color(0xFF2F57B3)
    val blue700 = Color(0xFF25458F)
}

internal object AppGreen {
    val green300 = Color(0xFF6FCF97)
    val green700 = Color(0xFF1A7F45)
}

internal object AppAmber {
    val amber300 = Color(0xFFF2B861)
    val amber700 = Color(0xFFA35F00)
}

internal object AppRed {
    val red300 = Color(0xFFF28B82)
    val red700 = Color(0xFFC2362B)
}
