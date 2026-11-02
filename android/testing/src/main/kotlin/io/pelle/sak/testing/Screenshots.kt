package io.pelle.sak.testing

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.pelle.sak.core.ui.SakTheme
import java.io.File

// the screenshots of the docs (docs/screenshots/), each in the light and in the dark mode of the
// phone: the content of an app in its theme, captured as android-<name>.png and
// android-<name>-dark.png into the directory of the system property sak.screenshots
// (mise run android:screenshots). The colors of the wallpaper are android's defaults
class Screenshots(
    private val compose: ComposeContentTestRule,
) {
    private var dark by mutableStateOf(false)

    private val dir = File(System.getProperty("sak.screenshots") ?: error("sak.screenshots is not set"))

    // the content once, the mode of the phone switched under it
    fun show(content: @Composable () -> Unit) {
        forgetFileProviders()
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                }
            val resources = LocalResources.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalResources provides remember(resources) { ReleaseNames(resources) },
            ) { SakTheme { content() } }
        }
    }

    // FileProvider keeps the directories of the app it saw first for good, robolectric gives every
    // test other ones, e.g. the cache the camera of the grocery app writes into
    private fun forgetFileProviders() {
        val cache = Class.forName("androidx.core.content.FileProvider").getDeclaredField("sCache").apply { isAccessible = true }
        synchronized(cache.get(null)!!) { (cache.get(null) as MutableMap<*, *>).clear() }
    }

    // what is shown now, light and dark; settle lets the app and compose finish what they started
    fun shoot(
        name: String,
        settle: () -> Unit,
    ) {
        for (mode in listOf(false, true)) {
            dark = mode
            settle()
            compose.onRoot().captureRoboImage(File(dir, if (mode) "android-$name-dark.png" else "android-$name.png"))
        }
        dark = false
    }
}

// the texts of the debug build the screenshots are taken with, its name as the release has it: "SAK
// Notes", not "SAK Notes (debug)"
@Suppress("DEPRECATION")
private class ReleaseNames(
    base: Resources,
) : Resources(base.assets, base.displayMetrics, base.configuration) {
    override fun getString(id: Int): String = super.getString(id).removeSuffix(DEBUG)

    override fun getText(id: Int): CharSequence = super.getText(id).let { if (it.endsWith(DEBUG)) it.removeSuffix(DEBUG) else it }

    private companion object {
        const val DEBUG = " (debug)"
    }
}
