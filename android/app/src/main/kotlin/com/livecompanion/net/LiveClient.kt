package com.livecompanion.net

import com.livecompanion.state.LiveJson
import com.livecompanion.state.sanitizeJson
import com.livecompanion.state.LiveStore
import com.livecompanion.state.HelloInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class HostTarget(val host: String, val port: Int) {
    val label get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
}

enum class ConnStatus { IDLE, CONNECTING, CONNECTED, RECONNECTING }

data class ConnState(
    val status: ConnStatus = ConnStatus.IDLE,
    val target: HostTarget? = null,
    /** Consecutive failed attempts since the last good connection. */
    val attempt: Int = 0,
    val lastError: String? = null,
)

/** A `res` with ok:false. */
class LiveException(message: String, val data: JsonElement? = null) : Exception(message)

/**
 * WebSocket client for the LiveCompanion protocol (docs/PROTOCOL.md).
 *
 * - [request] is a suspending req/res with unique ids and a timeout.
 * - [send] / [op] are fire-and-forget (errors become store notices).
 * - Pings every 3 s; the connection is dropped after 10 s of silence.
 * - Reconnects with backoff (0.5, 1, 2, 4, 5 s ...) and fully resyncs: the server re-sends every
 *   global section after `hello`; this client then restores focus, follow and the open clip.
 */
