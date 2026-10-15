package com.livecompanion.net

import android.content.Context
import android.net.wifi.WifiManager
import com.livecompanion.state.LiveJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** A LiveCompanion host announced by the UDP beacon (PROTOCOL "Discovery"). */
data class DiscoveredHost(
    val address: String,
    val name: String,
    val wsPort: Int = 9010,
    val helperPort: Int = 9012,
    val set: String = "",
)

object Beacon {
    const val PORT = 9011
    private const val EXPIRE_MS = 4000L

    /** Parse one beacon datagram; null if it is not ours. */
    fun parse(address: String, payload: String): DiscoveredHost? = try {
        val o = LiveJson.parseToJsonElement(payload).jsonObject
        if (o["svc"]?.jsonPrimitive?.contentOrNull != "livecompanion") null
        else DiscoveredHost(
            address = address,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: address,
            wsPort = o["ws"]?.jsonPrimitive?.intOrNull ?: 9010,
            helperPort = o["helper"]?.jsonPrimitive?.intOrNull ?: 9012,
            set = o["set"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    } catch (_: Exception) {
        null
    }

    /**
     * Live list of hosts currently broadcasting. Holds a WifiManager MulticastLock and a UDP socket
     * on [PORT] for as long as it is collected; entries expire after a few seconds of silence.
     */
    fun hosts(context: Context): Flow<List<DiscoveredHost>> = channelFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("livecompanion-beacon")?.apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) { }
        }
        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1000
                bind(InetSocketAddress(PORT))
            }
        } catch (_: Exception) {
            lock?.release()
            send(emptyList())
            awaitClose { }
            return@channelFlow
        }
        val seen = LinkedHashMap<String, Pair<DiscoveredHost, Long>>()
        launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val addr = pkt.address.hostAddress ?: continue
                    val host = parse(addr, String(pkt.data, 0, pkt.length, Charsets.UTF_8)) ?: continue
                    seen["$addr:${host.wsPort}"] = host to System.currentTimeMillis()
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
                val now = System.currentTimeMillis()
                seen.entries.removeAll { now - it.value.second > EXPIRE_MS }
                trySend(seen.values.map { it.first })
            }
        }
        awaitClose {
            socket.close()
            try { lock?.release() } catch (_: Exception) { }
        }
    }.distinctUntilChanged()
}
