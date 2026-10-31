package io.pelle.sak.grocery.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// material 3 in the light or dark mode of the system, the colors of the wallpaper where android has them
@Composable
fun GroceryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    MaterialTheme(colorScheme = colors, content = content)
}
