package net.clench.wallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClenchOrange = Color(0xFFFF6B00)
private val ClenchDark = Color(0xFF121212)

private val DarkColors = darkColorScheme(
    primary = ClenchOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2A1800),
    onPrimaryContainer = ClenchOrange,
    background = ClenchDark,
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2C2C2C),
)

private val LightColors = lightColorScheme(
    primary = ClenchOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0C2),
    onPrimaryContainer = Color(0xFF2A1800),
)

@Composable
fun ClenchTheme(
    darkTheme: Boolean = true, // default dark — wallets feel more secure dark
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
