package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SaturnSettingsTest {
    private val bridge = "obfs4 192.0.2.1:443 " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"

    @Test fun emptyDefaultsUseAutomaticDiscoveryWhileCustomModeRequiresBridges() {
        assertTrue(SaturnSettings().isValid())
        assertTrue(SaturnSettings().usesAutomaticBridges())
        assertFalse(SaturnSettings(bridgeSource = "custom").isValid())
        assertTrue(SaturnSettings(bridges = bridge).isValid())
        assertFalse(SaturnSettings(bridges = bridge).usesAutomaticBridges())
        assertTrue(SaturnSettings(bridges = bridge, bridgeSource = "auto").usesAutomaticBridges())
        assertFalse(SaturnSettings(bridgeSource = "unknown").isValid())
    }
    @Test fun invalidDohBootstrapModesAndCountriesAreRejected() {
        val good = SaturnSettings(bridges = bridge)
        listOf(good.copy(dohUrl = "http://dns.google/dns-query"), good.copy(dohUrl = "https://user:password@dns.google/dns-query"),
            good.copy(bootstrapIps = "dns.google"), good.copy(bootstrapIps = "999.2.3.4"), good.copy(scanMode = "anything"),
            good.copy(country = "us}\nExitNodes {gb")).forEach { assertFalse(it.isValid()) }
        assertTrue(good.copy(bootstrapIps = "2001:4860:4860::8888", country = "de", scanMode = "deep").isValid())
    }
    @Test fun bridgeParserRejectsInjectionMalformedKeysAndUnboundedInputs() {
        val good = SaturnSettings(bridges = "Bridge $bridge\n$bridge\n")
        assertEquals(listOf(bridge), good.bridgeLines())
        listOf(bridge + "\nSocksPort 0.0.0.0:9050", bridge.replace("cert=", "cert=!!"),
            bridge.replace(":443", ":70000"), bridge.replace("obfs4", "meek"), bridge.replace("iat-mode=0", "iat-mode=9"))
            .forEach { assertFalse(SaturnSettings(bridges = it).isValid()) }
        assertFalse(SaturnSettings(bridges = bridge.repeat(1000)).isValid())
    }
}
