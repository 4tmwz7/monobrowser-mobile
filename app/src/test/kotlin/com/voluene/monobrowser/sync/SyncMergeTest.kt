package com.voluene.monobrowser.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val now = 1_000_000_000L
    private fun e(v: String?, t: Long = 100) = SyncEntry(v, t)

    @Test fun firstSyncUnitesBothSides() {
        val r = SyncMerge.merge(base = emptyMap(), local = mapOf("a" to "A", "both" to "local"), remote = mapOf("b" to e("B"), "both" to e("remote")), now = now)
        assertEquals(mapOf("a" to "A", "both" to "local", "b" to "B"), r.local)
        assertEquals(e("A", now), r.remote["a"])
        assertEquals(e("local", now), r.remote["both"]) // this device's version of a key both have
        assertEquals(e("B"), r.remote["b"])
    }

    @Test fun remoteChangesComeIn() {
        val base = mapOf("a" to "A", "b" to "B")
        val r = SyncMerge.merge(base, local = base, remote = mapOf("a" to e("A2", 200), "b" to e(null, 200), "c" to e("C")), now = now)
        assertEquals(mapOf("a" to "A2", "c" to "C"), r.local)
        assertTrue(r.localChanged)
        assertFalse(r.remoteChanged)
    }

    @Test fun localChangesGoOut() {
        val base = mapOf("a" to "A", "b" to "B")
        val remote = mapOf("a" to e("A"), "b" to e("B"))
        val r = SyncMerge.merge(base, local = mapOf("a" to "A edited", "c" to "C"), remote = remote, now = now)
        assertEquals(e("A edited", now), r.remote["a"])
        assertEquals(e(null, now), r.remote["b"]) // deleted here -> tombstone, so it does not come back
        assertEquals(e("C", now), r.remote["c"])
        assertEquals(mapOf("a" to "A edited", "c" to "C"), r.local)
        assertFalse(r.localChanged)
    }

    @Test fun deletedHereStaysDeletedEvenIfTheFileStillHasIt() {
        val base = mapOf("x" to "X")
        val r = SyncMerge.merge(base, local = emptyMap(), remote = mapOf("x" to e("X")), now = now)
        assertNull(r.local["x"])
        assertEquals(e(null, now), r.remote["x"])
    }

    @Test fun resetFileGetsEverythingBack() {
        val base = mapOf("a" to "A")
        val r = SyncMerge.merge(base, local = base, remote = emptyMap(), now = now)
        assertEquals(mapOf("a" to "A"), r.local)
        assertEquals(e("A", now), r.remote["a"])
    }

    @Test fun oldTombstonesExpire() {
        val later = SyncMerge.TOMBSTONE_TTL_MS * 2
        val r = SyncMerge.merge(emptyMap(), emptyMap(), mapOf("old" to e(null, 0), "new" to e(null, later - 1000)), now = later)
        assertFalse("old" in r.remote)
        assertTrue("new" in r.remote)
    }

    @Test fun nothingToDoIsNoChange() {
        val base = mapOf("a" to "A")
        val r = SyncMerge.merge(base, base, mapOf("a" to e("A")), now)
        assertFalse(r.localChanged)
        assertFalse(r.remoteChanged)
    }

    @Test fun twoDevicesConverge() {
        // Phone and desktop start from the same file, each changes something, then both sync twice.
        var file = mapOf("shared" to e("S"), "gone" to e("G"))
        var phoneBase = mapOf("shared" to "S", "gone" to "G"); var phone = phoneBase
        var pcBase = phoneBase; var pc = pcBase
        phone = phone - "gone" + ("p" to "from phone")
        pc = pc + ("shared" to "S edited on pc") + ("d" to "from pc")
        fun syncPhone() { val r = SyncMerge.merge(phoneBase, phone, file, now); phone = r.local; phoneBase = r.local; file = r.remote }
        fun syncPc() { val r = SyncMerge.merge(pcBase, pc, file, now + 1); pc = r.local; pcBase = r.local; file = r.remote }
        syncPhone(); syncPc(); syncPhone()
        val expected = mapOf("shared" to "S edited on pc", "p" to "from phone", "d" to "from pc")
        assertEquals(expected, phone)
        assertEquals(expected, pc)
    }

    @Test fun codec() {
        val file = SyncFile(updatedAt = 5, updatedBy = "Pixel", collections = mapOf("bookmarks" to mapOf("k" to e("v"), "del" to e(null))))
        assertEquals(file, SyncCodec.decode(SyncCodec.encode(file)))
        assertEquals(SyncFile(), SyncCodec.decode("  "))
        assertNull(SyncCodec.decode("""{"format":"something-else"}"""))
        assertNull(SyncCodec.decode("not json"))
        val base = mapOf("bookmarks" to mapOf("k" to "v"))
        assertEquals(base, SyncCodec.decodeBase(SyncCodec.encodeBase(base)))
    }
}
