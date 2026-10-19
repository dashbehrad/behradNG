package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainConnectionStateTest {
    @Test fun startupOnlyBecomesRunningAfterDaemonSuccess() {
        val initial = MainUiState(selectedGuid = "profile-guid")
        assertFalse(initial.isConnecting)
        val connecting = initial.connectionState(running = false, connecting = true)
        assertTrue(connecting.isConnecting)
        assertFalse(connecting.isRunning)
        assertEquals(MainStatus.Connecting(), connecting.status)
        val running = connecting.connectionState(running = true)
        assertTrue(running.isRunning)
        assertFalse(running.isConnecting)
        assertEquals(MainStatus.Connected, running.status)
        assertEquals("profile-guid", running.selectedGuid)
    }

    @Test fun failureCancellationAndAuthoritativeStoppedSnapshotsClearConnecting() {
        val pending = MainUiState().connectionState(false, connecting = true)
        val failureOrStop = pending.connectionState(false)
        assertFalse(failureOrStop.isConnecting)
        assertFalse(failureOrStop.isRunning)
        assertEquals(MainStatus.Disconnected, failureOrStop.status)
        val stoppedSnapshot = pending.connectionState(false, preserveStatus = true)
        assertFalse(stoppedSnapshot.isConnecting)
        assertEquals(MainStatus.Disconnected, stoppedSnapshot.status)
        assertEquals(failureOrStop, failureOrStop.connectionState(false))
    }

    @Test fun testsCannotHideStartupAndRunningSnapshotsPreserveConnectionResults() {
        val testingDuringStartup = MainUiState().connectionState(false, connecting = true)
            .copy(status = MainStatus.TestProgress("3/5"))
        assertTrue(testingDuringStartup.isConnecting)
        assertFalse(testingDuringStartup.isRunning)
        val result = MainStatus.ConnectionTest(ConnectionTestResult(42))
        val running = MainUiState(isRunning = true, status = result)
        assertEquals(result, running.connectionState(true, preserveStatus = true).status)
        assertEquals(MainStatus.Connected, testingDuringStartup.connectionState(true).status)
        assertThrows(IllegalArgumentException::class.java) { running.connectionState(true, connecting = true) }
    }

    @Test fun connectingStatePreservesAndFormatsBootstrapProgress() {
        val connectingWithProgress = MainUiState().connectionState(false, connecting = true, progress = "45%")
        assertTrue(connectingWithProgress.isConnecting)
        assertFalse(connectingWithProgress.isRunning)
        assertEquals(MainStatus.Connecting("45%"), connectingWithProgress.status)
        assertEquals("45%", (connectingWithProgress.status as MainStatus.Connecting).progress)
    }
}
