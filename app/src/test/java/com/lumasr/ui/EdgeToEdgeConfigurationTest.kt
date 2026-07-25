package com.lumasr.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeToEdgeConfigurationTest {
    private val mainActivitySource = File("src/main/java/com/lumasr/MainActivity.kt").readText()
    private val themeSource = File("src/main/java/com/lumasr/ui/theme/Theme.kt").readText()
    private val manifestSource = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun enablesEdgeToEdgeBeforeSettingComposeContent() {
        val enableEdgeToEdgeIndex = mainActivitySource.indexOf("enableEdgeToEdge()")
        val setContentIndex = mainActivitySource.indexOf("setContent {")

        assertTrue("MainActivity must enable edge-to-edge", enableEdgeToEdgeIndex >= 0)
        assertTrue(
            "Edge-to-edge must be enabled before Compose content is installed",
            setContentIndex >= 0 && enableEdgeToEdgeIndex < setContentIndex
        )
    }

    @Test
    fun themeAvoidsDeprecatedWindowEdgeToEdgeApis() {
        listOf(
            "statusBarColor",
            "navigationBarColor",
            "setDecorFitsSystemWindows"
        ).forEach { deprecatedApi ->
            assertFalse(
                "Theme must not call deprecated window API: $deprecatedApi",
                themeSource.contains(deprecatedApi)
            )
        }
    }

    @Test
    fun mainActivityResizesForImeInsets() {
        assertTrue(manifestSource.contains("""android:name=".MainActivity""""))
        assertTrue(
            "MainActivity must resize for IME insets",
            manifestSource.contains("""android:windowSoftInputMode="adjustResize"""")
        )
    }
}
