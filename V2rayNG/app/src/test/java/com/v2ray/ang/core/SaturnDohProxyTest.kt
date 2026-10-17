package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class SaturnDohProxyTest {
    @Test fun localSocksConnectRelaysOnlyTheConfiguredBridgeAndStopClosesListeners(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val echo = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val bridge = "obfs4 127.0.0.1:${echo.localPort} " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"
        val proxy = SaturnDohProxy(SaturnSettings(bridges = bridge), scope)
        val proxyPort = proxy.port
        val echoJob = scope.launch {
            echo.accept().use { peer -> peer.getOutputStream().write(peer.getInputStream().read()) }
        }
        try {
            proxy.start(listOf(bridge))
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3000
                val output = client.getOutputStream()
                val input = DataInputStream(client.getInputStream())
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte()))
                output.flush()
                val reply = ByteArray(10); input.readFully(reply)
                assertEquals(0.toByte(), reply[1])
                output.write(42); output.flush()
                assertEquals(42, input.read())
            }
            withTimeout(3000) { echoJob.join() }
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 3000
                val output = client.getOutputStream()
                val input = DataInputStream(client.getInputStream())
                output.write(byteArrayOf(5, 1, 0)); output.flush()
                input.readUnsignedByte(); input.readUnsignedByte()
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 0, 1)); output.flush()
                assertEquals(-1, input.read())
            }
        } finally { proxy.stop(); echo.close(); scope.cancel() }
        assertThrows(java.io.IOException::class.java) { Socket("127.0.0.1", proxyPort).close() }
    }
}
