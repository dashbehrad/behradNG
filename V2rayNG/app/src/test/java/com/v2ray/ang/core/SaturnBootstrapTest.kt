package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket

class SaturnBootstrapTest {
    @Test fun bootstrapProgressParserAcceptsOnlyTorPercentages() {
        assertEquals(0, SaturnSession.parseBootstrapProgress("Bootstrapped 0%: Starting"))
        assertEquals(70, SaturnSession.parseBootstrapProgress("notice: Bootstrapped 70% (handshake_done)"))
        assertEquals(100, SaturnSession.parseBootstrapProgress("Bootstrapped 100%: Done"))
        assertNull(SaturnSession.parseBootstrapProgress("Bootstrapped 101%: Invalid"))
        assertNull(SaturnSession.parseBootstrapProgress("bridge 192.0.2.1:443"))
    }

    @Test fun readyAndTimedOutBootstrapsHaveDistinctResults(): Unit = runBlocking {
        assertTrue(SaturnSession.awaitBootstrap(1000, { true }, { true }))
        assertFalse(SaturnSession.awaitBootstrap(10, { false }, { true }))
    }

    @Test fun deadCoreIsFailureAndOwnerCancellationIsNotTimeout(): Unit = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SaturnSession.awaitBootstrap(1000, { false }, { false }) }
        }
        val entered = CompletableDeferred<Unit>()
        val pending = async {
            SaturnSession.awaitBootstrap(10000, { false }, { entered.complete(Unit); true })
        }
        entered.await()
        pending.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
    }

    @Test fun bridgeRefreshTimeoutFallsBackToBundledPool(): Unit = runBlocking {
        assertEquals(emptyList<String>(), SaturnSession.discoverBridgesWithTimeout {
            delay(SaturnSession.BRIDGE_REFRESH_TIMEOUT_MILLIS + 100)
            listOf("obfs4 bridge.example:443")
        })
        assertEquals(listOf("obfs4 bridge.example:443"), SaturnSession.discoverBridgesWithTimeout {
            listOf("obfs4 bridge.example:443")
        })
    }

    @Test fun testTunnelSocks5VerifiesTunnelConnectivity(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val job = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    assertEquals(5, input.read())
                    val nmethods = input.read()
                    for (i in 0 until nmethods) input.read()
                    output.write(byteArrayOf(5, 0))
                    output.flush()
                    assertEquals(5, input.read())
                    assertEquals(1, input.read())
                    assertEquals(0, input.read())
                    assertEquals(1, input.read())
                    for (i in 0 until 6) input.read()
                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
            }
            assertTrue(SaturnSession.testTunnelSocks5(server.localPort))
            job.await()
        }
    }

    @Test fun awaitTunnelReadyDetectsPrematureDeath(): Unit = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SaturnSession.awaitTunnelReady(12345, 1000, alive = { false }) }
        }
    }
}
