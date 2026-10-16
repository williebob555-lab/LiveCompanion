package com.livecompanion.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finding out whether a newer LiveCompanion has been published, and fetching it.
 * Mirrors InkSlate's UpdateCheck (GitHub Releases): stable channel = non-prerelease releases,
 * test channel = prereleases too (published on the moving `test` tag whose release title carries
 * the version). Pure JVM; every call blocks, callers use a background thread.
 */
object UpdateCheck {
    const val OWNER = "williebob555-lab"
    const val REPO = "LiveCompanion"
    const val APP = "LiveCompanion"
    const val PROJECT_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$PROJECT_URL/releases"
    const val TEST_TAG = "test"

    private const val ALL_RELEASES_API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=40"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    private val json = Json { ignoreUnknownKeys = true }

    enum class Channel(val label: String) {
        STABLE("Stable releases"),
        TEST("Test builds as well"),
    }

    sealed interface Result {
        data class Available(val release: Release, val download: Asset) : Result
        data class AvailableWithoutDownload(val release: Release) : Result
        data class UpToDate(val installed: Version) : Result
        /** The stable channel has no release at all; [testAvailable] = pre-releases exist (switch to the Test channel). */
        data class NoStable(val testAvailable: Boolean) : Result
        /** Nothing has been published for this channel yet. */
        data object NoReleases : Result
        data class Failed(val message: String) : Result
    }

    data class Release(val version: Version, val title: String, val notes: String, val pageUrl: String, val assets: List<Asset>)
    data class Asset(val name: String, val url: String, val sizeBytes: Long)

    /** Release tags that belong to this app: `v1.2.3` style versions and the moving `test` tag. */
    fun tagIsFor(tag: String): Boolean = tag == TEST_TAG || Version.parse(tag) != null

    /** Never throws; problems come back as [Result.Failed] with a sentence to show. */
    fun check(installedVersion: String, channel: Channel = Channel.STABLE): Result {
        val installed = Version.parse(installedVersion)
            ?: return Result.Failed("This build has no version number to compare against.")
        // A test build always follows test builds, whatever the channel switch says; otherwise it could
        // never be offered the next test build.
        val channel = if (installed.preRelease != null) Channel.TEST else channel

        val body = try {
            fetchJson(ALL_RELEASES_API)
        } catch (e: RateLimited) {
            return runCatching { fromPages(installed, channel) }.getOrNull() ?: Result.Failed(describe(e))
        } catch (e: Exception) {
            return Result.Failed(describe(e))
        }

        val release = try {
            newestOf(parseReleases(body, channel))
                ?: return noRelease(channel, parseReleases(body, Channel.TEST).isNotEmpty())
        } catch (e: Exception) {
            return Result.Failed("GitHub sent back something unexpected.")
        }

        if (release.version <= installed) return Result.UpToDate(installed)
        val download = pickAsset(release) ?: return Result.AvailableWithoutDownload(release)
        return Result.Available(release, download)
    }

    /** Nothing newer or older exists for [channel]: say so instead of a misleading "up to date". */
    internal fun noRelease(channel: Channel, anyPrerelease: Boolean): Result =
        if (channel == Channel.STABLE) Result.NoStable(anyPrerelease) else Result.NoReleases

