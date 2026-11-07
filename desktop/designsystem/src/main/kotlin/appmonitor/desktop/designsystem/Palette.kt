package appmonitor.desktop.designsystem

import androidx.compose.ui.graphics.Color

/** Raw tones, light to dark; only [LightAppColors] and [DarkAppColors] read them (ADR-0202). */
internal object AppGray {
    val gray0 = Color(0xFFFFFFFF)
    val gray25 = Color(0xFFF6F6F8)
    val gray50 = Color(0xFFF0F0F2)
    val gray75 = Color(0xFFEAEAEC)
    val gray150 = Color(0xFFD8D8DA)
    val gray350 = Color(0xFFABABAF)
    val gray450 = Color(0xFF8A8A8E)
    val gray550 = Color(0xFF6E6E73)
    val gray650 = Color(0xFF55555A)
    val gray750 = Color(0xFF3A3A3C)
    val gray800 = Color(0xFF2A2A2C)
    val gray825 = Color(0xFF252527)
    val gray850 = Color(0xFF1E1E20)
    val gray875 = Color(0xFF1C1C1E)
    val gray900 = Color(0xFF18181A)
    val gray925 = Color(0xFF161618)
    val gray950 = Color(0xFF0E0E10)
}

internal object AppBlue {
    val blue300 = Color(0xFF7AAFD8)
    val blue400 = Color(0xFF6B9FD4)
    val blue500 = Color(0xFF4A6FA5)
    val blue600 = Color(0xFF3A5F95)
}

internal object AppGreen {
    val green200 = Color(0xFF7FD18B)
    val green300 = Color(0xFF4CD964)
    val green600 = Color(0xFF1F8A3B)
    val green700 = Color(0xFF1F7A35)
}

internal object AppAmber {
    val amber200 = Color(0xFFFFD60A)
    val amber300 = Color(0xFFFFC15E)
    val amber700 = Color(0xFFB26A00)
    val amber800 = Color(0xFFA25B00)
}

internal object AppRed {
    val red300 = Color(0xFFFF6961)
    val red600 = Color(0xFFC93A32)
}
