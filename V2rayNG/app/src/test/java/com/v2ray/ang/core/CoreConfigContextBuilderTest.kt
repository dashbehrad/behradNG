package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.AetherSettings
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoreConfigContextBuilderTest {
    private val entry = ProfileItem.create(EConfigType.AETHER).copy(aetherSettings = AetherSettings(socksPort = "80"))
    private val exit = ProfileItem.create(EConfigType.VLESS).copy(server = "exit.example", serverPort = "443")

    @Test
    fun reversedChainUsesOnlyTheOwnedRuntimePortWithoutChangingStoredProfiles() {
        val resolved = CoreConfigContextBuilder.runtimeProfiles(EConfigType.PROXYCHAIN, listOf(exit, entry), 45123)
        assertEquals(exit, resolved.first())
        assertEquals("127.0.0.1", resolved.last().server)
        assertEquals("45123", resolved.last().aetherSettings?.socksPort)
        assertEquals("80", entry.aetherSettings?.socksPort)
    }

    @Test
    fun unsupportedOrdersGroupsAndMissingPortsAreRejected() {
        listOf(listOf(entry, exit), listOf(entry, entry)).forEach { profiles ->
            assertThrows(IllegalArgumentException::class.java) { CoreConfigContextBuilder.runtimeProfiles(EConfigType.PROXYCHAIN, profiles, 45123) }
        }
        assertThrows(IllegalArgumentException::class.java) { CoreConfigContextBuilder.runtimeProfiles(EConfigType.POLICYGROUP, listOf(entry), 45123) }
        listOf(null, 80, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { CoreConfigContextBuilder.runtimeProfiles(EConfigType.AETHER, listOf(entry), port) }
        }
    }

    @Test
    fun standaloneAetherAndOrdinaryXrayChainsKeepTheirSupportedPaths() {
        assertEquals("45123", CoreConfigContextBuilder.runtimeProfiles(EConfigType.AETHER, listOf(entry), 45123).single().serverPort)
        assertEquals(listOf(exit, exit), CoreConfigContextBuilder.runtimeProfiles(EConfigType.PROXYCHAIN, listOf(exit, exit), null))
    }
}