    /** The APK to download; prefers one whose name contains the release version. */
    internal fun pickAsset(release: Release): Asset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        return apks.firstOrNull { it.name.contains(release.version.toString(), ignoreCase = true) }
            ?: apks.maxByOrNull { it.name }
    }

    private fun versionOf(dto: ReleaseDto): Version? =
        Version.parse(dto.tagName) ?: dto.name?.let { Version.findIn(it) }

    internal fun parseReleases(body: String, channel: Channel = Channel.TEST): List<Release> =
        json.decodeFromString<List<ReleaseDto>>(body).filter { dto ->
            tagIsFor(dto.tagName) && !dto.draft && (channel == Channel.TEST || !dto.prerelease)
        }.mapNotNull { dto ->
            versionOf(dto)?.let { version ->
                Release(
                    version = version,
                    title = dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName,
                    notes = dto.body.orEmpty().trim(),
                    pageUrl = dto.htmlUrl ?: RELEASES_URL,
                    assets = dto.assets.map { Asset(it.name, it.downloadUrl, it.size) },
                )
            }
        }

    /** Newest by version, not by the order GitHub listed them. */
    internal fun newestOf(releases: List<Release>): Release? = releases.maxByOrNull { it.version }

    private fun fetchJson(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", APP)
        }
        try {
            when (val code = c.responseCode) {
                200 -> return c.inputStream.bufferedReader().use { it.readText() }
                404 -> throw IOException("No releases have been published yet.")
                403, 429 -> throw RateLimited()
                else -> throw IOException("GitHub replied $code.")
            }
        } finally {
            c.disconnect()
        }
    }

    private class RateLimited : IOException("GitHub is busy answering this connection - try again in a while.")

    /** API-free fallback: `releases/latest` redirect gives the stable tag; the test tag's page gives its title. */
    internal fun fromPages(installed: Version, channel: Channel): Result? {
        val found = ArrayList<Pair<String, Version>>()
        latestTag()?.takeIf { tagIsFor(it) }?.let { tag -> Version.parse(tag)?.let { found += tag to it } }
        if (channel == Channel.TEST) testVersion()?.let { found += TEST_TAG to it }
        val (tag, version) = found.maxByOrNull { it.second } ?: return null
        if (version <= installed) return Result.UpToDate(installed)
        val page = "$RELEASES_URL/tag/$tag"
        val name = "$APP-$version.apk"
        val url = "$PROJECT_URL/releases/download/$tag/$name"
        if (exists(url)) {
            val asset = Asset(name, url, 0)
            return Result.Available(Release(version, if (tag == TEST_TAG) "Test build $version" else tag, "", page, listOf(asset)), asset)
        }
        return Result.AvailableWithoutDownload(Release(version, tag, "", page, emptyList()))
    }

    private fun latestTag(): String? {
        val c = (URL("$RELEASES_URL/latest").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", APP)
        }
        return try {
            c.responseCode
            c.getHeaderField("Location")?.substringAfterLast("/tag/")?.takeIf { it.isNotBlank() }
        } finally {
            c.disconnect()
        }
    }

    private fun testVersion(): Version? {
        val c = (URL("$RELEASES_URL/tag/$TEST_TAG").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", APP)
        }
        return try {
            if (c.responseCode != 200) null
            else Regex("<title>([^<]*)</title>").find(c.inputStream.bufferedReader().use { it.readText() })
                ?.groupValues?.get(1)?.let { Version.findIn(it) }
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun exists(url: String): Boolean {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", APP)
        }
        return try { c.responseCode in 200..299 } catch (_: Exception) { false } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- downloading
    /** Downloads [asset] to [destination] (via a .part file). Progress is 0..1, or -1 when the size is unknown. */
    fun download(
        asset: Asset,
        destination: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): File {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        val c = (URL(asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", APP)
        }
        try {
            if (c.responseCode !in 200..299) throw IOException("The download failed (${c.responseCode}).")
            val total = if (asset.sizeBytes > 0) asset.sizeBytes else c.contentLengthLong
            c.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        if (!shouldContinue()) throw IOException("Download cancelled.")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(if (total > 0) written.toFloat() / total else -1f)
                    }
                }
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            c.disconnect()
        }
        destination.delete()
        if (!partial.renameTo(destination)) {
            partial.delete()
            throw IOException("Could not put the download in place.")
        }
        onProgress(1f)
        return destination
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection."
        is java.net.SocketTimeoutException -> "GitHub did not answer in time."
        else -> e.message ?: "The check could not be completed."
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        val size: Long = 0,
    )
}

/** Dotted version with optional pre-release suffix ("1.2.3", "1.0.0-test.4"). */
data class Version(val parts: List<Int>, val preRelease: String? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val diff = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i) ?: return -1
            val r = right.getOrNull(i) ?: return 1
            val ln = l.toIntOrNull()
            val rn = r.toIntOrNull()
            val diff = when {
                ln != null && rn != null -> ln.compareTo(rn)
                ln != null -> -1
                rn != null -> 1
                else -> naturalCompare(l, r)
            }
            if (diff != 0) return diff
        }
        return 0
    }

    /** "test9" < "test10": compares runs of digits as numbers, everything else as text. */
    private fun naturalCompare(a: String, b: String): Int {
        val chunk = Regex("\\d+|\\D+")
        val ca = chunk.findAll(a).map { it.value }.toList()
        val cb = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val xn = x.toBigIntegerOrNull()
            val yn = y.toBigIntegerOrNull()
            val diff = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
            if (diff != 0) return diff
        }
        return ca.size.compareTo(cb.size)
    }

    override fun toString(): String = parts.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        fun findIn(text: String): Version? = text.split(' ', '\t', '\n', ',', '(', ')')
            .asSequence()
            .filter { it.contains('.') }
            .mapNotNull { word -> parse(word)?.takeIf { it.parts.size >= 2 } }
            .firstOrNull()

        fun parse(raw: String): Version? {
            var text = raw.trim()
            if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
            val dash = text.indexOf('-')
            val preRelease = if (dash >= 0) text.substring(dash + 1).takeIf { it.isNotEmpty() } else null
            if (dash >= 0) text = text.substring(0, dash)
            val parts = text.split(".").map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            }
            return if (parts.isEmpty()) null else Version(parts, preRelease)
        }
    }
}
