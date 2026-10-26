package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SaturnExitTest {
    @Test
    fun exitReadinessRequiresASuccessfulStreamAndRetriesFailures(): Unit = runBlocking {
        var attempts = 0
        assertTrue(SaturnSession.awaitExit(5_000) {
            attempts++
            if (attempts == 1) throw IOException("Exit unavailable")
        })
        assertEquals(2, attempts)
        assertFalse(SaturnSession.awaitExit(20) { throw IOException("No exit in selected country") })
    }

    @Test
    fun cancellationAndDeadProcessesAreNotExitSuccess(): Unit = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SaturnSession.awaitExit(1_000) { error("Aether stopped") } }
        }
        val entered = CompletableDeferred<Unit>()
        val pending = async {
            SaturnSession.awaitExit(5_000) {
                entered.complete(Unit)
                delay(10_000)
            }
        }
        entered.await()
        pending.cancelAndJoin()
        assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
    }

    @Test
    fun socksGreetingAloneIsNotEnoughToPassTheExitProbe(): Unit = runBlocking {
        for (reply in listOf(0, 4)) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                val peer = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        readExitRequest(socket)
                        socket.getOutputStream().apply {
                            write(byteArrayOf(5, reply.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
                            flush()
                        }
                    }
                }
                val failure = runCatching { SaturnSession.probeExit(server.localPort) }.exceptionOrNull()
                if (reply == 0) assertNull(failure) else assertTrue(failure is IOException)
                peer.await()
            }
        }
    }

    @Test
    fun cancellingExitProbeClosesThePendingSocksConnection(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val entered = CompletableDeferred<Unit>()
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    readExitRequest(socket)
                    entered.complete(Unit)
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            val pending = async { SaturnSession.probeExit(server.localPort) }
            withTimeout(5_000) {
                entered.await()
                pending.cancelAndJoin()
                peer.await()
            }
            assertTrue(pending.isCancelled)
        }
    }

    private fun readExitRequest(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        assertEquals(5, input.readUnsignedByte())
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        assertTrue(0.toByte() in methods)
        socket.getOutputStream().apply { write(byteArrayOf(5, 0)); flush() }
        assertEquals(5, input.readUnsignedByte())
        assertEquals(1, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        // ATYP=DOMAIN proves the probe leaves hostname resolution to the exit.
        assertEquals(3, input.readUnsignedByte())
        val host = ByteArray(input.readUnsignedByte()).also(input::readFully)
        assertEquals("cp.cloudflare.com", host.toString(Charsets.US_ASCII))
        assertEquals(80, input.readUnsignedShort())
    }
}
