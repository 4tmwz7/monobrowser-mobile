package com.voluene.monobrowser.ui_tests

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.onRoot
import com.voluene.monobrowser.R
import com.voluene.monobrowser.browser.Tab
import com.voluene.monobrowser.data.ClosedTab
import com.voluene.monobrowser.data.TopSite
import com.voluene.monobrowser.reader.Reader
import com.voluene.monobrowser.settings.ThemeMode
import com.voluene.monobrowser.ui.browser.AddressBar
import com.voluene.monobrowser.ui.browser.BrowserMenuGrid
import com.voluene.monobrowser.ui.browser.MenuIcons
import com.voluene.monobrowser.ui.browser.MenuInfo
import com.voluene.monobrowser.ui.browser.MenuTile
import com.voluene.monobrowser.ui.browser.TextSizeDialog
import com.voluene.monobrowser.ui.reader.ReaderScreen
import com.voluene.monobrowser.ui.start.StartPage
import com.voluene.monobrowser.ui.theme.MonoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.graphics.drawscope.clipPath

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { MonoTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { content() } }
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        save(name)
    }

    private fun save(name: String) {
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView.rootView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val dir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val sites = listOf(
        TopSite("https://github.com/", "GitHub", "github.com"),
        TopSite("https://news.ycombinator.com/", "Hacker News", "news.ycombinator.com"),
        TopSite("https://pl.wikipedia.org/", "Wikipedia", "pl.wikipedia.org"),
        TopSite("https://www.youtube.com/", "YouTube", "youtube.com"),
        TopSite("https://onet.pl/", "Onet", "onet.pl"),
        TopSite("https://my-site.dev/", "My site", "my-site.dev"),
        TopSite("https://allegro.pl/", "Allegro", "allegro.pl"),
    )
    private val closed = listOf(
        ClosedTab("https://kotlinlang.org/docs/coroutines-overview.html", "Coroutines | Kotlin Documentation"),
        ClosedTab("https://developer.android.com/develop/ui/compose", "Jetpack Compose UI App Development Toolkit"),
    )

    @Composable
    private fun browser(tab: Tab, page: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { page() }
            AddressBar(
                tab = tab, tabCount = 3, toolbarTop = false, onOpenPalette = {}, onAddressLongPress = {}, onSiteInfo = {}, onHistory = {},
                onBack = {}, onForward = {}, onReloadOrStop = {}, onMenu = {}, onTabs = {}, onNewTab = {}, onSwipeTab = {},
            )
        }
    }

    private fun start(name: String, dark: Boolean, private: Boolean) = shot(name, dark) {
        browser(Tab(1, isPrivate = private)) {
            StartPage(private, sites, true, closed, onSearch = {}, onOpen = {}, onSiteOptions = {}, onReopen = {})
        }
    }

    @Test fun startLight() = start("start_light", false, false)
    @Test fun startDark() = start("start_dark", true, false)
    @Test fun startPrivate() = start("start_private", false, true)
    @Test fun startEmpty() = shot("start_empty") { browser(Tab(1)) { StartPage(false, emptyList(), true, emptyList(), onSearch = {}, onOpen = {}, onSiteOptions = {}, onReopen = {}) } }

    @Test fun menu() = shot("menu") {
        val tiles = listOf(
            MenuTile(R.string.tab_new, Icons.Filled.Add) {}, MenuTile(R.string.menu_new_private_short, Icons.Filled.Lock) {},
            MenuTile(R.string.menu_bookmarks, MenuIcons.Bookmark) {}, MenuTile(R.string.menu_bookmark_remove, Icons.Filled.Star, active = true) {},
            MenuTile(R.string.menu_history, Icons.Filled.DateRange) {}, MenuTile(R.string.menu_downloads, MenuIcons.Download) {},
            MenuTile(R.string.menu_notes, Icons.Filled.Edit) {}, MenuTile(R.string.menu_note_add_short, Icons.Filled.List) {},
            MenuTile(R.string.menu_find, Icons.Filled.Search) {}, MenuTile(R.string.menu_reader, MenuIcons.Article) {},
            MenuTile(R.string.menu_desktop_site, MenuIcons.Desktop, active = true) {}, MenuTile(R.string.menu_text_size, MenuIcons.TextSize) {},
            MenuTile(R.string.menu_reopen_short, Icons.Filled.Refresh, enabled = false) {}, MenuTile(R.string.menu_share, Icons.Filled.Share) {},
            MenuTile(R.string.menu_print, MenuIcons.Print) {}, MenuTile(R.string.menu_settings, Icons.Filled.Settings) {},
        )
        browser(Tab(1, url = "https://example.com/article", blockedCount = 12)) {
            BrowserMenuGrid(tiles, MenuInfo("Blocked on this page: 12", "Disable on this site"), {})
        }
    }

    private val para = "Pchnąć w tę łódź jeża lub ośm skrzyń fig. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
    private val article = Reader.parse(
        """{"title":"Why monochrome interfaces age well","site":"example.com","byline":"Jan Kowalski","url":"https://example.com/a","blocks":[
        {"t":"p","x":"$para $para"},{"t":"p","x":"$para"},{"t":"h","x":"Less is more"},{"t":"li","x":"One colour for text"},
        {"t":"li","x":"One colour for the background and a long item that wraps onto a second line for sure"},
        {"t":"q","x":"Simplicity is the ultimate sophistication."},{"t":"p","x":"$para $para"},{"t":"pre","x":"fun main() {\n    println(\"mono\")\n}"},{"t":"p","x":"$para"}]}""",
    )!!

    @Test fun reader() = shot("reader") { ReaderScreen(article, 18, true, {}, {}, {}) }
    @Test fun readerSpeaking() = shot("reader_speaking") {
        ReaderScreen(article, 18, true, {}, {}, {}, saved = true, readAloud = com.voluene.monobrowser.reader.ReadAloudState(speaking = true, block = 1, rate = 1.25f))
    }

    @Test fun readerDarkSans() = shot("reader_dark_sans", dark = true) { ReaderScreen(article, 16, false, {}, {}, {}) }
    @Test fun tabs() = shot("tabs") {
        val ws = com.voluene.monobrowser.ui.tabs.WorkspaceUiState(
            listOf(com.voluene.monobrowser.data.WorkspaceOps.main()), "main", mapOf("main" to "Main"), mapOf("main" to 7),
        )
        val list = listOf(
            Tab(1, "GitHub · Build and ship software", "https://github.com/", blockedCount = 3),
            Tab(2, "Hacker News", "https://news.ycombinator.com/", sleeping = true),
            Tab(3, "Łódź – Wikipedia, wolna encyklopedia", "https://pl.wikipedia.org/wiki/Łódź", desktopMode = true),
            Tab(4, "", ""),
            Tab(5, "Secret", "https://example.org/", isPrivate = true),
            Tab(6, "Kotlin docs", "https://kotlinlang.org/docs/home.html", neverSleep = true),
            Tab(7, "YouTube", "https://www.youtube.com/"),
        )
        com.voluene.monobrowser.ui.tabs.TabSwitcher(
            list, 3, true, {}, {}, {}, {}, {}, { _, _ -> }, ws, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {},
            thumbnails = mapOf(1L to fakePage(0xFF2DA44E.toInt()), 3L to fakePage(0xFF3366CC.toInt()), 6L to fakePage(0xFF7F52FF.toInt())),
        )
    }

    @Test fun predictiveBack() {
        var closed = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MonoTheme(mode = ThemeMode.LIGHT) {
                browser(Tab(1)) { StartPage(false, sites, true, closed2, onSearch = {}, onOpen = {}, onSiteOptions = {}, onReopen = {}) }
                com.voluene.monobrowser.ui.common.PredictiveBackContainer(onBack = { closed = true }) { ReaderScreen(article, 18, true, {}, {}, {}) }
            }
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val d = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            d.dispatchOnBackStarted(androidx.activity.BackEventCompat(0f, 400f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
            d.dispatchOnBackProgressed(androidx.activity.BackEventCompat(120f, 400f, 0.35f, androidx.activity.BackEventCompat.EDGE_LEFT))
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        save("predictive_back_mid")
        compose.runOnUiThread { d.onBackPressed() }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        org.junit.Assert.assertTrue(closed)
    }
    private val closed2 = closed

    /** A phone with 110 px (~55 dp) rounded corners; the glass outline is drawn on top of the shot. */
    private fun roundedPhone(name: String, content: @Composable () -> Unit) {
        val r = 110f
        com.voluene.monobrowser.ui.common.ConcentricCorners.testCorners = { w, h ->
            listOf(
                com.voluene.monobrowser.ui.common.DisplayCorner(r, r, r), com.voluene.monobrowser.ui.common.DisplayCorner(w - r, r, r),
                com.voluene.monobrowser.ui.common.DisplayCorner(w - r, h - r, r), com.voluene.monobrowser.ui.common.DisplayCorner(r, h - r, r),
            )
        }
        try {
            shot(name) {
                Box(Modifier.fillMaxSize()) {
                    content()
                    // What the glass hides: everything outside a rounded rectangle.
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r)))
                        }
                        clipPath(path, androidx.compose.ui.graphics.ClipOp.Difference) {
                            drawRect(androidx.compose.ui.graphics.Color(0xFFFF3B30))
                        }
                    }
                }
            }
        } finally {
            com.voluene.monobrowser.ui.common.ConcentricCorners.testCorners = null
        }
    }

    @Test fun cornersMenu() = roundedPhone("corners_menu") {
        val tiles = listOf(
            MenuTile(R.string.tab_new, Icons.Filled.Add) {}, MenuTile(R.string.menu_new_private_short, Icons.Filled.Lock) {},
            MenuTile(R.string.menu_bookmarks, MenuIcons.Bookmark) {}, MenuTile(R.string.menu_settings, Icons.Filled.Settings) {},
        )
        BrowserMenuGrid(tiles, MenuInfo("Blocked on this page: 12", "Disable on this site"), {})
    }

    @Test fun cornersTabs() = roundedPhone("corners_tabs") {
        val ws = com.voluene.monobrowser.ui.tabs.WorkspaceUiState(
            listOf(com.voluene.monobrowser.data.WorkspaceOps.main()), "main", mapOf("main" to "Main"), mapOf("main" to 2),
        )
        com.voluene.monobrowser.ui.tabs.TabSwitcher(
            listOf(Tab(1, "GitHub", "https://github.com/"), Tab(2, "YouTube", "https://www.youtube.com/")), 1, true,
            {}, {}, {}, {}, {}, { _, _ -> }, ws, {}, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, {}, {},
        )
    }

    @Test fun cornersBar() = roundedPhone("corners_bar") { browser(Tab(1, url = "https://example.com/")) { Box(Modifier.fillMaxSize()) } }

    @Test fun siteInfo() = shot("site_info") {
        browser(Tab(1, url = "https://github.com/4tmwz7/monobrowser-mobile", blockedCount = 7)) {
            com.voluene.monobrowser.ui.browser.SiteInfoSheet(
                com.voluene.monobrowser.browser.TabManager.SiteInfo(
                    "https://github.com", "github.com", true, "Sectigo Limited", 1_800_000_000_000L,
                    listOf(com.voluene.monobrowser.browser.SitePermissionStore.Decision("https://github.com", com.voluene.monobrowser.browser.SitePermission.CAMERA, true)),
                ),
                7, true, true, false, {}, {}, {}, {}, {}, {},
            )
        }
    }

    @Test fun barReader() = shot("bar_reader") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth())
            AddressBar(
                tab = Tab(1, url = "https://martinfowler.com/articles/microservices.html", readerAvailable = true, blockedCount = 4), tabCount = 3,
                toolbarTop = false, onOpenPalette = {}, onAddressLongPress = {}, onSiteInfo = {}, onHistory = {}, onBack = {}, onForward = {},
                onReloadOrStop = {}, onMenu = {}, onTabs = {}, onNewTab = {}, onSwipeTab = {}, onReader = {},
            )
        }
    }

    @Test fun barInsecure() = shot("bar_insecure") { browser(Tab(1, url = "http://neverssl.com/online/")) { Box(Modifier.fillMaxSize()) } }

    /** A coloured fake page: header bar, text lines, an image block. */
    private fun fakePage(accent: Int): androidx.compose.ui.graphics.ImageBitmap {
        val b = Bitmap.createBitmap(360, 576, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint()
        p.color = accent; c.drawRect(0f, 0f, 360f, 60f, p)
        p.color = 0xFF333333.toInt()
        for (i in 0 until 9) c.drawRect(20f, 90f + i * 26f, if (i % 3 == 2) 220f else 340f, 104f + i * 26f, p)
        p.color = accent; c.drawRect(20f, 340f, 340f, 520f, p)
        return b.asImageBitmap()
    }

    @Test fun message() = shot("message") {
        browser(Tab(1, url = "https://example.com/")) {
            Box(Modifier.fillMaxSize()) {
                StartPage(false, sites.mapIndexed { i, t -> if (i < 2) t.copy(pinned = true) else t }, true, emptyList(), onSearch = {}, onOpen = {}, onSiteOptions = {}, onReopen = {})
                com.voluene.monobrowser.ui.browser.MessageBar(
                    com.voluene.monobrowser.browser.TabManager.UiMessage(1, "Downloaded raport-2026.pdf", "Open") {},
                    {}, Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(8.dp),
                )
            }
        }
    }

    @Test fun loadingBar() = shot("loading_bar") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth())
            AddressBar(
                tab = Tab(1, url = "https://example.com/", progress = 0), tabCount = 3, toolbarTop = false, onOpenPalette = {}, onAddressLongPress = {},
                onSiteInfo = {}, onHistory = {}, onBack = {}, onForward = {}, onReloadOrStop = {}, onMenu = {}, onTabs = {}, onNewTab = {},
                onSwipeTab = {}, loadProgress = { 40 },
            )
        }
    }

    @Test fun textSize() = shot("text_size") { browser(Tab(1, url = "https://example.com/")) { TextSizeDialog(115, {}, {}) } }
}
