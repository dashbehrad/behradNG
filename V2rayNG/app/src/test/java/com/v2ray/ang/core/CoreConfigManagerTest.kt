package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreConfigManagerTest {
    @Test
    fun saturnDnsAllowsTorLatencyWithoutChangingResolversOrDirectRules() {
        val custom = V2rayConfig.DnsBean.ServersBean(address = "https://resolver.example/dns-query",
            domains = listOf("domain:example.com"), skipFallback = true)
        val direct = custom.copy(tag = "direct-dns")
        val slower = custom.copy(timeoutMs = 60_000)
        val config = V2rayConfig(log = V2rayConfig.LogBean(), inbounds = arrayListOf(), outbounds = arrayListOf(),
            routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf(
                V2rayConfig.RoutingBean.RulesBean(outboundTag = "direct", inboundTag = listOf("direct-dns")))),
            dns = V2rayConfig.DnsBean(servers = arrayListOf("https://cloudflare-dns.com/dns-query", custom, direct,
                slower, "localhost", "fakedns", "https+local://resolver.example/dns-query", "tcp://8.8.8.8")))

        CoreConfigManager.configureSaturnDns(config)
        CoreConfigManager.configureSaturnDns(config)

        val servers = config.dns!!.servers!!
        assertEquals(30_000L, (servers[0] as V2rayConfig.DnsBean.ServersBean).timeoutMs)
        assertEquals(custom.copy(timeoutMs = 30_000), servers[1])
        assertEquals(direct, servers[2])
        assertEquals(slower, servers[3])
        assertEquals(listOf("localhost", "fakedns", "https+local://resolver.example/dns-query"), servers.subList(4, 7))
        assertEquals("tcp://8.8.8.8", (servers[7] as V2rayConfig.DnsBean.ServersBean).address)
        assertNull(custom.timeoutMs)
    }

    @Test
    fun dnsServerTimeoutIsAnOptionalBackwardCompatibleField() {
        val gson = Gson()
        val previous = """{"address":"https://resolver.example/dns-query","domains":["domain:example.com"],"skipFallback":true}"""
        val server = gson.fromJson(previous, V2rayConfig.DnsBean.ServersBean::class.java)
        assertNull(server.timeoutMs)
        assertEquals(listOf("domain:example.com"), server.domains)
        assertEquals(true, server.skipFallback)
        val extended = gson.toJsonTree(server.copy(timeoutMs = 30_000)).asJsonObject
        assertEquals(30_000L, extended.get("timeoutMs").asLong)
        assertEquals(server.address, extended.get("address").asString)
    }

    @Test
    fun saturnAllowsExitStreamSetupAtTheUnauthenticatedSocksPolicyLevel() {
        val level = V2rayConfig.PolicyBean.LevelBean(handshake = 4, connIdle = 300, uplinkOnly = 1)
        val config = V2rayConfig(log = V2rayConfig.LogBean(), inbounds = arrayListOf(),
            outbounds = arrayListOf(), routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf()),
            policy = V2rayConfig.PolicyBean(levels = mapOf("8" to level), system = "retained"))

        CoreConfigManager.configureSaturnPolicy(config)

        assertEquals(30, config.policy?.levels?.get("0")?.handshake)
        assertEquals(30, config.policy?.levels?.get("8")?.handshake)
        assertEquals(300, config.policy?.levels?.get("8")?.connIdle)
        assertEquals(1, config.policy?.levels?.get("8")?.uplinkOnly)
        assertEquals("retained", config.policy?.system)
        assertEquals(4, level.handshake)

        config.policy!!.levels = mapOf("0" to level.copy(handshake = 90))
        CoreConfigManager.configureSaturnPolicy(config)
        assertEquals(90, config.policy?.levels?.get("0")?.handshake)

        config.policy = null
        CoreConfigManager.configureSaturnPolicy(config)
        assertEquals(30, config.policy?.levels?.get("0")?.handshake)
    }

    @Test
    fun saturnDnsInterceptsVpnAndProxyOnlyInputsBeforeUserRoutesAndIsIdempotent() {
        val userRule = V2rayConfig.RoutingBean.RulesBean(outboundTag = "proxy", network = "udp")
        val config = V2rayConfig(log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(
                V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks"),
                V2rayConfig.InboundBean(tag = "tun", port = null, protocol = "tun"),
                V2rayConfig.InboundBean(tag = "http", port = 10809, protocol = "http")),
            outbounds = arrayListOf(), routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf(userRule)))

        CoreConfigManager.configureSaturnDns(config)
        CoreConfigManager.configureSaturnDns(config)

        assertEquals(2, config.routing.rules.size)
        assertEquals(listOf("socks", "tun"), config.routing.rules.first().inboundTag)
        assertEquals("53", config.routing.rules.first().port)
        assertEquals("dns-out", config.routing.rules.first().outboundTag)
        assertEquals(userRule, config.routing.rules.last())
        assertEquals(1, config.outbounds.count { it.protocol == "dns" && it.tag == "dns-out" })
    }

    @Test
    fun saturnDnsReusesAnExistingDnsOutboundAndSupportsSocksOnlyMode() {
        val dnsOutbound = V2rayConfig.OutboundBean(protocol = "dns", tag = "dns-out")
        val config = V2rayConfig(log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = arrayListOf(dnsOutbound), routing = V2rayConfig.RoutingBean("AsIs", rules = arrayListOf()))
        CoreConfigManager.configureSaturnDns(config)
        assertEquals(listOf("socks"), config.routing.rules.single().inboundTag)
        assertEquals(listOf(dnsOutbound), config.outbounds)

        config.inbounds.clear()
        config.routing.rules.clear()
        CoreConfigManager.configureSaturnDns(config)
        assertTrue(config.routing.rules.isEmpty())
    }

    @Test
    fun rawDnsIpsUseTcpWhileHttpsAndExplicitTcpResolversStayUnchanged() {
        val servers = listOf("8.8.8.8", "2001:4860:4860::8888", "[2606:4700:4700::1111]",
            "https://custom.example/dns-query", "tcp://192.0.2.1:5353", "localhost")
        assertEquals(listOf("tcp://8.8.8.8", "tcp://[2001:4860:4860::8888]", "tcp://[2606:4700:4700::1111]",
            "https://custom.example/dns-query", "tcp://192.0.2.1:5353", "localhost"), CoreConfigManager.tcpDnsServers(servers))
        assertTrue(CoreConfigManager.tcpDnsServers(emptyList()).isEmpty())
        assertEquals("8.8.8.8", servers.first())
    }

    @Test
    fun successfulResolutionIsSharedAcrossOutboundsWithoutDiscardingAddressFamilies() {
        val outbounds = listOf(outbound("proxy.example", 443), outbound("proxy.example", 8443))
        val dns = V2rayConfig.DnsBean(hosts = mapOf("existing.example" to "192.0.2.2"))
        val addresses = listOf("2001:db8::1", "192.0.2.1")
        var calls = 0

        CoreConfigManager.resolveOutboundHosts(outbounds, dns, true) { domain, preferIpv6 ->
            assertEquals("proxy.example", domain)
            assertTrue(preferIpv6)
            calls++
            addresses
        }

        assertEquals(1, calls)
        assertEquals(addresses, dns.hosts?.get("proxy.example"))
        assertEquals("192.0.2.2", dns.hosts?.get("existing.example"))
        outbounds.forEach {
            assertEquals("UseIP", it.streamSettings?.sockopt?.domainStrategy)
            assertEquals(true, it.streamSettings?.sockopt?.happyEyeballs?.prioritizeIPv6)
            assertEquals(2, it.streamSettings?.sockopt?.happyEyeballs?.interleave)
        }
    }

    @Test
    fun failedAndEmptyLookupsAreAttemptedOncePerBuildAndRetriedOnNextBuild() {
        listOf<List<String>?>(null, emptyList()).forEach { failure ->
            val outbounds = listOf(outbound("proxy.example", 443), outbound("proxy.example", 8443))
            val dns = V2rayConfig.DnsBean()
            var calls = 0

            CoreConfigManager.resolveOutboundHosts(outbounds, dns, false) { _, _ ->
                calls++
                failure
            }

            assertEquals(1, calls)
            assertTrue(dns.hosts.orEmpty().isEmpty())
            outbounds.forEach { assertNull(it.streamSettings?.sockopt) }

            CoreConfigManager.resolveOutboundHosts(outbounds, dns, false) { _, _ ->
                calls++
                listOf("192.0.2.1")
            }

            assertEquals(2, calls)
            assertEquals("192.0.2.1", dns.hosts?.get("proxy.example"))
            outbounds.forEach {
                assertEquals(false, it.streamSettings?.sockopt?.happyEyeballs?.prioritizeIPv6)
            }
        }
    }

    @Test
    fun failedHostnameDoesNotPreventOtherServersFromResolving() {
        val outbounds = listOf(outbound("failed.example", 443), outbound("working.example", 443))
        val dns = V2rayConfig.DnsBean()
        val calls = mutableListOf<String>()

        CoreConfigManager.resolveOutboundHosts(outbounds, dns, false) { domain, _ ->
            calls.add(domain)
            if (domain == "working.example") listOf("192.0.2.1") else null
        }

        assertEquals(listOf("failed.example", "working.example"), calls)
        assertNull(outbounds.first().streamSettings?.sockopt)
        assertEquals("192.0.2.1", dns.hosts?.get("working.example"))
        assertEquals("UseIP", outbounds.last().streamSettings?.sockopt?.domainStrategy)
    }

    @Test
    fun explicitHostsTakePriorityAndAbsentAddressesDoNotTriggerResolution() {
        val existing = outbound("proxy.example", 443)
        val missing = outbound(null, 443)
        val empty = outbound("", 443)
        val dns = V2rayConfig.DnsBean(hosts = mapOf("proxy.example" to "192.0.2.5"))

        CoreConfigManager.resolveOutboundHosts(listOf(existing, missing, empty), dns, false) { _, _ ->
            error("Explicit hosts and missing addresses must not be resolved")
        }

        assertEquals("192.0.2.5", dns.hosts?.get("proxy.example"))
        assertEquals("UseIP", existing.streamSettings?.sockopt?.domainStrategy)
        assertNull(missing.streamSettings?.sockopt)
        assertNull(empty.streamSettings?.sockopt)
    }

    @Test
    fun emptyOutboundListPreservesHostsWithoutResolving() {
        val dns = V2rayConfig.DnsBean(hosts = mapOf("existing.example" to "192.0.2.2"))
        CoreConfigManager.resolveOutboundHosts(emptyList(), dns, false) { _, _ ->
            error("Empty outbound list must not resolve")
        }
        assertEquals(mapOf("existing.example" to "192.0.2.2"), dns.hosts)
    }

    private fun outbound(address: String?, port: Int) = V2rayConfig.OutboundBean(
        protocol = "vless",
        settings = V2rayConfig.OutboundBean.OutSettingsBean(address = address, port = port),
        streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(),
    )
}
