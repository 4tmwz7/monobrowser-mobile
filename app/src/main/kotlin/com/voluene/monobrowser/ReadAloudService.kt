package com.voluene.monobrowser

import com.voluene.monobrowser.media.MediaInfo
import com.voluene.monobrowser.media.MediaPlaybackService
import com.voluene.monobrowser.media.MediaSource
import com.voluene.monobrowser.reader.ReadAloudState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read aloud as media playback (notification, lock screen, Now Bar, watch); see [MediaPlaybackService]. */
class ReadAloudService : MediaPlaybackService() {
    override val channelId = "read_aloud"
    override val channelName = R.string.reader_listen
    override val notificationId = 41
    override val smallIcon = R.drawable.ic_notification_read_aloud

    override val source: MediaSource by lazy {
        val player = (application as MonoApp).readAloud
        fun info(s: ReadAloudState) =
            if (s.articleUrl == null || s.finished) null
            else MediaInfo(
                s.title.ifBlank { getString(R.string.reader_listen) }, s.site, s.speaking, rate = s.rate,
                artwork = com.voluene.monobrowser.media.Artwork.monogram(this, s.site),
            )
        object : MediaSource {
            override val info: Flow<MediaInfo?> = player.state.map(::info)
            override fun current() = info(player.state.value)
            override fun play() = player.resume()
            override fun pause() = player.pause()
            override fun skip(forward: Boolean) = player.skip(forward)
            override fun stop() = player.stop()
        }
    }
}
