package com.funkodex.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FunkoOrange     = Color(0xFFE8401A)
private val FunkoDarkOrange = Color(0xFFA82C0F)
private val FunkoYellow     = Color(0xFFFFD600)

private val LightColorScheme = lightColorScheme(
    primary            = FunkoOrange,
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3B0900),
    secondary          = Color(0xFF765849),
    onSecondary        = Color.White,
    secondaryContainer = Color(0xFFFFDAD2),
    tertiary           = Color(0xFF695F30),
    surface            = Color.White,
    onSurface          = Color(0xFF201A18),
    surfaceVariant     = Color(0xFFF4DED8),
    background         = Color(0xFFF5F5F7),
)

private val DarkColorScheme = darkColorScheme(
    primary            = Color(0xFFFFB4A0),
    onPrimary          = Color(0xFF5F1400),
    primaryContainer   = FunkoDarkOrange,
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary          = Color(0xFFE7BDB0),
    onSecondary        = Color(0xFF432B20),
    tertiary           = FunkoYellow,
    surface            = Color(0xFF1C1C1E),
    onSurface          = Color(0xFFEDE0DC),
    surfaceVariant     = Color(0xFF53433F),
    background         = Color(0xFF141210),
)

// Custom theme IDs — user can pick in Settings screen
enum class AppTheme(val displayName: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark"),
    FUNKO_ORANGE("Funko Orange"),   // default — orange primary
    FUNKO_BLUE("Cool Blue"),        // alternative blue primary
    FUNKO_GOLD("Gold Edition"),     // premium gold primary
}

@Composable
fun FunkoDexTheme(
    appTheme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colorScheme = when (appTheme) {
        AppTheme.SYSTEM       -> if (systemDark) DarkColorScheme else LightColorScheme
        AppTheme.LIGHT        -> LightColorScheme
        AppTheme.DARK         -> DarkColorScheme
        AppTheme.FUNKO_ORANGE -> if (systemDark) DarkColorScheme else LightColorScheme
        AppTheme.FUNKO_BLUE   -> if (systemDark) blueDarkScheme() else blueLightScheme()
        AppTheme.FUNKO_GOLD   -> if (systemDark) goldDarkScheme() else goldLightScheme()
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

private fun blueLightScheme() = lightColorScheme(
    primary            = Color(0xFF1565C0),
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary          = Color(0xFF535F70),
    surface            = Color.White,
    background         = Color(0xFFF5F7FF),
    surfaceVariant     = Color(0xFFDDE3ED),
)
private fun blueDarkScheme() = darkColorScheme(
    primary            = Color(0xFFACC7FF),
    onPrimary          = Color(0xFF002E6A),
    primaryContainer   = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFD6E4FF),
    surface            = Color(0xFF111318),
    background         = Color(0xFF111318),
    surfaceVariant     = Color(0xFF3F4759),
)
private fun goldLightScheme() = lightColorScheme(
    primary            = Color(0xFF7B5800),
    onPrimary          = Color.White,
    primaryContainer   = Color(0xFFFFDEA3),
    onPrimaryContainer = Color(0xFF261900),
    secondary          = Color(0xFF6B5D3F),
    surface            = Color.White,
    background         = Color(0xFFFFFBF2),
    surfaceVariant     = Color(0xFFEEE1C6),
)
private fun goldDarkScheme() = darkColorScheme(
    primary            = Color(0xFFF9BC1D),
    onPrimary          = Color(0xFF402D00),
    primaryContainer   = Color(0xFF7B5800),
    onPrimaryContainer = Color(0xFFFFDEA3),
    surface            = Color(0xFF1A1B16),
    background         = Color(0xFF1A1B16),
    surfaceVariant     = Color(0xFF4A4535),
)
