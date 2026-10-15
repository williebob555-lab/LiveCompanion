package com.livecompanion

import com.livecompanion.state.LiveJson
import com.livecompanion.state.LiveStore

/** Realistic fake server state, fed through the real section decoder. */
fun fakeStore(): LiveStore {
    val s = LiveStore()
    fun sec(name: String, json: String) = s.handleSection(name, LiveJson.parseToJsonElement(json))
    s.setHello(com.livecompanion.state.HelloInfo(1, "12.4.1", "mock"))
    sec("session", """{"tempo":122.5,"sig":[4,4],"playing":true,"pos":37.5,"rec":false,"session_rec":false,"overdub":true,
        "loop":true,"metronome":true,"can_undo":true,"can_redo":false,"sel_track":"t1","sel_scene":2,"follow":false}""")
    sec("tracks", """{"tracks":[
        {"ref":"t0","name":"1 Drums","color":16724787,"kind":"audio","armable":true,"arm":false,"mute":false,"solo":false,"monitor":1},
        {"ref":"t1","name":"2 Bass with a really long name that must ellipsize","color":3452671,"kind":"midi","armable":true,"arm":true,"mute":false,"solo":false,"monitor":0,"playing_slot":1},
        {"ref":"t2","name":"3 Keys","color":16766720,"kind":"midi","armable":true}],
        "returns":[{"ref":"r0","name":"A Reverb","color":8421504,"kind":"return"}],
        "master":{"ref":"m","name":"Master","color":8421504,"kind":"master"},
        "scenes":[{"i":0,"name":"Intro","color":0},{"i":1,"name":"","color":0},{"i":2,"name":"Drop","color":0},{"i":3,"name":"","color":0},{"i":4,"name":"","color":0},{"i":5,"name":"","color":0}]}""")
    sec("focus", """{"ref":"t1","name":"2 Bass with a really long name that must ellipsize","color":3452671,"kind":"midi","armable":true,"arm":true,"mute":false,"solo":true,"monitor":0,
        "vol":{"v":0.7,"min":0,"max":1,"str":"-3.2 dB"},"pan":{"v":-0.25,"min":-1,"max":1,"str":"25L"},"playing_slot":1}""")
    sec("slots", """{"tr":"t1","slots":[
        {"i":0,"has_clip":true,"has_stop":true,"status":"stopped","clip":{"name":"Bass A","color":3452671,"len":16,"looping":true,"audio":false}},
        {"i":1,"has_clip":true,"has_stop":true,"status":"playing","clip":{"name":"Bass B (long clip name that is far too long to fit)","color":16724787,"len":8,"looping":true}},
        {"i":2,"has_clip":true,"has_stop":true,"status":"triggered","clip":{"name":"Fill","color":16766720,"len":4}},
        {"i":3,"has_clip":false,"has_stop":true,"status":"empty"},
        {"i":4,"has_clip":true,"has_stop":true,"status":"recording","clip":{"name":"Take 3","color":16777215,"len":0,"audio":true}},
        {"i":5,"has_clip":false,"has_stop":false,"status":"empty"}]}""")
    s.handleRt(LiveJson.parseToJsonElement("""{"pos":37.5,"playing":true,"beat_in_bar":1.5,"meters":{"focus":[0.6,0.8],"master":[0.5,0.5]},"slot_pos":{"1":3.0}}"""))
    return s
}
