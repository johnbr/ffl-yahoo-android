package io.github.johnbr.fflyahoo.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published release with an APK the app can update itself to. */
data class Release(
    val version: String,
    /** The release's GitHub page, for its notes. */
    val page: String,
    val apkName: String,
    val apkUrl: String,
    val size: Long,
    /** Lower-case hex, from GitHub's asset digest; null if GitHub gave none. */
    val sha256: String?,
)

/** Reading GitHub's "latest release" answer, and comparing versions. No Android in here, so it is unit-tested. */
object Releases {
    const val REPO = "johnbr/ffl-yahoo-android"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        val digest: String? = null,
    )

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val page: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    /** The release in [body], or null when it is a draft or pre-release, or has no APK attached. */
    fun parse(body: String): Release? {
        val release = json.decodeFromString<GhRelease>(body)
        if (release.draft || release.prerelease) return null
        val version = versionOf(release.tag) ?: return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        val sha = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        return Release(version, release.page, apk.name, apk.url, apk.size, sha)
    }

    /** `v0.31.0` → `0.31.0`; null for anything that is not a plain x.y.z version. */
    fun versionOf(tag: String): String? = tag.removePrefix("v").takeIf { parts(it) != null }

    /** Whether [candidate] is a later version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current.substringBefore('-')) ?: return true
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun parts(version: String): List<Int>? =
        version.split(".").takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }
}