open class LiveClient(
    val store: LiveStore,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // RFC 6455 control-frame ping: every compliant server answers it, even one that ignores our JSON ping.
        .pingInterval(10, TimeUnit.SECONDS)
        .build(),
    private val helloTimeoutMs: Long = HELLO_TIMEOUT_MS,
    private val pingMs: Long = PING_MS,
    private val silenceMs: Long = SILENCE_MS,
) {
    protected val _state = MutableStateFlow(ConnState())
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private var loopJob: Job? = null
    @Volatile private var socket: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /** Last focused track ref, kept across reconnects to restore focus. */
    @Volatile private var lastFocus: String = ""

    /** Desired follow-Live-selection state, re-applied after every reconnect. null = leave server default. */
    @Volatile var followPref: Boolean? = null

    // ---------------------------------------------------------------- connection
    open fun connect(host: String, port: Int) {
        stopLoop()
        store.reset()
        lastFocus = ""
        val target = HostTarget(host.trim(), port)
        _state.value = ConnState(ConnStatus.CONNECTING, target)
        loopJob = scope.launch(Dispatchers.IO) { runLoop(target) }
    }

    open fun disconnect() {
        stopLoop()
        store.reset()
        lastFocus = ""
        _state.value = ConnState()
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        socket?.cancel()
        socket = null
        failPending("Disconnected")
    }

    /**
     * First attempt: a single try. If it never produces a `hello` (refused, DNS, timeout, not our protocol) the state
     * goes to IDLE with [ConnState.lastError] and the loop ends - the UI stays on the Connect screen.
     * Once a hello has been seen, drops are retried forever with backoff (auto-reconnect).
     */
    private suspend fun runLoop(target: HostTarget) {
        var attempt = 0
        var established = false
        while (currentCoroutineContext().isActive) {
            _state.value = ConnState(
                if (attempt == 0) ConnStatus.CONNECTING else ConnStatus.RECONNECTING,
                target, attempt, _state.value.lastError,
            )
            val closed = CompletableDeferred<String?>()
            val lastRx = AtomicLong(System.nanoTime())
            val gotHello = AtomicBoolean(false)
            val pongSeen = AtomicBoolean(false)
            val noHello = AtomicBoolean(false)
            val hostPart = if (target.host.contains(':') && !target.host.startsWith("[")) "[${target.host}]" else target.host
            val ws = try {
                http.newWebSocket(
                    Request.Builder().url("http://$hostPart:${target.port}/").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            lastRx.set(System.nanoTime())
                            try {
                                val msg = LiveJson.parseToJsonElement(text).jsonObject
                                when (msg["t"]?.jsonPrimitive?.contentOrNull) {
                                    "hello" -> gotHello.set(true)
                                    "pong" -> pongSeen.set(true)
                                }
                                dispatch(sanitizeJson(msg) as JsonObject)
                            } catch (e: Exception) {
                                // Junk or hostile payload: ignore the message, never drop the connection over it.
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            closed.complete(reason.ifBlank { null })
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            closed.complete(t.message ?: t.javaClass.simpleName)
                        }
                    },
                )
            } catch (e: Exception) {
                // Malformed host string etc. Nothing to retry.
                _state.value = ConnState(ConnStatus.IDLE, target, attempt, e.message ?: "Invalid address")
                return
            }
            socket = ws
            val helloWatch = scope.launch(Dispatchers.IO) {
                delay(helloTimeoutMs)
                if (!gotHello.get()) {
                    noHello.set(true)
                    ws.cancel() // -> onFailure -> closed
                }
            }
            val pinger = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(pingMs)
                    if (!ws.send(PING)) break
                    // Only a server that has shown it answers JSON pings is held to the silence limit; a minimal
                    // server (hello + sections only) is kept alive by the WebSocket-level ping instead.
                    if (pongSeen.get() && System.nanoTime() - lastRx.get() > silenceMs * 1_000_000L) {
                        ws.cancel() // -> onFailure -> closed
                        break
                    }
                }
            }
            var reason: String? = try {
                closed.await()
            } finally {
                helloWatch.cancel()
                pinger.cancel()
                ws.cancel()
                socket = null
                failPending("Connection lost")
            }
            if (!currentCoroutineContext().isActive) return
            if (gotHello.get()) { attempt = 0; established = true }
            if (noHello.get()) reason = "No hello from host within ${helloTimeoutMs / 1000} s - is it LiveCompanion?"
            if (!established) {
                _state.value = ConnState(ConnStatus.IDLE, target, 1, friendlyError(reason, target))
                return
            }
            _state.value = ConnState(ConnStatus.RECONNECTING, target, attempt + 1, reason)
            delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
            attempt++
        }
    }

    private fun friendlyError(raw: String?, target: HostTarget): String {
        val r = raw ?: "no response"
        val why = when {
            r.startsWith("No hello") -> r
            r.contains("refused", true) || r.contains("Failed to connect", true) || r.contains("ECONNREFUSED", true) -> "connection refused (is Live running with the LiveCompanion control surface?)"
            r.contains("Unable to resolve host", true) || r.contains("UnknownHost", true) || r.contains("No address associated", true) ->
                "unknown host name"
            r.contains("timeout", true) || r.contains("timed out", true) -> "timed out"
            r.contains("unreachable", true) -> "network unreachable"
            else -> r.take(120)
        }
        return "Could not connect to ${target.label}: $why"
    }

    private fun dispatch(msg: JsonObject) {
        when (msg["t"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> {
                val h = try {
                    LiveJson.decodeFromJsonElement(HelloInfo.serializer(), msg)
                } catch (_: Exception) {
                    HelloInfo()
                }
                store.setHello(h)
                val cur = _state.value
                _state.value = ConnState(ConnStatus.CONNECTED, cur.target, 0, null)
                resync()
                scope.launch { checkScript() }
            }
            "res" -> {
                val id = msg["id"]?.jsonPrimitive?.intOrNull ?: return
                pending.remove(id)?.complete(msg)
            }
            "sec" -> {
                val name = msg["s"]?.jsonPrimitive?.contentOrNull ?: return
                val d = msg["d"] ?: return
                if (name == "focus") (d as? JsonObject)?.get("ref")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) lastFocus = it }
                store.handleSection(name, d)
                if (name == "tracks" && lastFocus.isEmpty()) autoFocus()
            }
            "rt" -> msg["d"]?.let { store.handleRt(it) }
            "log" -> {
                val lvl = msg["lvl"]?.jsonPrimitive?.contentOrNull
                val m = msg["msg"]?.jsonPrimitive?.contentOrNull ?: return
                store.notice(if (lvl == null || lvl == "info") m else "$lvl: $m")
            }
            "pong" -> Unit
        }
    }

    /** First connect: focus Live's selected track, else the first track, so the app never opens on "No track selected". */
    private fun autoFocus() {
        val t = store.tracks.value
        val ref = t.find(store.session.value.selTrack)?.ref ?: t.tracks.firstOrNull()?.ref ?: t.all.firstOrNull()?.ref ?: return
        store.focusPending(ref)
        op("focus.set", "tr" to ref)
    }

    /** After (re)connect: server already re-sent global sections; restore this client's own state. */
    private fun resync() {
        val focus = lastFocus
        if (focus.isNotEmpty()) op("focus.set", "tr" to focus)
        followPref?.let { op("focus.follow", "on" to it) }
        store.clip.value.loc?.let { op("clip.open", "loc" to it) }
    }

    /** After hello: compare Live's script (sys.info ops + script_version) with what this app needs. */
    private suspend fun checkScript() {
        try {
            val d = request("sys.info", timeoutMs = 4000).jsonObject
            val ops = (d["ops"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            store.setScript(com.livecompanion.state.evaluateScript(d["script_version"]?.jsonPrimitive?.intOrNull, ops))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LiveException) {
            // No sys.info at all = ancient script; a timeout or a minimal test server proves nothing.
            if (e.message?.startsWith("Needs script update") == true) store.setScript(com.livecompanion.state.ScriptStatus(true, null, listOf("sys.info")))
        } catch (_: Exception) {
        }
    }

    private fun failPending(why: String) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            it.remove()
            e.value.complete(buildJsonObject { put("ok", false); put("err", why) })
        }
    }

    // ---------------------------------------------------------------- requests
    /** Sends a request and suspends for the reply. Throws [LiveException] on `ok:false`, timeout or no connection. */
    open suspend fun request(op: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 8000): JsonElement {
        if (SimDelay.ms > 0) delay(SimDelay.ms)
        val ws = socket ?: throw LiveException("Not connected")
        val id = nextId.getAndIncrement()
        val def = CompletableDeferred<JsonObject>()
        pending[id] = def
        val payload = buildJsonObject {
            put("t", "req"); put("id", id); put("op", op); put("a", args)
        }.toString()
        if (!ws.send(payload)) {
            pending.remove(id)
            throw LiveException("Not connected")
        }
        val res = try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw LiveException("Timed out: $op")
        } catch (e: kotlinx.coroutines.CancellationException) {
            pending.remove(id)
            throw e
        }
        val ok = res["ok"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!ok) {
            val err = res["err"]?.jsonPrimitive?.contentOrNull ?: "Request failed: $op"
            // An older remote script in Live does not know newer ops: say so instead of a bare "unknown op".
            if (err.startsWith("unknown op")) throw LiveException("Needs script update ($op) - reinstall the LiveCompanion script and restart Live")
            throw LiveException(err, res["d"])
        }
        return res["d"] ?: JsonNull
    }

    /** Like [request] but returns null instead of throwing (error goes to notices when [notify]). */
    suspend fun requestOrNull(op: String, args: JsonObject = JsonObject(emptyMap()), notify: Boolean = true): JsonElement? =
        try {
            request(op, args)
        } catch (e: LiveException) {
            if (notify) store.notice("${e.message}")
            null
        }

    /** Fire-and-forget. Failures surface as store notices. */
    fun send(op: String, args: JsonObject = JsonObject(emptyMap()), onFail: (() -> Unit)? = null) {
        if (op == "focus.set") args["tr"]?.jsonPrimitive?.contentOrNull?.let { lastFocus = it }
        scope.launch {
            try {
                request(op, args)
            } catch (e: LiveException) {
                store.notice(e.message ?: op)
                onFail?.invoke()
            }
        }
    }

    /** Fire-and-forget with args as pairs; see [jsonArgs]. */
    fun op(op: String, vararg args: Pair<String, Any?>) = send(op, jsonArgs(*args))

    private val throttle = ConcurrentHashMap<String, Pair<String, JsonObject>>()
    private val throttleRunning = ConcurrentHashMap<String, Boolean>()

    /**
     * For continuous controls (faders/knobs): at most one request per [intervalMs] per [key], and the
     * final value is always sent.
     */
    fun sendThrottled(key: String, op: String, args: JsonObject, intervalMs: Long = 33) {
        throttle[key] = op to args
        if (throttleRunning.putIfAbsent(key, true) != null) return
        scope.launch {
            try {
                while (true) {
                    val (o, a) = throttle.remove(key) ?: break
                    send(o, a)
                    delay(intervalMs)
                }
            } finally {
                throttleRunning.remove(key)
                // A value that raced in after the loop ended.
                throttle.remove(key)?.let { (o, a) -> sendThrottled(key, o, a, intervalMs) }
            }
        }
    }

    fun opThrottled(key: String, op: String, vararg args: Pair<String, Any?>) =
        sendThrottled(key, op, jsonArgs(*args))

    companion object {
        private const val PING = """{"t":"ping"}"""
        private const val PING_MS = 3000L
        private const val SILENCE_MS = 10_000L
        const val HELLO_TIMEOUT_MS = 5000L
        private val BACKOFF_MS = longArrayOf(500, 1000, 2000, 4000, 5000)
    }
}

/** Build request args: supports String, Boolean, Number, JsonElement and null values. */
fun jsonArgs(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) {
        when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Int -> put(k, v)
            is Long -> put(k, v)
            is Float -> put(k, v)
            is Double -> put(k, v)
            is JsonElement -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { x ->
                when (x) {
                    is String -> JsonPrimitive(x)
                    is Int -> JsonPrimitive(x)
                    is Double -> JsonPrimitive(x)
                    is Float -> JsonPrimitive(x)
                    is JsonElement -> x
                    else -> JsonNull
                }
            }))
            else -> put(k, v.toString())
        }
    }
}
