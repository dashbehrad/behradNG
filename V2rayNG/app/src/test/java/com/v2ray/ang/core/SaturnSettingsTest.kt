package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.SaturnSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SaturnSettingsTest {
    private val bridge = "obfs4 192.0.2.1:443 " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"

    @Test fun defaultsUseH2Carrier() {
        assertEquals("h2", SaturnSettings().normalizedCarrier())
    }

    @Test fun carrierDefaultsToH2AndNormalizesSupportedHttpProtocols() {
        assertEquals("h2", SaturnSettings().normalizedCarrier())
        assertEquals("h2", SaturnSettings(carrier = "h2").normalizedCarrier())
        assertEquals("h3", SaturnSettings(carrier = "h3").normalizedCarrier())
        assertEquals("h2", SaturnSettings(carrier = "http2").normalizedCarrier())
        assertEquals("h3", SaturnSettings(carrier = "http3").normalizedCarrier())
        assertEquals("h3", SaturnSettings(carrier = "HTTP/3").normalizedCarrier())
        assertTrue(SaturnSettings(carrier = "h2").isValid())
        assertTrue(SaturnSettings(carrier = "h3").isValid())
        assertTrue(SaturnSettings(carrier = "http2").isValid())
        assertTrue(SaturnSettings(carrier = "http3").isValid())
        assertFalse(SaturnSettings(carrier = "invalid").isValid())
        assertFalse(SaturnSettings(carrier = "quic").isValid())
    }

    @Test fun directModeNeedsNoBridgesAndRetainsCustomBridgesForSwitchingBack() {
        val direct = SaturnSettings(bridgeSource = "direct")
        assertTrue(direct.isValid())
        assertTrue(direct.usesDirectConnection())
        assertFalse(direct.copy(country = "invalid").isValid())
        assertFalse(direct.usesAutomaticBridges())
        assertFalse(SaturnSettings().usesDirectConnection())
        val withBridges = direct.copy(bridges = bridge)
        assertTrue(withBridges.isValid())
        assertEquals(bridge, withBridges.copy(bridgeSource = "custom").bridges)
        assertTrue(withBridges.copy(bridgeSource = "custom").isValid())
        assertFalse(direct.copy(bridgeSource = "custom").isValid())
    }

    @Test fun directKeywordInCustomBridgesEnablesDirectConnectionWithoutUiChanges() {
        val customDirect = SaturnSettings(bridgeSource = "custom", bridges = "DIRECT")
        assertTrue(customDirect.isValid())
        assertTrue(customDirect.usesDirectConnection())
        assertFalse(customDirect.usesAutomaticBridges())
    }

    @Test fun emptyDefaultsUseAutomaticDiscoveryWhileCustomModeRequiresBridges() {
        assertTrue(SaturnSettings().isValid())
        assertTrue(SaturnSettings().usesAutomaticBridges())
        assertFalse(SaturnSettings(bridgeSource = "custom").isValid())
        assertTrue(SaturnSettings(bridges = bridge).isValid())
        assertFalse(SaturnSettings(bridges = bridge).usesAutomaticBridges())
        assertTrue(SaturnSettings(bridges = bridge, bridgeSource = "auto").usesAutomaticBridges())
        assertFalse(SaturnSettings(bridgeSource = "unknown").isValid())
    }

    @Test fun invalidModesAndCountriesAreRejected() {
        val good = SaturnSettings(bridges = bridge)
        listOf(good.copy(scanMode = "anything"),
            good.copy(country = "us}\nExitNodes {gb")).forEach { assertFalse(it.isValid()) }
        assertTrue(good.copy(country = "de", scanMode = "deep").isValid())
    }

    @Test fun bridgeParserRejectsInjectionMalformedKeysAndUnboundedInputs() {
        val good = SaturnSettings(bridges = "Bridge $bridge\n$bridge\n")
        assertEquals(listOf(bridge), good.bridgeLines())
        listOf(bridge + "\nSocksPort 0.0.0.0:9050", bridge.replace("cert=", "cert=!!"),
            bridge.replace(":443", ":70000"), bridge.replace("obfs4", "meek"), bridge.replace("iat-mode=0", "iat-mode=9"))
            .forEach { assertFalse(SaturnSettings(bridges = it).isValid()) }
        assertFalse(SaturnSettings(bridges = bridge.repeat(1000)).isValid())
    }

    @Test fun aetherAndUrnetworkSettingsSupportProtocolsAndQuickAccounts() {
        val defaultSettings = SaturnSettings()
        assertEquals("masque", defaultSettings.normalizedAetherProtocol())
        assertEquals("h2", defaultSettings.normalizedAetherCarrier())
        assertEquals("web", defaultSettings.normalizedUrMode())
        assertEquals("h2", defaultSettings.normalizedUrCarrier())
        assertEquals("auto", defaultSettings.urLocation)
        assertFalse(defaultSettings.hasUrAccount())

        val customAether = defaultSettings.copy(aetherProtocol = "wg", aetherCarrier = "h3")
        assertTrue(customAether.isValid())
        assertEquals("wg", customAether.normalizedAetherProtocol())
        assertEquals("h3", customAether.normalizedAetherCarrier())

        val withAccount = defaultSettings.copy(
            urAccountId = "ur_1234567890abcdef",
            urAccountToken = "urtk_abcdef1234567890",
            urTermsAgreed = true,
            urMode = "streaming",
            urCarrier = "h3",
            urLocation = "de"
        )
        assertTrue(withAccount.isValid())
        assertTrue(withAccount.hasUrAccount())
        assertEquals("streaming", withAccount.normalizedUrMode())
        assertEquals("h3", withAccount.normalizedUrCarrier())

        val withRealJwt = withAccount.copy(
            urAccountToken = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9." + "A".repeat(200) + "." + "B".repeat(100)
        )
        assertTrue(withRealJwt.isValid())

        // Account without terms agreement is rejected
        assertFalse(withAccount.copy(urTermsAgreed = false).isValid())
        // Invalid aether protocol or carrier rejected
        assertFalse(defaultSettings.copy(aetherProtocol = "invalid").isValid())
        assertFalse(defaultSettings.copy(aetherCarrier = "h1").isValid())
        // Invalid ur mode or carrier rejected
        assertFalse(defaultSettings.copy(urMode = "invalid").isValid())
        assertFalse(defaultSettings.copy(urCarrier = "h1").isValid())
    }
}
