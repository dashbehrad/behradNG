package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SaturnConfigurationTest {
    @Test fun torrcUsesLoopbackManagedObfs4AndExplicitCountryWithoutShellCommands() {
        val bridge = "obfs4 192.0.2.1:443 " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"
        val settings = SaturnSettings(bridges = bridge, country = "de")
        val config = SaturnSession.configuration(settings, File("state"), File("state/geoip"), File("libsaturn-obfs4.so"),
            19050, 19051, listOf(bridge), 1234)
        assertTrue(config.contains("SocksPort 127.0.0.1:19050\n"))
        assertTrue(config.contains("Socks5Proxy 127.0.0.1:19051\n"))
        assertTrue(config.contains("ExitNodes {de}\n"))
        assertTrue(config.contains("UseBridges 1\n"))
        assertTrue(config.contains("__OwningControllerProcess 1234\n"))
        assertTrue(config.contains("Bridge $bridge\n"))
        assertThrows(IllegalArgumentException::class.java) {
            SaturnSession.configuration(settings, File("state"), File("geoip"), File("obfs4"), 0, 19051, listOf(bridge), 1234)
        }
        assertFalse(SaturnSession.configuration(settings.copy(country = "auto"), File("state"), File("geoip"), File("obfs4"),
            19050, 19051, listOf(bridge), 1234).contains("ExitNodes"))
    }
}
