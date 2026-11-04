package com.voluene.monobrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SitePermissionStoreTest {
    @Test fun decisionsAreRememberedPerOriginAndPermission() {
        val store = SitePermissionStore()
        val a = "https://a.example"
        store.set(a, listOf(SitePermission.CAMERA), true)
        store.set(a, listOf(SitePermission.MICROPHONE), false)
        assertEquals(true, store[a, SitePermission.CAMERA])
        assertEquals(false, store[a, SitePermission.MICROPHONE])
        assertNull(store[a, SitePermission.LOCATION])
        assertNull(store["https://b.example", SitePermission.CAMERA])
    }

    @Test fun undecidedListsOnlyTheMissingOnes() {
        val store = SitePermissionStore()
        store.set("https://a.example", listOf(SitePermission.CAMERA), true)
        assertEquals(
            listOf(SitePermission.MICROPHONE),
            store.undecided("https://a.example", listOf(SitePermission.CAMERA, SitePermission.MICROPHONE)),
        )
    }

    @Test fun clearForgetsEverything() {
        val store = SitePermissionStore()
        store.set("https://a.example", SitePermission.entries, true)
        store.clear()
        assertNull(store["https://a.example", SitePermission.LOCATION])
    }

    @Test fun originAndHostParsing() {
        assertEquals("https://example.com", SitePermissionStore.originOf("https://Example.com/path?q=1#x"))
        assertEquals("http://example.com:8080", SitePermissionStore.originOf("http://user:pw@example.com:8080/"))
        assertNull(SitePermissionStore.originOf("about:blank"))
        assertNull(SitePermissionStore.originOf("file:///x"))
        assertEquals("example.com", SitePermissionStore.hostOf("http://example.com:8080"))
    }

    @Test fun persistsThroughOnChangeAndRestores() {
        var saved: Map<String, Boolean> = emptyMap()
        val store = SitePermissionStore { saved = it }
        store.set("https://a.com", listOf(SitePermission.CAMERA, SitePermission.LOCATION), true)
        store.set("https://b.com", listOf(SitePermission.MICROPHONE), false)
        store.forget("https://a.com", SitePermission.LOCATION)
        assertEquals(mapOf("https://a.com|CAMERA" to true, "https://b.com|MICROPHONE" to false), saved)

        val restored = SitePermissionStore(saved + mapOf("garbage" to true, "https://c.com|TELEPATHY" to true, "ftp://x|CAMERA" to true))
        assertEquals(true, restored["https://a.com", SitePermission.CAMERA])
        assertEquals(false, restored["https://b.com", SitePermission.MICROPHONE])
        assertEquals(2, restored.decisionsList().size)
        assertEquals(listOf(SitePermission.CAMERA), restored.decisionsFor("https://a.com").map { it.permission })
    }
}
