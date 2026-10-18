package com.v2ray.ang.fmt

import com.google.gson.Gson
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SaturnSettings
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.io.encoding.Base64

class SaturnFmtTest {
    @Test fun releasedResolverSettingsRemainReadableAndDirectLinksRoundTrip() {
        val released = Gson().fromJson("""{"dohUrl":"https://doh.nineline.workers.dev/dns-query","bootstrapIps":"1.1.1.1,1.0.0.1","bridges":"","scanMode":"turbo","country":"auto"}""", SaturnSettings::class.java)
        assertTrue(released.isValid())
        assertTrue(released.usesAutomaticBridges())
        assertFalse(released.usesDirectConnection())
        assertEquals("https://doh.nineline.workers.dev/dns-query", released.dohUrl)
        assertEquals("1.1.1.1,1.0.0.1", released.bootstrapIps)
        val direct = profile().copy(saturnSettings = SaturnSettings(bridgeSource = "direct", country = "nl"))
        val restored = SaturnFmt.parse("saturn://" + SaturnFmt.toUri(direct))!!
        assertEquals(direct.saturnSettings, restored.saturnSettings)
        assertTrue(restored.saturnSettings!!.usesDirectConnection())
    }

    private fun profile() = ProfileItem.create(EConfigType.SATURN).copy(remarks = "Saturn test",
        saturnSettings = SaturnSettings(bridges = "obfs4 192.0.2.1:443 " + "A".repeat(40) + " cert=" + "B".repeat(70) + " iat-mode=0"))

    @Test fun encryptedCapabilityLinksRoundTripAndUseFreshRandomness() {
        val original = profile()
        val first = SaturnFmt.toUri(original)
        val second = SaturnFmt.toUri(original)
        assertNotEquals(first, second)
        val restored = SaturnFmt.parse("saturn://$first")!!
        assertEquals(original.saturnSettings, restored.saturnSettings)
        assertEquals(original.remarks, restored.remarks)
        assertFalse(first.contains("192.0.2.1"))
        assertEquals(EConfigType.SATURN, restored.configType)
    }
    @Test fun tamperingTruncationWrongVersionAndOversizeAreRejected() {
        val uri = SaturnFmt.toUri(profile())
        val parts = uri.removePrefix("v1/").split('#')
        val bytes = Base64.UrlSafe.decode(parts[0]); bytes[15] = (bytes[15].toInt() xor 1).toByte()
        assertNull(SaturnFmt.parse("saturn://v1/${Base64.UrlSafe.encode(bytes)}#${parts[1]}"))
        assertNull(SaturnFmt.parse("saturn://" + uri.substringBefore('#')))
        assertNull(SaturnFmt.parse("saturn://" + uri.replace("v1/", "v2/")))
        assertNull(SaturnFmt.parse("saturn://" + "a".repeat(65536)))
    }
    @Test fun precedingReleasedProfilesRemainReadableWithoutSaturnSettings() {
        val old = Gson().fromJson("""{"configType":"VLESS","remarks":"old","server":"example.org","serverPort":"443"}""", ProfileItem::class.java)
        assertNull(old.saturnSettings)
        assertEquals(EConfigType.VLESS, old.configType)
        assertEquals("old", old.remarks)
    }

    @Test fun releasedSaturnCustomSettingsRemainCustomAndAutomaticLinksRoundTrip() {
        val preceding = Gson().fromJson(Gson().toJson(profile().saturnSettings), com.google.gson.JsonObject::class.java)
        preceding.remove("bridgeSource")
        val legacy = Gson().fromJson(preceding, SaturnSettings::class.java)
        assertTrue(legacy.isValid())
        assertFalse(legacy.usesAutomaticBridges())
        val automatic = profile().copy(saturnSettings = SaturnSettings(bridgeSource = "auto", country = "nl"))
        val restored = SaturnFmt.parse("saturn://" + SaturnFmt.toUri(automatic))!!
        assertEquals(automatic.saturnSettings, restored.saturnSettings)
        assertTrue(restored.saturnSettings!!.usesAutomaticBridges())
    }
}
