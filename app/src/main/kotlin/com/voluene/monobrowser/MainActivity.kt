package com.voluene.monobrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.voluene.monobrowser.browser.PictureInPicture
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.voluene.monobrowser.browser.ActivityHost
import com.voluene.monobrowser.browser.IncomingIntent
import com.voluene.monobrowser.browser.IncomingRequest
import com.voluene.monobrowser.browser.TabManager
import com.voluene.monobrowser.ui.browser.BrowserScreen
import com.voluene.monobrowser.settings.AppLanguage
import com.voluene.monobrowser.ui.theme.MonoTheme
import com.voluene.monobrowser.util.LocaleHelper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** `ACTION_CREATE_DOCUMENT` with the MIME type chosen per call (the stock contract fixes it at registration). */
private class CreateDocumentWithType : androidx.activity.result.contract.ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).setType(input.second).addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

class MainActivity : ComponentActivity(), ActivityHost {
    private val tabManager: TabManager by viewModels()

    private var documentResult: ((Uri?) -> Unit)? = null
    private var shownLanguage: AppLanguage = AppLanguage.SYSTEM
    private var fileChooserResult: ((Array<Uri>?) -> Unit)? = null
    private var permissionResult: ((Map<String, Boolean>) -> Unit)? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileChooserResult
        fileChooserResult = null
        callback?.invoke(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = documentResult
        documentResult = null
        callback?.invoke(uri)
    }

    private val createDocument = registerForActivityResult(CreateDocumentWithType()) { uri ->
        val callback = documentResult
        documentResult = null
        callback?.invoke(uri)
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val callback = permissionResult
        permissionResult = null
        callback?.invoke(granted)
    }

