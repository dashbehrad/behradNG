package com.v2ray.ang.core

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProfileItemChainTest {
    private val profiles = mapOf(
        "aether-guid" to ProfileItem.create(EConfigType.AETHER).copy(remarks = "entry"),
        "xray-guid" to ProfileItem.create(EConfigType.VLESS).copy(remarks = "exit"),
        "other-guid" to ProfileItem.create(EConfigType.TROJAN).copy(remarks = "other"),
    )

    @Test
    fun precedingRemarkBasedJsonResolvesWithoutChangingItsPersistedFields() {
        val preceding = """{"configType":"PROXYCHAIN","remarks":"old chain","proxyChainProfiles":"entry,exit"}"""
        val chain = Gson().fromJson(preceding, ProfileItem::class.java)
        assertEquals(listOf("aether-guid", "xray-guid"), chain.chainMemberGuids(profiles))
        assertEquals("aether-guid", chain.aetherEntryGuid(profiles))
        assertEquals("entry,exit", chain.proxyChainProfiles)
        assertNull(chain.proxyChainProfileGuids)
    }

    @Test
    fun guidBasedChainSurvivesRenamesAndRoundTrips() {
        val chain = ProfileItem.create(EConfigType.PROXYCHAIN).copy(
            proxyChainProfiles = "entry,exit", proxyChainProfileGuids = listOf("aether-guid", "xray-guid"))
        val restored = Gson().fromJson(Gson().toJson(chain), ProfileItem::class.java)
        val renamed = profiles.mapValues { (_, profile) -> profile.copy(remarks = "renamed") }
        assertEquals(listOf("aether-guid", "xray-guid"), restored.chainMemberGuids(renamed))
        assertEquals("aether-guid", restored.aetherEntryGuid(renamed))
    }

    @Test
    fun ordinaryXrayChainsRemainSupported() {
        val chain = ProfileItem.create(EConfigType.PROXYCHAIN).copy(proxyChainProfileGuids = listOf("xray-guid", "other-guid"))
        assertNull(chain.aetherEntryGuid(profiles))
    }

    @Test
    fun missingDuplicateAmbiguousAndUnsupportedMembersFailClosed() {
        listOf(emptyList(), listOf("xray-guid"), listOf("xray-guid", "missing"),
            listOf("xray-guid", "xray-guid"), listOf("xray-guid", "aether-guid")).forEach { ids ->
            val chain = ProfileItem.create(EConfigType.PROXYCHAIN).copy(proxyChainProfileGuids = ids)
            assertThrows(Exception::class.java) { chain.aetherEntryGuid(profiles) }
        }
        val legacy = ProfileItem.create(EConfigType.PROXYCHAIN).copy(proxyChainProfiles = "entry,exit")
        assertThrows(Exception::class.java) { legacy.chainMemberGuids(profiles + ("duplicate-guid" to profiles.getValue("xray-guid"))) }
        listOf(EConfigType.CUSTOM, EConfigType.PROXYCHAIN, EConfigType.POLICYGROUP, EConfigType.HYSTERIA).forEach { type ->
            val chain = ProfileItem.create(EConfigType.PROXYCHAIN).copy(proxyChainProfileGuids = listOf("xray-guid", "unsupported"))
            assertThrows(Exception::class.java) { chain.aetherEntryGuid(profiles + ("unsupported" to ProfileItem.create(type))) }
        }
    }
}
