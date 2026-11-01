package com.voluene.monobrowser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the version tests of the desktop `portable-update.test.ts`. */
class VersionsTest {
    @Test fun precedenceFollowsSemver() {
        assertEquals(0, Versions.compare("0.5.2", "0.5.2"))
        assertEquals(1, Versions.compare("v0.6.0", "0.5.9"))
        assertEquals(-1, Versions.compare("0.5.2", "0.10.0"))
        assertEquals(1, Versions.compare("1.0.0", "1.0.0-rc.1"))
        assertEquals(-1, Versions.compare("1.0.0-rc.2", "1.0.0-rc.10"))
        assertEquals(1, Versions.compare("1.0.0-alpha", "1.0.0-1"))
        assertEquals(0, Versions.compare("1.0.0+build5", "1.0.0"))
        assertNull(Versions.compare("nope", "1.0.0"))
        assertNull(Versions.compare("1.0.0", null))
    }

    @Test fun isNewer() {
        assertTrue(Versions.isNewer("v0.5.3", "0.5.2"))
        assertFalse(Versions.isNewer("0.5.2", "0.5.2"))
        assertFalse(Versions.isNewer("0.5.1", "0.5.2"))
        assertFalse(Versions.isNewer("garbage", "0.5.2"))
        assertTrue(Versions.isNewer("0.1.1", "0.1.0"))
        assertTrue(Versions.isNewer("1.0.0", "0.99.99"))
    }

    @Test fun parseRejectsNonSemver() {
        assertNotNull(Versions.parse(" v1.2.3 "))
        assertNull(Versions.parse("1.2"))
        assertNull(Versions.parse("1.2.3.4"))
        assertNull(Versions.parse(""))
        assertNull(Versions.parse("99999999999999999999.0.0"))
    }

    @Test fun stripPrefix() {
        assertEquals("0.1.0", Versions.stripPrefix(" v0.1.0 "))
        assertEquals("0.1.0", Versions.stripPrefix("0.1.0"))
    }
}
