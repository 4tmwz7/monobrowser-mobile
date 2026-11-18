package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalLinkPolicyTest {
    private val app = LinkAction.OpenApp("reddit://r/x", "reddit")
    private fun intent(fallback: String?, pkg: String? = "com.reddit.frontpage") = LinkAction.OpenIntentUri(
        "intent://x#Intent;scheme=reddit;" + (pkg?.let { "package=$it;" } ?: "") + "end", fallback,
    )

    @Test fun noGestureNeverDoesAnything() {
        for (a in listOf(app, intent("https://a.b"), LinkAction.OpenExternal("mailto:a@b.c"), LinkAction.Ignore("x"))) {
            assertEquals(ExternalDecision.None, ExternalLinkPolicy.decide(a, false, listOf("Reddit")))
        }
    }

    @Test fun loadIsNotHandled() = assertEquals(ExternalDecision.None, ExternalLinkPolicy.decide(LinkAction.Load, true, null))

    @Test fun mailtoAndFriendsOpenAtOnce() =
        assertEquals(ExternalDecision.OpenNow, ExternalLinkPolicy.decide(LinkAction.OpenExternal("tel:1"), true, emptyList()))

    @Test fun ignoredSchemesToast() =
        assertEquals(ExternalDecision.ToastUnsupported, ExternalLinkPolicy.decide(LinkAction.Ignore("file"), true, null))

    @Test fun ownSchemeAsksWithTheAppName() {
        assertEquals(ExternalDecision.Ask("Reddit", null), ExternalLinkPolicy.decide(app, true, listOf("Reddit")))
        assertEquals(ExternalDecision.Ask(null, null, listOf("A", "B")), ExternalLinkPolicy.decide(app, true, listOf("A", "B")))
    }

    @Test fun ownSchemeWithoutAppToasts() {
        assertEquals(ExternalDecision.ToastNoApp, ExternalLinkPolicy.decide(app, true, emptyList()))
        assertEquals(ExternalDecision.ToastNoApp, ExternalLinkPolicy.decide(app, true, null))
    }

    @Test fun intentWithAppAsksAndStayLoadsFallback() {
        assertEquals(ExternalDecision.Ask("Reddit", "https://a.b"), ExternalLinkPolicy.decide(intent("https://a.b"), true, listOf("Reddit")))
        assertEquals(ExternalDecision.Ask("Reddit", null), ExternalLinkPolicy.decide(intent(null), true, listOf("Reddit")))
    }

    @Test fun intentWithoutAppUsesFallbackAtOnce() {
        assertEquals(ExternalDecision.LoadFallback("https://a.b"), ExternalLinkPolicy.decide(intent("https://a.b"), true, emptyList()))
        assertEquals(ExternalDecision.LoadFallback("https://a.b"), ExternalLinkPolicy.decide(intent("https://a.b"), true, null))
    }

    @Test fun intentWithPackageOnlyAsksForTheStore() {
        assertEquals(ExternalDecision.Ask(null, null), ExternalLinkPolicy.decide(intent(null), true, emptyList()))
    }

    @Test fun intentWithNothingToDoToasts() {
        assertEquals(ExternalDecision.ToastNoApp, ExternalLinkPolicy.decide(intent(null, pkg = null), true, emptyList()))
        // unsafe (file:/content: data) intents are never started, not even via the store
        assertEquals(ExternalDecision.ToastNoApp, ExternalLinkPolicy.decide(intent(null), true, null))
    }
}
