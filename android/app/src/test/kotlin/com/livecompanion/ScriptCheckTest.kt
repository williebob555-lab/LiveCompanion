package com.livecompanion

import com.livecompanion.state.MIN_SCRIPT_VERSION
import com.livecompanion.state.REQUIRED_OPS
import com.livecompanion.state.evaluateScript
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptCheckTest {
    @Test fun oldScriptWithoutReloadIsOutdatedAndNotReloadable() {
        val s = evaluateScript(2, REQUIRED_OPS)
        assertTrue(s.outdated)
        assertFalse(s.canReload)
    }

    @Test fun reloadableScriptThatIsTooOldOffersReload() {
        val s = evaluateScript(MIN_SCRIPT_VERSION - 1, REQUIRED_OPS + "sys.reload")
        assertTrue(s.outdated)
        assertTrue(s.canReload)
    }

    @Test fun currentScriptIsFine() {
        val s = evaluateScript(MIN_SCRIPT_VERSION, REQUIRED_OPS + "sys.reload")
        assertFalse(s.outdated)
    }
}
