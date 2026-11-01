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
}
