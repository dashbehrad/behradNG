package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AetherSessionTest {
    @Test
    fun startupReadinessDependsOnSocksHandshake() {
        assertTrue(AetherSession.isStartupReady(true))
        assertFalse(AetherSession.isStartupReady(false))
    }

    @Test
    fun childEnvironmentLeavesEchUnsetAndDiscardsInheritedAetherOptions() {
        listOf("0", "auto", "invalid-key").forEach { inheritedEch ->
            val environment = mutableMapOf(
                "AETHER_ECH" to inheritedEch,
                "AETHER_CONFIG" to "/unrelated/identity.toml",
                "AETHER_UPSTREAM" to "inherited-proxy",
                "RUST_LOG" to "debug",
                "PATH" to "/system/bin",
            )
            AetherSession().configureEnvironment(environment)
            assertFalse("AETHER_ECH" in environment)
            assertFalse("AETHER_CONFIG" in environment)
            assertFalse("AETHER_UPSTREAM" in environment)
            assertEquals("/system/bin", environment["PATH"])
            assertEquals("info", environment["AETHER_LOG_LEVEL"])
            assertEquals("warn,aether=info", environment["RUST_LOG"])
        }
    }
}
