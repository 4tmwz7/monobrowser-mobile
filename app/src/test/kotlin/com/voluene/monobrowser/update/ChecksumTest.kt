package com.voluene.monobrowser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumTest {
    private val hash = "a".repeat(32) + "B".repeat(32)
    private val lower = hash.lowercase()

    @Test fun parsesSha256sumOutput() {
        assertEquals(lower, Checksum.parse("$hash  MonoBrowser-0.2.0.apk\n", "MonoBrowser-0.2.0.apk"))
        assertEquals(lower, Checksum.parse("$hash *MonoBrowser-0.2.0.apk", "MonoBrowser-0.2.0.apk"))
        assertEquals(lower, Checksum.parse("$hash  ./dist/MonoBrowser-0.2.0.apk", "MonoBrowser-0.2.0.apk"))
    }

    @Test fun parsesBareHash() = assertEquals(lower, Checksum.parse("  $hash \n", "anything.apk"))

    @Test fun picksTheLineOfTheRequestedFile() {
        val other = "1".repeat(64)
        assertEquals(lower, Checksum.parse("$other  other.apk\n$hash  MonoBrowser-0.2.0.apk\n", "MonoBrowser-0.2.0.apk"))
        assertNull(Checksum.parse("$other  other.apk\n", "MonoBrowser-0.2.0.apk"))
    }

    @Test fun rejectsMalformedInput() {
        assertNull(Checksum.parse("", "a.apk"))
        assertNull(Checksum.parse("not a hash  a.apk", "a.apk"))
        assertNull(Checksum.parse("abc123  a.apk", "a.apk"))
        assertNull(Checksum.parse("<html>404</html>", "a.apk"))
    }

    @Test fun hashesStreams() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Checksum.sha256Hex("".byteInputStream()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksum.sha256Hex("abc".byteInputStream()))
    }

    @Test fun comparisonIgnoresCase() {
        assertTrue(Checksum.matches(lower, hash))
        assertFalse(Checksum.matches(lower, "0".repeat(64)))
        assertFalse(Checksum.matches(lower, ""))
    }
}
