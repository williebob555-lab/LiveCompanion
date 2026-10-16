package com.livecompanion

import com.livecompanion.ui.clip.ClipView
import com.livecompanion.ui.components.TimelineView
import com.livecompanion.ui.components.nameError
import com.livecompanion.update.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA pass 3 fixes: zoom limits, empty-name rejection, "No stable release yet". */
class P3FixTests {
    @Test fun emptyNamesAreRejected() {
        assertNotNull(nameError(""))
        assertNotNull(nameError("   "))
        assertNull(nameError("Bass"))
    }

    @Test fun arrangeZoomLimits() {
        val v = TimelineView()
        v.widthPx = 1000f
        v.setLimits(contentEnd = 32.0, dens = 2f)
        // whole content (+ margin) fills the width at the minimum zoom, never less
        val min = v.minPpb
        assertEquals(1000f / (4f * (32f + 32f)), min, 1e-3f)
        assertEquals(min, v.clampPpb(0.0001f), 1e-6f)
        // maximum: 1/64 note (1/16 beat) is ~24dp wide
        assertEquals(24f * 2f / 0.0625f, v.maxPpb, 1e-3f)
        assertEquals(v.maxPpb, v.clampPpb(1_000_000f), 1e-3f)
        // a tiny clip: max never drops below min
        v.setLimits(contentEnd = 0.1, dens = 1f)
        assertTrue(v.maxPpb >= v.minPpb)
    }

    @Test fun clipZoomLimits() {
        val v = ClipView()
        v.leftW = 100f
        v.width = 1100f
        v.setLimits(span = 8.0, unitsPer64 = 0.0625, dens = 3f)
        assertEquals(1000f / (8f * 4f), v.minPpu, 1e-3f) // clip fills ~1/4 of the width
        assertEquals(24f * 3f / 0.0625f, v.maxPpu, 1e-3f)
        v.zoomX(0.0001f)
        assertEquals(v.minPpu, v.ppu, 1e-3f)
        v.zoomX(1e9f)
        assertEquals(v.maxPpu, v.ppu, 1e-3f)
    }

    @Test fun stableChannelWithOnlyPrereleasesSaysNoStable() {
        val r = UpdateCheck.noRelease(UpdateCheck.Channel.STABLE, anyPrerelease = true)
        assertEquals(UpdateCheck.Result.NoStable(true), r)
        assertEquals(UpdateCheck.Result.NoReleases, UpdateCheck.noRelease(UpdateCheck.Channel.TEST, anyPrerelease = false))
    }
}
