package com.livecompanion

import com.livecompanion.ui.clip.clipZoomOutPpu
import com.livecompanion.ui.components.TimelineView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomLimitsTest {
    @Test fun trackViewWholeSongFitsInAQuarterOfTheWidth() {
        val p = TimelineView.zoomOutPpb(1000f, 128.0, 4.0)       // 128 beats + 8 bars (32) = 160 beats
        assertEquals(1000f / (4 * 160f), p, 1e-4f)
        // at the limit the song (+8 bars) spans ~1/4 of the width
        assertEquals(250f, p * 160f, 0.5f)
    }

    @Test fun trackViewNeverBelowTwoPixelsPerBar() {
        val p = TimelineView.zoomOutPpb(400f, 100_000.0, 4.0)
        assertEquals(2f / 4f, p, 1e-6f)
        assertTrue(p * 4 >= 2f - 1e-4f)
    }

    @Test fun trackViewLimitsApplyToClamp() {
        val v = TimelineView()
        v.widthPx = 1000f
        v.setLimits(128.0, 2f, 4.0)
        assertEquals(1000f / 640f, v.minPpb, 1e-4f)
        assertEquals(v.minPpb, v.clampPpb(0.0001f), 1e-6f)
        assertTrue(v.maxPpb > 100f) // zoom-in limit kept
    }

    @Test fun clipEditorClipFillsAQuarter() {
        val ppu = clipZoomOutPpu(800f, 8.0)
        assertEquals(200f, ppu * 8f, 0.1f)
    }
}
