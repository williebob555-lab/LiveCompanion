package com.livecompanion.net

import com.livecompanion.state.LiveJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Waveform summary (remote script `clip.peaks`, or the helper), channels merged (min of mins, max of maxes). Values -1..1. */
/** [approx] = coarse meter envelope of a clip whose file Live still holds locked (drawn dimmer, re-fetched later). */
class Peaks(val sr: Int, val ch: Int, val frames: Long, val n: Int, val min: FloatArray, val max: FloatArray, val approx: Boolean = false) {
    val seconds: Double get() = if (sr > 0) frames.toDouble() / sr else 0.0
}

sealed class PeaksResult {
    class Ok(val peaks: Peaks) : PeaksResult()
    class Err(val msg: String) : PeaksResult()
}

/** Parse the helper's `/peaks` body `{sr,ch,frames,n,min:[[..]..],max:[[..]..]}`. Throws on malformed input. */
fun parsePeaks(json: String): Peaks {
    val o = LiveJson.parseToJsonElement(json).jsonObject
    if (o["ok"]?.jsonPrimitive?.contentOrNull == "false") throw IllegalArgumentException(o["err"]?.jsonPrimitive?.contentOrNull ?: "helper error")
    fun channels(key: String): List<FloatArray> {
        val a = o[key]?.jsonArray ?: throw IllegalArgumentException("missing $key")
        if (a.isEmpty()) return emptyList()
        // Accept both [[..],[..]] (per channel) and a flat [..].
        return if (a[0] is JsonArray) a.map { c -> FloatArray(c.jsonArray.size) { i -> c.jsonArray[i].jsonPrimitive.floatOrNull ?: 0f } }
        else listOf(FloatArray(a.size) { i -> a[i].jsonPrimitive.floatOrNull ?: 0f })
    }
    val mins = channels("min")
    val maxs = channels("max")
    val n = mins.maxOfOrNull { it.size } ?: 0
    if (n == 0 || maxs.isEmpty()) throw IllegalArgumentException("empty peaks")
    val lo = FloatArray(n) { 0f }
    val hi = FloatArray(n) { 0f }
    for (c in mins) for (i in c.indices) if (c[i] < lo[i]) lo[i] = c[i]
    for (c in maxs) for (i in 0 until minOf(n, c.size)) if (c[i] > hi[i]) hi[i] = c[i]
    return Peaks(
        sr = o["sr"]?.jsonPrimitive?.intOrNull ?: 0,
        ch = o["ch"]?.jsonPrimitive?.intOrNull ?: mins.size,
        frames = o["frames"]?.jsonPrimitive?.longOrNull ?: 0L,
        n = n, min = lo, max = hi,
        approx = o["approx"]?.jsonPrimitive?.contentOrNull == "true",
    )
}

/** `http://host:9012/peaks?path=<encoded>&n=<n>`; HttpUrl handles IPv6 literals and encoding. */
fun peaksUrl(host: String, path: String, n: Int, port: Int = 9012): HttpUrl? = try {
    HttpUrl.Builder().scheme("http").host(host.removePrefix("[").removeSuffix("]")).port(port)
        .addPathSegment("peaks").addQueryParameter("path", path).addQueryParameter("n", n.toString()).build()
} catch (_: Exception) {
    null
}

/**
 * Fetches and memoizes peaks. Never throws: failures come back as [PeaksResult.Err].
 * Order: `clip.peaks` over the Live WebSocket (needs no helper) -> helper HTTP (exotic formats such as mp3/flac).
 */
class PeaksClient(
    /** Test hook: replaces the in-Live `clip.peaks` request (returns the `d` JSON text, throws on `ok:false`). */
    private val liveFetch: (suspend (loc: JsonObject, n: Int) -> String)? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build(),
    private val port: Int = 9012,
) {
    private val cache = object : LinkedHashMap<String, Peaks>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Peaks>?) = size > 24
    }

    /** The connected client used for `clip.peaks`; set by the screens that show waveforms. */
    @Volatile var live: LiveClient? = null

    /** Only REAL (non-approx) results are ever cached; failures never. [ver] = clip length etc., so a re-recorded/trimmed file refetches. */
    @Synchronized fun cached(path: String, n: Int, ver: String = ""): Peaks? = cache["$path|$ver|$n"]

    @Synchronized fun put(path: String, n: Int, p: Peaks, ver: String = "") { if (!p.approx) cache["$path|$ver|$n"] = p }

    /** [loc] = the clip locator (slot/arr/take) for the in-Live path; without it only the helper is tried. */
    suspend fun get(host: String, path: String, n: Int, loc: JsonObject? = null, ver: String = ""): PeaksResult {
        cached(path, n, ver)?.let { return PeaksResult.Ok(it) }
        if (path.isBlank()) return PeaksResult.Err("No file")
        var viaLive: String? = null
        val lc = live
        if ((lc != null || liveFetch != null) && loc != null) {
            try {
                val d = liveFetch?.invoke(loc, n)
                    ?: lc!!.request("clip.peaks", buildJsonObject { put("loc", loc); put("n", n) }, timeoutMs = 20000).toString()
                val p = parsePeaks(d)
                put(path, n, p, ver)
                return PeaksResult.Ok(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                viaLive = e.message ?: "failed"
            }
        }
        if (host.isBlank()) return PeaksResult.Err(viaLive ?: "No host")
        val r = fetchHelper(host, path, n, ver)
        return if (r is PeaksResult.Err && viaLive != null) PeaksResult.Err(viaLive) else r
    }

    private suspend fun fetchHelper(host: String, path: String, n: Int, ver: String): PeaksResult {
        return withContext(Dispatchers.IO) {
            try {
                val url = peaksUrl(host, path, n, port) ?: return@withContext PeaksResult.Err("Bad host")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) {
                        val msg = try { LiveJson.parseToJsonElement(body).jsonObject["err"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                        return@use PeaksResult.Err(msg ?: "HTTP ${r.code}")
                    }
                    val p = parsePeaks(body)
                    put(path, n, p, ver)
                    PeaksResult.Ok(p)
                }
            } catch (e: Exception) {
                PeaksResult.Err(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Keeps asking until a REAL waveform arrives (e.g. Live writes the .asd only at record end): every attempt's outcome
     * (approx, error, final Ok) goes to [onResult]; failures are never cached and never end the loop. Cancel the caller's
     * coroutine to stop. [retryMs] (attempt) = delay before attempt+1 (default 1.5 s, 1.5 s, 3 s, then 5 s).
     */
    suspend fun await(
        host: String, path: String, n: Int, loc: JsonObject?, ver: String = "",
        retryMs: (Int) -> Long = ::defaultRetryMs, onResult: (PeaksResult) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            val r = get(host, path, n, loc, ver)
            onResult(r)
            if (r is PeaksResult.Ok && !r.peaks.approx) return
            kotlinx.coroutines.delay(retryMs(attempt++))
        }
    }

    companion object {
        fun defaultRetryMs(attempt: Int): Long = when (attempt) { 0, 1 -> 1500L; 2 -> 3000L; else -> 5000L }

        val shared = PeaksClient()
    }
}

/** Key for per-clip peaks state: file + clip extent, so a new/longer recording of the same path is a different waveform. */
fun peaksVer(len: Double): String = "%.3f".format(java.util.Locale.ROOT, len)
fun peaksKey(path: String, len: Double): String = path + "|" + peaksVer(len)
