package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class SaturnDohSocketTest {
    @Test fun dohSocketCanBeCreatedBeforeConnectingAndThenSendData() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            DoHFragmentingSocketFactory().createSocket().use { client ->
                assertFalse(client.isConnected)
                client.connect(InetSocketAddress("127.0.0.1", listener.localPort), 3000)
                assertTrue(client.isConnected)
                assertTrue(client.isBound)
                assertTrue(client.tcpNoDelay)
                assertEquals(listener.localPort, client.port)
                listener.accept().use { peer ->
                    assertEquals(peer.port, client.localPort)
                    peer.soTimeout = 3000
                    client.getOutputStream().write(42)
                    client.getOutputStream().flush()
                    assertEquals(42, peer.getInputStream().read())
                }
            }
        }
    }
}
