package com.voluene.monobrowser

import com.voluene.monobrowser.media.MediaPlaybackService
import com.voluene.monobrowser.media.MediaSource

/** Audio / video playing in a page as media playback (notification, lock screen, Now Bar, watch). */
class WebMediaService : MediaPlaybackService() {
    override val channelId = "web_media"
    override val channelName = R.string.media_channel
    override val notificationId = 42
    override val smallIcon = R.drawable.ic_notification_media
    override val source: MediaSource get() = (application as MonoApp).webMedia.source
}
