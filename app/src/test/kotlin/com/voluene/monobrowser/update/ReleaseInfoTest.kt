package com.voluene.monobrowser.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    private val page = "https://github.com/o/r/releases"

    private fun payload(
        tag: String = "v0.2.0", draft: Boolean = false, prerelease: Boolean = false,
        assets: String = """
            {"name":"MonoBrowser-0.2.0.apk","size":9612706,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/MonoBrowser-0.2.0.apk"},
            {"name":"MonoBrowser-0.2.0.apk.sha256","size":90,"browser_download_url":"https://github.com/o/r/releases/download/v0.2.0/MonoBrowser-0.2.0.apk.sha256"}
        """,
        extra: String = ""","html_url":"https://github.com/o/r/releases/tag/v0.2.0"""",
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[$assets]$extra}"""

    @Test fun parsesApkAndChecksumAssets() {
        val r = ReleaseParser.parse(payload(), page)!!
        assertEquals("v0.2.0", r.tag)
        assertEquals("0.2.0", r.version)
        assertEquals("MonoBrowser-0.2.0.apk", r.apkName)
        assertEquals(9612706L, r.apkSize)
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/MonoBrowser-0.2.0.apk", r.apkUrl)
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/MonoBrowser-0.2.0.apk.sha256", r.shaUrl)
        assertEquals("https://github.com/o/r/releases/tag/v0.2.0", r.pageUrl)
    }

    @Test fun keepsBodyAndPublishedAt() {
        val extra = """,
            "body":"## New\n- one\n- [site](https://x.y)\n","published_at":"2026-10-30T12:00:00Z","html_url":"https://github.com/o/r/releases/tag/v0.2.0" """
        val r = ReleaseParser.parse(payload(extra = extra), page)!!
        assertEquals("## New\n- one\n- [site](https://x.y)", r.body)
        assertEquals("2026-10-30T12:00:00Z", r.publishedAt)
    }

    @Test fun missingOrNullBodyIsEmpty() {
        val none = ReleaseParser.parse(payload(), page)!!
        assertEquals("", none.body)
        assertNull(none.publishedAt)
        val nul = ReleaseParser.parse(payload(extra = """,
            "body":null,"published_at":null,"html_url":"https://github.com/o/r/releases/tag/v0.2.0" """), page)!!
        assertEquals("", nul.body)
        assertNull(nul.publishedAt)
    }

    @Test fun skipsDraftsAndPrereleases() {
        assertNull(ReleaseParser.parse(payload(draft = true), page))
        assertNull(ReleaseParser.parse(payload(prerelease = true), page))
    }

    @Test fun requiresBothAssets() {
        val apkOnly = """{"name":"MonoBrowser-0.2.0.apk","size":1,"browser_download_url":"https://github.com/a.apk"}"""
        assertNull(ReleaseParser.parse(payload(assets = apkOnly), page))
        assertNull(ReleaseParser.parse(payload(assets = ""), page))
    }

    @Test fun assetNamesMustMatchTheTagVersion() {
        val wrong = """
            {"name":"MonoBrowser-0.1.0.apk","size":1,"browser_download_url":"https://github.com/a.apk"},
            {"name":"MonoBrowser-0.1.0.apk.sha256","size":1,"browser_download_url":"https://github.com/a.sha"}
        """
        assertNull(ReleaseParser.parse(payload(assets = wrong), page))
    }

    @Test fun rejectsNonVersionTagsAndGarbage() {
        assertNull(ReleaseParser.parse(payload(tag = "latest"), page))
        assertNull(ReleaseParser.parse("not json", page))
        assertNull(ReleaseParser.parse("[]", page))
        assertNull(ReleaseParser.parse("{}", page))
    }

    @Test fun pageUrlFallsBackToTagPage() {
        val r = ReleaseParser.parse(payload(extra = ""), "$page/")!!
        assertEquals("$page/tag/v0.2.0", r.pageUrl)
        assertEquals("$page/tag/v0.2.0", ReleaseParser.parse(payload(extra = ""","html_url":"javascript:alert(1)""""), page)!!.pageUrl)
    }

    @Test fun trustedUrls() {
        assertTrue(TrustedUrls.isTrusted("https://github.com/o/r/releases/download/v1/a.apk"))
        assertTrue(TrustedUrls.isTrusted("https://objects.githubusercontent.com/x"))
        assertFalse(TrustedUrls.isTrusted("http://github.com/o/r/a.apk"))
        assertFalse(TrustedUrls.isTrusted("https://evilgithub.com/a.apk"))
        assertFalse(TrustedUrls.isTrusted("https://github.com.evil.io/a.apk"))
        assertFalse(TrustedUrls.isTrusted("https://user@evil.io/a.apk"))
        assertFalse(TrustedUrls.isTrusted("http://10.0.2.2:8000/a.apk"))
    }

    @Test fun testOriginIsTrustedOnlyWhenOverridden() {
        val base = "http://10.0.2.2:8000/repos/o/r"
        assertTrue(TrustedUrls.isTrusted("http://10.0.2.2:8000/a.apk", base))
        assertFalse(TrustedUrls.isTrusted("http://10.0.2.2:9000/a.apk", base))
        assertFalse(TrustedUrls.isTrusted("http://evil.io:8000/a.apk", base))
        assertFalse(TrustedUrls.isTrusted("http://api.github.com/a.apk", "http://api.github.com/repos/o/r"))
        assertNotNull(ReleaseParser.parse(payload(), page))
    }
}
