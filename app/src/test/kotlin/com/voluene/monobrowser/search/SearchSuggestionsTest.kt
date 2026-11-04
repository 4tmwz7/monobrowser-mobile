package com.voluene.monobrowser.search

import com.voluene.monobrowser.settings.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionsTest {
    @Test fun urls() {
        val google = SearchSuggestions.url(SearchEngine.GOOGLE, " łódź pogoda ", "pl")!!
        assertTrue(google.startsWith("https://suggestqueries.google.com/complete/search?client=firefox"))
        assertTrue(google.endsWith("&hl=pl&q=%C5%82%C3%B3d%C5%BA%20pogoda"))
        assertEquals("https://duckduckgo.com/ac/?type=list&q=a%26b", SearchSuggestions.url(SearchEngine.DUCKDUCKGO, "a&b", "en"))
        assertNull(SearchSuggestions.url(SearchEngine.CUSTOM, "x", "en"))
        assertNull(SearchSuggestions.url(SearchEngine.GOOGLE, "   ", "en"))
    }

    @Test fun parsesOpenSearchJson() {
        val body = """["łód",["łódź","Łódź","łódź pogoda","łód","",3,"łódka","łódź piotrkowska"],[],{"google:x":1}]"""
        assertEquals(listOf("łódź", "łódź pogoda", "łódka", "łódź piotrkowska"), SearchSuggestions.parse(body, "łód"))
        assertEquals(listOf("łódź"), SearchSuggestions.parse(body, "łód", limit = 1))
    }

    @Test fun garbageGivesNothing() {
        assertEquals(emptyList<String>(), SearchSuggestions.parse("<html>", "q"))
        assertEquals(emptyList<String>(), SearchSuggestions.parse("{}", "q"))
        assertEquals(emptyList<String>(), SearchSuggestions.parse("""["q"]""", "q"))
        assertEquals(emptyList<String>(), SearchSuggestions.parse("""["q", {"a":1}]""", "q"))
    }
}
