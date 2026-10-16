package com.livecompanion

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.livecompanion.data.Prefs
import com.livecompanion.net.Beacon
import com.livecompanion.net.DiscoveredHost
import com.livecompanion.net.HostTarget
import com.livecompanion.net.ConnStatus
import com.livecompanion.net.LiveClient
import com.livecompanion.net.DemoClient
import com.livecompanion.state.LiveStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Owns the store + client so the connection survives rotation. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val store = LiveStore()
    val liveClient = LiveClient(store, viewModelScope)
    val demoClient = DemoClient(store, viewModelScope, { name ->
        runCatching { app.assets.open("demo/$name.json").bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
    })
    private val _demo = MutableStateFlow(false)
    /** True while the built-in demo set is shown instead of a real Live connection. */
    val demo: StateFlow<Boolean> = _demo.asStateFlow()
    /** The client the UI should talk to right now. */
    val client: LiveClient get() = if (_demo.value) demoClient else liveClient

    private val _recents = MutableStateFlow(prefs.recents())
    val recents: StateFlow<List<HostTarget>> = _recents.asStateFlow()

    /**
     * Non-null once a connect attempt has produced a hello (Main shell shown, even while reconnecting later).
     * Until then the Connect screen stays up and shows "Connecting... Cancel" / the failure.
     */
    private val _active = MutableStateFlow<HostTarget?>(null)
    val active: StateFlow<HostTarget?> = _active.asStateFlow()

    /** Live beacon list; only collects (and holds the multicast lock) while the Connect screen observes it. */
    val discovered: StateFlow<List<DiscoveredHost>> =
        Beacon.hosts(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), emptyList())

    init {
        liveClient.followPref = if (prefs.followLive) true else null
        demoClient.followPref = null
        viewModelScope.launch {
            liveClient.state.collect { st ->
                val t = st.target
                if (st.status == ConnStatus.CONNECTED && t != null && _active.value == null) {
                    // Only a successful hello makes a host "known".
                    prefs.lastHost = t
                    prefs.addRecent(t)
                    _recents.value = prefs.recents()
                    _active.value = t
                }
            }
        }
        // Reconnect to the last host on launch; the Connect screen shows "Connecting..." until the hello arrives.
        prefs.lastHost?.let { connect(it) }
    }

    fun connect(t: HostTarget) {
        liveClient.connect(t.host, t.port)
    }

    /** Opens the main shell on the built-in fake set; nothing touches the network. */
    fun startDemo() {
        liveClient.disconnect()
        demoClient.connect("demo", 0)
        _demo.value = true
        _active.value = DemoClient.DEMO_TARGET
    }

    fun disconnect() {
        if (_demo.value) {
            demoClient.disconnect()
            _demo.value = false
        } else {
            prefs.lastHost = null
        }
        liveClient.disconnect()
        _active.value = null
    }

    fun forgetRecent(t: HostTarget) {
        prefs.removeRecent(t)
        _recents.value = prefs.recents()
    }
}
