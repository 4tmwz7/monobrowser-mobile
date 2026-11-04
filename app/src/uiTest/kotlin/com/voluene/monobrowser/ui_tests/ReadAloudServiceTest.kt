package com.voluene.monobrowser.ui_tests

import android.app.Notification
import android.media.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.voluene.monobrowser.MonoApp
import com.voluene.monobrowser.ReadAloudService
import com.voluene.monobrowser.reader.BlockKind
import com.voluene.monobrowser.reader.ReaderArticle
import com.voluene.monobrowser.reader.ReaderBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Read aloud is published as media playback: what the lock screen, Now Bar and a paired watch show. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadAloudServiceTest {
    @Test fun mediaNotificationAndSession() {
        val app = ApplicationProvider.getApplicationContext<MonoApp>()
        val article = ReaderArticle(
            "Why monochrome interfaces age well", "example.com", "", "https://example.com/a",
            List(4) { ReaderBlock(BlockKind.PARAGRAPH, "Paragraph $it.") },
        )
        app.readAloud.play(article)
        val controller = Robolectric.buildService(ReadAloudService::class.java).create().startCommand(0, 1)
        ShadowLooper.idleMainLooper()

        val notification = shadowOf(controller.get()).lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Why monochrome interfaces age well", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("example.com", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val token = notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        assertNotNull("MediaStyle carries the session (Now Bar / watch read it)", token)
        // (Robolectric's MediaController does not connect to the session, so its metadata is not checked here.)
        assertEquals(4, notification.actions.size)

        app.readAloud.stop()
        ShadowLooper.idleMainLooper()
        assertTrue("stops with the reading", shadowOf(controller.get()).isStoppedBySelf)
    }
}
