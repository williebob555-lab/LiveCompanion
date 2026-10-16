package com.livecompanion

import com.livecompanion.ui.components.DragThrottle
import com.livecompanion.ui.components.Optimistic
import org.junit.Assert.assertEquals
import org.junit.Test

class OptimisticTest {
    private var now = 0L

    @Test fun throttleSendsLatestAtMostEveryInterval() {
        val sent = ArrayList<Int>()
        val t = DragThrottle<Int>(33, { now }) { sent.add(it) }
        t.offer(1); now = 10; t.offer(2); now = 20; t.offer(3)
        assertEquals(listOf(1), sent)           // intermediates dropped
        now = 40; t.poll()
        assertEquals(listOf(1, 3), sent)        // latest wins
        now = 45; t.offer(4); t.flush()
        assertEquals(listOf(1, 3, 4), sent)     // flush delivers the final value
        t.sendNow(9)
        assertEquals(9, sent.last())
    }

    @Test fun rendersLocalWhileDraggingAndAfterReleaseUntilServerMatches() {
        now = 0
        val o = Optimistic<Float>(600, { now })
        o.begin(0.2f); o.move(0.7f)
        assertEquals(0.7f, o.display(0.2f), 0f)         // server echo ignored while dragging
        o.release()
        now = 100
        assertEquals(0.7f, o.display(0.5f), 0f)          // no snap-back with a stale server value
        assertEquals(0.7f, o.display(0.7f), 0f)          // matched
        o.settle(0.7f)
        assertEquals(0.9f, o.display(0.9f), 0f)          // local dropped: server drives again
    }

    @Test fun settleTimesOut() {
        now = 0
        val o = Optimistic<Float>(600, { now })
        o.begin(1f); o.release()
        now = 599
        assertEquals(1f, o.display(0f), 0f)
        now = 601
        assertEquals(0f, o.display(0f), 0f)              // server never matched: give up after the timeout
        o.settle(0f)
        assertEquals(0.3f, o.display(0.3f), 0f)
    }
}
