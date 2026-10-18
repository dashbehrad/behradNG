package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NetworkMonitorTest {
    @Test fun firstNetworkAndDuplicateInitialPropertiesDoNotRestartTheTunnel() {
        val state = NetworkMonitor.HandoverState<Int>()
        assertNull(state.available(1))
        assertNull(state.available(1))
        assertNull(state.propertiesChanged(1, setOf("address"), setOf("dns")))
        assertNull(state.propertiesChanged(1, setOf("address"), setOf("dns")))
        assertTrue(state.isCurrent(1))
    }

    @Test fun losingTheOldNetworkDoesNotClearTheReplacementNetwork() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        val pending = requireNotNull(state.available(2))
        assertFalse(state.lost(1))
        assertFalse(state.isCurrent(1))
        assertTrue(state.isCurrent(2))
        assertTrue(state.canHandover(pending))
    }

    @Test fun restoredNetworkRestartsEvenWhenItHasTheSameIdentity() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        assertTrue(state.lost(1))
        assertFalse(state.lost(1))
        assertTrue(state.canHandover(requireNotNull(state.available(1))))
    }

    @Test fun rapidSwitchesInvalidateThePreviousPendingHandover() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        val wifi = requireNotNull(state.available(2))
        val cellular = requireNotNull(state.available(3))
        assertFalse(state.canHandover(wifi))
        assertTrue(state.canHandover(cellular))
        assertNull(state.available(3))
        assertTrue(state.canHandover(cellular))
    }

    @Test fun losingThePendingNetworkCancelsHandoverUntilAnotherNetworkArrives() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        val pending = requireNotNull(state.available(2))
        assertTrue(state.lost(2))
        assertFalse(state.canHandover(pending))
        assertTrue(state.canHandover(requireNotNull(state.available(3))))
    }

    @Test fun ipOrDnsChangesRestartOnlyTheCurrentNetworkAndCoalesceOlderChanges() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        assertNull(state.propertiesChanged(1, setOf("old-ip"), setOf("old-dns")))
        val ipChange = requireNotNull(state.propertiesChanged(1, setOf("new-ip"), setOf("old-dns")))
        val dnsChange = requireNotNull(state.propertiesChanged(1, setOf("new-ip"), setOf("new-dns")))
        assertFalse(state.canHandover(ipChange))
        assertTrue(state.canHandover(dnsChange))
        assertNull(state.propertiesChanged(2, setOf("other-ip"), setOf("other-dns")))
        assertTrue(state.canHandover(dnsChange))
    }

    @Test fun initialPropertiesOfAReplacementDoNotInvalidateItsScheduledHandover() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        state.propertiesChanged(1, setOf("old-ip"), setOf("old-dns"))
        val pending = requireNotNull(state.available(2))
        assertNull(state.propertiesChanged(2, setOf("new-ip"), setOf("new-dns")))
        assertTrue(state.canHandover(pending))
    }

    @Test fun stopInvalidatesQueuedHandoverAndEveryLaterCallback() {
        val state = NetworkMonitor.HandoverState<Int>()
        state.available(1)
        val pending = requireNotNull(state.available(2))
        state.close()
        state.close()
        assertTrue(state.closed)
        assertFalse(state.canHandover(pending))
        assertFalse(state.isCurrent(2))
        assertFalse(state.lost(2))
        assertNull(state.available(3))
        assertNull(state.propertiesChanged(3, setOf("ip"), setOf("dns")))
    }
}
