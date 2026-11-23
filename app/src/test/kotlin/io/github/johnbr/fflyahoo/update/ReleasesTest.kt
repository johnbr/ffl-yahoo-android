package io.github.johnbr.fflyahoo.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleasesTest {
    private fun release(tag: String = "v0.31.0", assets: String = APK, extra: String = "") = """
        {"tag_name":"$tag","html_url":"https://github.com/johnbr/ffl-yahoo-android/releases/tag/$tag",
         "draft":false,"prerelease":false,"name":"ignored"$extra,"assets":[$assets]}
    """.trimIndent()

    @Test
    fun `parses the APK asset and its digest`() {
        val r = Releases.parse(release())!!
        assertEquals("0.31.0", r.version)
        assertEquals("ffl-live-v0.31.0.apk", r.apkName)
        assertEquals("https://github.com/johnbr/ffl-yahoo-android/releases/download/v0.31.0/ffl-live-v0.31.0.apk", r.apkUrl)
        assertEquals(2668810, r.size)
        assertEquals("2068ee061e074b30dd5fa9b3fb93bbeca2cb23b4d514b996c35424d5e9dd265d", r.sha256)
        assertEquals("https://github.com/johnbr/ffl-yahoo-android/releases/tag/v0.31.0", r.page)
    }

    @Test
    fun `a release without an APK is not an update`() {
        assertNull(Releases.parse(release(assets = "")))
        assertNull(Releases.parse(release(assets = """{"name":"notes.txt","browser_download_url":"x"}""")))
    }

    @Test
    fun `drafts, pre-releases and odd tags are not updates`() {
        assertNull(Releases.parse(release().replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(Releases.parse(release().replace("\"draft\":false", "\"draft\":true")))
        assertNull(Releases.parse(release(tag = "nightly")))
    }

    @Test
    fun `no digest leaves the hash unknown`() {
        assertNull(Releases.parse(release(assets = """{"name":"a.apk","browser_download_url":"u","size":3}"""))!!.sha256)
    }

    @Test
    fun `compares versions numerically`() {
        assertTrue(Releases.isNewer("0.31.0", "0.30.0"))
        assertTrue(Releases.isNewer("0.30.10", "0.30.9"))
        assertTrue(Releases.isNewer("1.0.0", "0.99.99"))
        assertFalse(Releases.isNewer("0.30.0", "0.30.0"))
        assertFalse(Releases.isNewer("0.29.5", "0.30.0"))
        // A local build's suffixed version is compared on its numbers.
        assertTrue(Releases.isNewer("0.31.0", "0.30.0-dev"))
        assertFalse(Releases.isNewer("garbage", "0.30.0"))
    }

    private companion object {
        const val APK = """{"name":"ffl-live-v0.31.0.apk",
            "browser_download_url":"https://github.com/johnbr/ffl-yahoo-android/releases/download/v0.31.0/ffl-live-v0.31.0.apk",
            "size":2668810,"digest":"sha256:2068EE061e074b30dd5fa9b3fb93bbeca2cb23b4d514b996c35424d5e9dd265d"}"""
    }
}