    override fun attachBaseContext(newBase: Context) {
        shownLanguage = LocaleHelper.savedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrap(newBase)) // Android < 13: per-app language
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tabManager.host = this
        if (savedInstanceState == null) handleIntent(intent)
        // Language changed in the settings: apply (Android 13+ recreates by itself, older versions need recreate()).
        lifecycleScope.launch {
            tabManager.settings.map { it.language }.distinctUntilChanged().collect { language ->
                if (language == shownLanguage) return@collect
                shownLanguage = language
                if (LocaleHelper.apply(this@MainActivity, language)) recreate()
            }
        }
        // Floating video window: kept in step with what plays, so the system can shrink the app into it smoothly
        // (Android 12+ enters it by itself on the home gesture; older versions on onUserLeaveHint).
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine((application as MonoApp).webMedia.state, tabManager.settings.map { it.autoPictureInPicture }, tabManager.activeTabId) { _, auto, _ ->
                // Only what the window depends on (position reports every few seconds change nothing here).
                tabManager.pictureInPictureVideo(requirePlaying = false)?.let { listOf(it.playing, it.videoWidth, it.videoHeight) } to auto
            }.distinctUntilChanged().collect { updatePictureInPicture() }
        }
        setContent {
            val settings by tabManager.settings.collectAsState()
            MonoTheme(settings.themeId, settings.themeMode) { BrowserScreen(tabManager) }
        }
    }

    // --- Picture in picture ---

    private fun pictureInPictureParams(): android.app.PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        // In the window a paused video stays (with a play button); entering needs one that plays.
        val video = tabManager.pictureInPictureVideo(requirePlaying = false)
        val canEnter = video?.playing == true
        val builder = android.app.PictureInPictureParams.Builder()
        if (video != null) {
            // The system accepts 1:2.39 .. 2.39:1.
            val ratio = PictureInPicture.ratio(video.videoWidth, video.videoHeight)
            builder.setAspectRatio(android.util.Rational(ratio.first, ratio.second))
            builder.setActions(listOf(pictureInPictureAction(video.playing)))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(canEnter && tabManager.settings.value.autoPictureInPicture)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pictureInPictureAction(playing: Boolean): android.app.RemoteAction {
        val action = if (playing) com.voluene.monobrowser.media.MediaPlaybackService.ACTION_PAUSE else com.voluene.monobrowser.media.MediaPlaybackService.ACTION_PLAY
        val intent = android.app.PendingIntent.getService(
            this, if (playing) 11 else 12, Intent(this, WebMediaService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = getString(if (playing) R.string.media_pause else R.string.media_play)
        val icon = android.graphics.drawable.Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        return android.app.RemoteAction(icon, label, label, intent)
    }

    private fun updatePictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        pictureInPictureParams()?.let { runCatching { setPictureInPictureParams(it) } }
    }

    override fun enterPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        if (tabManager.pictureInPictureVideo() == null) return false
        val params = pictureInPictureParams() ?: return false
        return runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters by itself (auto-enter); before that, going home with a video playing enters here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && tabManager.settings.value.autoPictureInPicture) enterPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        tabManager.onPictureInPicture(isInPictureInPictureMode)
        // Left the window by closing it (not by expanding it back): the activity is stopped, not resumed.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            tabManager.onPictureInPictureDismissed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun copyFromScreen(source: android.graphics.Rect, target: android.graphics.Bitmap, onResult: (Boolean) -> Unit) {
        if (source.isEmpty) return onResult(false)
        runCatching {
            android.view.PixelCopy.request(window, source, target, { result -> onResult(result == android.view.PixelCopy.SUCCESS) }, android.os.Handler(mainLooper))
        }.onFailure { onResult(false) }
    }

    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // RUNNING_LOW while visible, or BACKGROUND and worse while in the background.
        @Suppress("DEPRECATION")
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) tabManager.onLowMemory()
    }

    override fun onStart() {
        super.onStart()
        tabManager.onAppVisible(true)
    }

    override fun onResume() {
        super.onResume()
        tabManager.updater.onAppResumed() // back from "Install unknown apps" settings -> continue the update
    }

    override fun onStop() {
        super.onStop()
        tabManager.onAppVisible(false)
        tabManager.flush() // the process may be killed while in the background
    }

    override fun onDestroy() {
        if (tabManager.host === this) tabManager.host = null
        // A pending request can no longer be answered; unblock the page.
        fileChooserResult?.invoke(null)
        fileChooserResult = null
        permissionResult?.invoke(emptyMap())
        permissionResult = null
        documentResult?.invoke(null)
        documentResult = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(android.app.SearchManager.QUERY)
            else -> null
        }
        when (val request = IncomingIntent.parse(intent.action, intent.dataString, text)) {
            is IncomingRequest.Open -> tabManager.openExternalInput(request.input)
            is IncomingRequest.NewTab ->
                if (request.focusAddress) tabManager.openNewTab(request.isPrivate, focusAddress = true) else tabManager.openNewTab(request.isPrivate)
            IncomingRequest.None -> Unit
        }
    }

    override fun pickFiles(params: WebChromeClient.FileChooserParams, onResult: (Array<Uri>?) -> Unit) {
        fileChooserResult?.invoke(null) // a new request replaces the previous one
        fileChooserResult = onResult
        try {
            fileChooser.launch(params.createIntent())
        } catch (_: android.content.ActivityNotFoundException) {
            fileChooserResult = null
            onResult(null)
        }
    }

    override fun requestPermissions(permissions: List<String>, onResult: (Map<String, Boolean>) -> Unit) {
        permissionResult?.invoke(emptyMap())
        permissionResult = onResult
        this.permissions.launch(permissions.toTypedArray())
    }

    override fun openDocument(mimeTypes: Array<String>, onResult: (Uri?) -> Unit) {
        documentResult?.invoke(null)
        documentResult = onResult
        try {
            openDocument.launch(mimeTypes)
        } catch (_: android.content.ActivityNotFoundException) {
            documentResult = null
            onResult(null)
        }
    }

    override fun createDocument(suggestedName: String, mimeType: String, onResult: (Uri?) -> Unit) {
        documentResult?.invoke(null)
        documentResult = onResult
        try {
            createDocument.launch(suggestedName to mimeType)
        } catch (_: android.content.ActivityNotFoundException) {
            documentResult = null
            onResult(null)
        }
    }
}
