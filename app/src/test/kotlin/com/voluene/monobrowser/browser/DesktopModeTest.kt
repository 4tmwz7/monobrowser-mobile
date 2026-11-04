package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DesktopModeTest {
    @Test fun webViewUserAgentBecomesDesktopChrome() {
        val mobile = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP1A.240305.019; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/131.0.6778.135 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Safari/537.36",
            DesktopMode.userAgent(mobile),
        )
    }

    @Test fun reducedUserAgent() {
        val mobile = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"
        val desktop = DesktopMode.userAgent(mobile)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36", desktop)
        assertFalse(desktop.contains("Mobile"))
        assertFalse(desktop.contains("Android"))
    }

    @Test fun unknownUserAgentIsKept() {
        val other = "SomethingElse/1.0"
        assertEquals(other, DesktopMode.userAgent(other))
    }
}
