package dev.muto.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A calm green for "protected" and a muted red for "off": the home screen is mostly one large
// status colour, so both need to be readable at full-screen size without being alarming.
private val Protected = Color(0xFF2E7D57)
private val ProtectedContainer = Color(0xFFB6F0CE)
private val Interrupted = Color(0xFF9A3B32)

private val LightScheme = lightColorScheme(
    primary = Protected,
    onPrimary = Color.White,
    primaryContainer = ProtectedContainer,
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    tertiary = Color(0xFF3D6472),
    error = Interrupted,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FD6AE),
    onPrimary = Color(0xFF003821),
    primaryContainer = Color(0xFF175139),
    onPrimaryContainer = ProtectedContainer,
    secondary = Color(0xFFB4CCBA),
    tertiary = Color(0xFFA5CCDC),
    error = Color(0xFFFFB4AB),
)

@Composable
fun MutoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Material You where the platform has it: a system utility should look like part of the
        // system rather than insist on its own brand.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
