package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLinksTest {
    @Test fun webSchemesLoadInTheWebView() {
        for (url in listOf("https://example.com", "HTTP://example.com", "about:blank", "blob:https://x/abc")) {
            assertEquals(url, LinkAction.Load, ExternalLinks.classify(url))
        }
    }

    @Test fun appSchemesOpenExternally() {
        for (url in listOf("mailto:a@b.c", "tel:+48123", "sms:123?body=hi", "smsto:1", "mms:1", "mmsto:1", "geo:50,20")) {
            assertEquals(url, LinkAction.OpenExternal(url), ExternalLinks.classify(url))
        }
    }

    @Test fun ownAppSchemesAreAsked() {
        assertEquals(LinkAction.OpenApp("reddit://r/x", "reddit"), ExternalLinks.classify("reddit://r/x"))
        assertEquals(LinkAction.OpenApp("vnd.youtube:abc", "vnd.youtube"), ExternalLinks.classify("vnd.youtube:abc"))
        assertEquals(LinkAction.OpenApp("FB://profile/1", "fb"), ExternalLinks.classify("FB://profile/1"))
        assertEquals(LinkAction.OpenApp("market://details?id=x.y", "market"), ExternalLinks.classify("market://details?id=x.y"))
    }

    @Test fun dangerousAndUnknownSchemesAreIgnored() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "content://x/y", "data:text/html,hi", "view-source:https://a", "chrome://settings", "android-app://x", "ftp://x", "monobrowser-retry:")) {
            assertTrue(url, ExternalLinks.classify(url) is LinkAction.Ignore)
        }
        assertEquals(LinkAction.Ignore("javascript"), ExternalLinks.classify("JavaScript:alert(1)"))
        assertEquals(LinkAction.Ignore(""), ExternalLinks.classify("no scheme"))
    }

    private val intentWithFallback =
        "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Finstall%3Fa%3D1;end"

    @Test fun intentUrisKeepTheirFallback() {
        val action = ExternalLinks.classify(intentWithFallback) as LinkAction.OpenIntentUri
        assertEquals(intentWithFallback, action.uri)
        assertEquals("https://example.com/install?a=1", action.fallbackUrl)
        assertEquals("com.google.zxing.client.android", ExternalLinks.intentPackage(intentWithFallback))
    }

    @Test fun nonHttpFallbacksAreRejected() {
        assertNull(ExternalLinks.intentFallbackUrl("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"))
        assertNull(ExternalLinks.intentFallbackUrl("intent://x#Intent;S.browser_fallback_url=intent%3A%2F%2Fother;end"))
        assertNull(ExternalLinks.intentFallbackUrl("intent://x#Intent;scheme=a;end"))
        assertNull(ExternalLinks.intentPackage("intent://x#Intent;scheme=a;end"))
    }

    @Test fun schemeIsLowercased() {
        assertEquals("mailto", ExternalLinks.schemeOf(" MAILTO:a@b.c"))
    }

    @Test fun displayTargetIsShortAndReadable() {
        fun target(url: String) = ExternalLinks.displayTarget(ExternalLinks.classify(url))
        assertEquals("com.reddit.frontpage", target("market://details?id=com.reddit.frontpage&referrer=af_tranid%3DMz%26pid%3Dxpromo"))
        assertEquals("reddit://reddit/r/Polska", target("reddit://reddit/r/Polska/?af_channel=xpromo&af_deeplink=true#x"))
        assertEquals("com.example.app", target("intent://x#Intent;scheme=foo;package=com.example.app;end"))
        assertNull(target("intent://x#Intent;scheme=foo;end"))
        assertNull(target("https://example.com/"))
        val long = target("myapp://" + "a".repeat(200))!!
        assertEquals(60, long.length)
        assertTrue(long.endsWith("\u2026"))
    }
}
