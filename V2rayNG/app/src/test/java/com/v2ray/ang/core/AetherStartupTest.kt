package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.AetherSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AetherStartupTest {
    @Test
    fun registrationErrorsRetainOnlySafeFailureCategories() {
        listOf(
            "Error: Api(\"registration: failed to lookup address information\")" to AetherStartupFailure.DNS,
            "Error: Api(\"registration: tls: handshake with api.cloudflareclient.com:443: failed\")" to AetherStartupFailure.TLS,
            "Error: Api(\"registration: api.cloudflareclient.com:443 did not answer within 15s\")" to AetherStartupFailure.TIMEOUT,
            "Error: Api(\"registration: connect to api.cloudflareclient.com:443: connection refused\")" to AetherStartupFailure.NETWORK,
            "Error: Api(\"registration: status 403 Forbidden: refused\")" to AetherStartupFailure.REFUSED,
            "Error: Api(\"registration: status 429 Too Many Requests\")" to AetherStartupFailure.RATE_LIMITED,
            "Error: Io(Os { code: 20, kind: NotADirectory })" to AetherStartupFailure.CONFIG,
            "Error: Other(\"config parse: invalid\")" to AetherStartupFailure.CONFIG,
        ).forEach { (line, expected) ->
            val failure = AetherStartupFailure.UNKNOWN.observe("$line credential=DO_NOT_LOG")
            assertEquals(expected, failure)
            val exception = AetherStartupException(AetherStartupStage.REGISTRATION, 1, failure)
            assertEquals(expected, exception.failure)
            assertFalse(exception.message.orEmpty().contains("DO_NOT_LOG"))
            assertFalse(exception.message.orEmpty().contains("api.cloudflareclient.com"))
        }
        assertEquals(AetherStartupFailure.UNKNOWN, AetherStartupFailure.UNKNOWN.observe("enrolling TLS key"))
        assertEquals(AetherStartupFailure.UNKNOWN, AetherStartupFailure.UNKNOWN.observe("Error: Other(\"unknown secret\")"))
        assertEquals(AetherStartupFailure.DNS, AetherStartupFailure.DNS.observe("unrelated status 403"))
        assertEquals(AetherStartupFailure.UNKNOWN, AetherStartupFailure.DNS.observe("Error: Other(\"unknown secret\")"))
        assertEquals(AetherStartupFailure.UNKNOWN, AetherStartupFailure.DNS.observe("[+] identity ready: device=DO_NOT_LOG"))
    }

    @Test
    fun legacyConfiguredPortsNeverDetermineTheRuntimeListener() {
        listOf("1819", "10808", "80", "", "bad").forEach { legacy ->
            val arguments = AetherSettings(socksPort = legacy).arguments("/private/identity.toml", 45123)
            assertEquals("127.0.0.1:45123", arguments[arguments.indexOf("--bind") + 1])
        }
        listOf(0, 80, 65536).forEach { port ->
            assertThrows(IllegalArgumentException::class.java) { AetherSettings().arguments("identity.toml", port) }
        }
    }

    @Test
    fun nativeOutputRetainsOnlyThePhaseAndExitCode() {
        var stage = AetherStartupStage.CORE
        stage = stage.observe("register identity credential=DO_NOT_LOG")
        assertEquals(AetherStartupStage.REGISTRATION, stage)
        stage = stage.observe("identity ready: device=DO_NOT_LOG")
        assertEquals(AetherStartupStage.SCAN, stage)
        stage = stage.observe("handshake failed private-key=DO_NOT_LOG")
        assertEquals(AetherStartupStage.TRANSPORT, stage)
        val failure = AetherStartupException(stage, 1)
        assertEquals(1, failure.exitCode)
        assertFalse(failure.message.orEmpty().contains("DO_NOT_LOG"))
        assertEquals(stage, stage.observe("unrelated diagnostic text"))
    }

    @Test
    fun timeoutIsDistinctFromAnExitedCore() {
        val failure = AetherStartupException(AetherStartupStage.SCAN)
        assertNull(failure.exitCode)
        assertEquals(AetherStartupStage.SCAN, failure.stage)
    }
}
