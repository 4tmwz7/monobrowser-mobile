package com.voluene.monobrowser.ui_tests

import android.app.Notification
import android.media.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.voluene.monobrowser.MonoApp
import com.voluene.monobrowser.WebMediaService
import com.voluene.monobrowser.media.WebMediaControl
import com.voluene.monobrowser.media.WebMediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Media playing in a page, published like a music app: cover, title, controls, session. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebMediaServiceTest {
    private val calls = ArrayList<String>()
    private val control = object : WebMediaControl {
        override fun play(tabId: Long) { calls += "play $tabId" }
        override fun pause(tabId: Long) { calls += "pause $tabId" }
        override fun skip(tabId: Long, forward: Boolean) { calls += "skip $forward" }
        override fun seekTo(tabId: Long, positionMs: Long) { calls += "seek $positionMs" }
    }

    private fun start(state: WebMediaState): Notification {
        val app = ApplicationProvider.getApplicationContext<MonoApp>()
        app.webMedia.control = control
        app.webMedia.update(state)
        val service = Robolectric.buildService(WebMediaService::class.java).create().startCommand(0, 1).get()
        ShadowLooper.idleMainLooper()
        return shadowOf(service).lastForegroundNotification
    }

    @Test fun musicLikeNotification() {
        val n = start(WebMediaState(7, true, "Song title", "Some artist", canNext = true, canPrevious = true, private = false, durationMs = 200_000))
        assertEquals("Song title", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Some artist", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNotNull("cover", n.getLargeIcon())
        assertNotNull(n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))
        assertEquals(listOf("Previous", "Pause", "Next", "Stop"), n.actions.map { it.title.toString() })

        val app = ApplicationProvider.getApplicationContext<MonoApp>()
        app.webMedia.source.skip(true)
        app.webMedia.source.seekTo(42_000)
        app.webMedia.source.stop()
        assertEquals(listOf("skip true", "seek 42000", "pause 7"), calls)
        assertNull(app.webMedia.state.value)
    }

    @Test fun privateTabsHideTitleAndCover() {
        val n = start(WebMediaState(3, true, "Secret song", "secret.example", canNext = false, canPrevious = false, private = true))
        assertNull(n.getLargeIcon())
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertTrue(n.publicVersion?.extras?.getString(Notification.EXTRA_TITLE) != "Secret song")
        assertEquals(listOf("Pause", "Stop"), n.actions.map { it.title.toString() })
    }
}
