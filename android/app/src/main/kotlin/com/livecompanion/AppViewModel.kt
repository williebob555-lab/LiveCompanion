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
    val client = LiveClient(store, viewModelScope)

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
        client.followPref = if (prefs.followLive) true else null
        viewModelScope.launch {
            client.state.collect { st ->
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
        client.connect(t.host, t.port)
    }

    fun disconnect() {
        prefs.lastHost = null
        client.disconnect()
        _active.value = null
    }

    fun forgetRecent(t: HostTarget) {
        prefs.removeRecent(t)
        _recents.value = prefs.recents()
    }
}
