package com.voluene.monobrowser.ui_tests

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.ui.browser.BrowserScreen
import com.voluene.monobrowser.ui.theme.MonoTheme
import org.junit.Rule
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView.rootView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun tour() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var manager: TabManager
        compose.runOnUiThread {
            manager = TabManager(app)
            manager.updateSettings { it.copy(onboardingDone = true) }
            manager.editBookmarks { data ->
                var d = data
                listOf("https://github.com/" to "GitHub", "https://kotlinlang.org/" to "Kotlin", "https://pl.wikipedia.org/" to "Wikipedia").forEach { (u, t) ->
                    d = com.voluene.monobrowser.data.BookmarkOps.addBookmark(d, u, t, null, 1L)
                }
                d
            }
        }
        compose.setContent {
            val settings by manager.settings.collectAsState()
            MonoTheme(settings.themeId, settings.themeMode) { BrowserScreen(manager) }
        }
        fun open(label: String, shot: String) {
            compose.onNodeWithContentDescription("Menu").performClick()
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithText(label).performClick()
            save(shot)
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle(); compose.mainClock.advanceTimeBy(600)
        }
        open("Settings", "tour_settings")
        open("History", "tour_history")
        open("Bookmarks", "tour_bookmarks")
        open("Downloads", "tour_downloads")
        open("Notes", "tour_notes")
        compose.onNodeWithText("Search or enter address", useUnmergedTree = true).let { }
        compose.onAllNodesWithText("Search or enter address")[0].performClick()
        save("tour_palette")
    }

    @Test fun readingList() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var manager: TabManager
        compose.runOnUiThread {
            manager = TabManager(app)
            manager.updateSettings { it.copy(onboardingDone = true) }
        }
        val para = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
        val article = com.voluene.monobrowser.reader.ReaderArticle(
            "Saved article title", "example.com", "", "https://example.com/saved",
            List(6) { com.voluene.monobrowser.reader.ReaderBlock(com.voluene.monobrowser.reader.BlockKind.PARAGRAPH, para) },
        )
        manager.readingList.save(article)
        val deadline = System.currentTimeMillis() + 5000
        while (manager.readingList.items.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        org.junit.Assert.assertEquals(1, manager.readingList.items.value.size)
        compose.setContent {
            val settings by manager.settings.collectAsState()
            MonoTheme(settings.themeId, settings.themeMode) { BrowserScreen(manager) }
        }
        save("reading_start")
        compose.onNodeWithText("Saved article title").performClick()
        val until = System.currentTimeMillis() + 5000
        while (compose.onAllNodesWithText("1 min read").fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(50); compose.waitForIdle()
        }
        save("reading_offline")
        compose.onNodeWithText("1 min read").assertExists()
    }

    @Test fun browse() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        lateinit var manager: TabManager
        compose.runOnUiThread {
            manager = TabManager(app)
            manager.updateSettings { it.copy(onboardingDone = true) }
        }
        compose.setContent {
            val settings by manager.settings.collectAsState()
            MonoTheme(settings.themeId, settings.themeMode) { BrowserScreen(manager) }
        }
        save("app_start")
        compose.onNodeWithContentDescription("Menu").performClick()
        save("app_menu")
        compose.onNodeWithText("Settings").performClick()
        save("app_settings")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        save("app_after_back")
        compose.onNodeWithText("1").performClick()
        save("app_tabs")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        fun isLight(): Boolean {
            var light = true
            compose.runOnUiThread {
                val webView = manager.webViewFor(manager.activeTabId.value)!!
                val value = android.util.TypedValue()
                webView.context.theme.resolveAttribute(android.R.attr.isLightTheme, value, true)
                light = value.data != 0
            }
            return light
        }
        org.junit.Assert.assertTrue("light app -> light web context", isLight())
        compose.runOnUiThread { manager.updateSettings { it.copy(themeMode = com.voluene.monobrowser.settings.ThemeMode.DARK) } }
        save("app_dark")
        org.junit.Assert.assertFalse("dark app -> dark web context", isLight())
        compose.runOnUiThread { manager.updateSettings { it.copy(webDarkMode = com.voluene.monobrowser.settings.WebDarkMode.OFF) } }
        save("app_dark_weboff")
        org.junit.Assert.assertTrue("web dark off -> light web context", isLight())
        // Memory pressure and background / foreground must not break the visible tab.
        compose.runOnUiThread { manager.newTab(url = "https://example.com/"); manager.onLowMemory(); manager.onAppVisible(false); manager.onAppVisible(true) }
        save("app_after_low_memory")
        org.junit.Assert.assertNotNull(manager.webViewFor(manager.activeTabId.value))
    }
}
