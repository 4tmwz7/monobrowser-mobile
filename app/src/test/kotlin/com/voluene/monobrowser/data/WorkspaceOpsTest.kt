package com.voluene.monobrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceOpsTest {
    private val main = WorkspaceOps.main()
    private fun seeded(vararg values: Double): () -> Double { var i = 0; return { values[i++ % values.size] } }

    @Test fun sanitizers() {
        assertEquals("a", WorkspaceOps.sanitizeIcon("  ab"))
        assertEquals("👨‍👩‍👧", WorkspaceOps.sanitizeIcon("👨‍👩‍👧 family"))
        assertNull(WorkspaceOps.sanitizeIcon(""))
        assertNull(WorkspaceOps.sanitizeIcon(null))
        assertEquals("Praca i dom", WorkspaceOps.sanitizeName("  Praca   i   dom  "))
        assertNull(WorkspaceOps.sanitizeName("   "))
        assertEquals(40, WorkspaceOps.sanitizeName("x".repeat(100))!!.length)
    }

    @Test fun iconKeepsEmojiSequencesWholeButNothingAfterThem() {
        assertEquals("🇵🇱", WorkspaceOps.sanitizeIcon("🇵🇱🇩🇪")) // a flag is two regional indicators
        assertEquals("👍🏽", WorkspaceOps.sanitizeIcon("👍🏽x")) // skin-tone modifier
        assertEquals("❤\uFE0F", WorkspaceOps.sanitizeIcon("❤\uFE0Fabc")) // variation selector
        assertEquals("e\u0301", WorkspaceOps.sanitizeIcon("e\u0301e")) // combining accent
        assertEquals("◼", WorkspaceOps.sanitizeIcon("◼●"))
    }

    @Test fun displayNameUsesMainLabelOnlyForUnnamedMain() {
        assertEquals("Główna", WorkspaceOps.displayName(main, "Główna"))
        assertEquals("Praca", WorkspaceOps.displayName(main.copy(name = "Praca"), "Główna"))
        assertEquals("Przestrzeń", WorkspaceOps.displayName(Workspace("ws-a"), "Główna", fallback = "Przestrzeń"))
    }

    @Test fun normalizeKeepsMainDropsDuplicatesAndJunk() {
        val list = WorkspaceOps.normalize(listOf(
            Workspace("ws-a", "A", "★"), Workspace("ws-a", "dup"), Workspace("Bad Id!", "x"), Workspace("ws-b", "", ""),
        ))
        assertEquals(listOf("main", "ws-a", "ws-b"), list.map { it.id })
        assertEquals("★", list[1].icon)
        assertEquals(WorkspaceOps.ICONS[0], list[2].icon)
        assertEquals(listOf("main"), WorkspaceOps.normalize(emptyList()).map { it.id })
    }

    @Test fun normalizeCapsTheList() {
        val many = (0 until 40).map { Workspace("ws-$it", "$it") }
        assertEquals(WorkspaceOps.MAX_WORKSPACES, WorkspaceOps.normalize(many).size)
    }

    @Test fun addGeneratesUniqueIdsDefaultNamesAndIcons() {
        val (list1, first) = WorkspaceOps.add(listOf(main), null, null, "Space", "Main", seeded(0.1))!!
        assertEquals("Space 2", first.name)
        assertNotEquals(main.icon, first.icon)
        val (_, second) = WorkspaceOps.add(list1, " Praca ", "P", "Space", "Main", seeded(0.1, 0.1, 0.9))!!
        assertNotEquals(first.id, second.id)
        assertEquals("Praca", second.name)
        assertEquals("P", second.icon)
        assertTrue(WorkspaceOps.isValidId(second.id))
    }

    @Test fun addRefusesBeyondTheLimit() {
        var list = listOf(main)
        while (list.size < WorkspaceOps.MAX_WORKSPACES) list = WorkspaceOps.add(list, null, null, "Space", "Main")!!.first
        assertNull(WorkspaceOps.add(list, "X", null, "Space", "Main"))
    }

    @Test fun updatePatchesOnlyGivenFields() {
        val list = listOf(main, Workspace("ws-a", "A", "A"))
        val renamed = WorkspaceOps.update(list, "ws-a", name = "Nowa")!!
        assertEquals("Nowa", renamed[1].name)
        assertEquals("A", renamed[1].icon)
        assertEquals("Nowa", WorkspaceOps.update(renamed, "ws-a", name = "   ")!![1].name) // blank is ignored
        assertEquals("★", WorkspaceOps.update(renamed, "ws-a", icon = "★")!![1].icon)
        assertEquals("", WorkspaceOps.update(listOf(main.copy(name = "X")), "main", name = " ")!![0].name) // main resets
        assertNull(WorkspaceOps.update(list, "ghost", name = "x"))
    }

    @Test fun removeNeverRemovesMain() {
        val list = listOf(main, Workspace("ws-a"), Workspace("ws-b"))
        assertNull(WorkspaceOps.remove(list, "main"))
        assertNull(WorkspaceOps.remove(list, "ghost"))
        assertEquals(listOf("main", "ws-b"), WorkspaceOps.remove(list, "ws-a")!!.map { it.id })
    }

    @Test fun moveClampsAndReorders() {
        val list = listOf(main, Workspace("ws-a"), Workspace("ws-b"))
        assertEquals(listOf("main", "ws-b", "ws-a"), WorkspaceOps.move(list, "ws-a", 1)!!.map { it.id })
        assertEquals(listOf("ws-b", "main", "ws-a"), WorkspaceOps.move(list, "ws-b", -5)!!.map { it.id })
        assertEquals(list, WorkspaceOps.move(list, "main", -1))
        assertNull(WorkspaceOps.move(list, "ghost", 1))
    }

    @Test fun adjacentWrapsAround() {
        val list = listOf(main, Workspace("ws-a"), Workspace("ws-b"))
        assertEquals("ws-a", WorkspaceOps.adjacentId(list, "main", 1))
        assertEquals("main", WorkspaceOps.adjacentId(list, "ws-b", 1))
        assertEquals("ws-b", WorkspaceOps.adjacentId(list, "main", -1))
        assertNull(WorkspaceOps.adjacentId(listOf(main), "main", 1))
    }
}
