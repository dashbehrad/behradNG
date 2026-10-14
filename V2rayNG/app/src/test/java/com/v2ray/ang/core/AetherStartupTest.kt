package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.AetherSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertEquals(AetherStartupFailure.OTHER, AetherStartupFailure.UNKNOWN.observe("Error: Other(\"unknown secret\")"))
        assertEquals(AetherStartupFailure.DNS, AetherStartupFailure.DNS.observe("unrelated status 403"))
        assertEquals(AetherStartupFailure.UNKNOWN, AetherStartupFailure.DNS.observe("Error: Unexpected(\"unknown secret\")"))
        assertEquals(AetherStartupFailure.UNKNOWN, AetherStartupFailure.DNS.observe("[+] identity ready: device=DO_NOT_LOG"))
    }

    @Test
    fun prefixedErrorsExposeOnlyTheirTypeAndNumericCodes() {
        val classifier = AetherStartupFailure.UNKNOWN
        val rejected = "[native] \u001B[31mError:\u001B[0m Api(\"registration: status 400 Bad Request: invalid (code 1044) credential=DO_NOT_LOG\")"
        assertEquals(AetherStartupFailure.HTTP_ERROR, classifier.observe(rejected))
        assertEquals(400, classifier.httpStatus(rejected))
        assertEquals(1044, classifier.apiCode(rejected))
        assertNull(classifier.ioCode(rejected))
        val io = "  Error: Io(Os { code: 13, kind: PermissionDenied, message: \"secret path\" })"
        assertEquals(AetherStartupFailure.CONFIG, classifier.observe(io))
        assertEquals(13, classifier.ioCode(io))
        assertEquals(AetherStartupFailure.API_RESPONSE, classifier.observe("Error: Api(\"registration decode: missing field token\")"))
        assertEquals(AetherStartupFailure.API, classifier.observe("Error: Api(\"unrecognized private response\")"))
        assertEquals(AetherStartupFailure.IO, classifier.observe("Error: Io(Custom { kind: Other, error: \"private response\" })"))
        assertEquals(AetherStartupFailure.ECH, classifier.observe("Error: Ech(\"private response\")"))
        val exception = AetherStartupException(AetherStartupStage.REGISTRATION, 1,
            classifier.observe(rejected), classifier.httpStatus(rejected), classifier.apiCode(rejected))
        assertTrue(exception.message.orEmpty().contains("httpStatus=400, apiCode=1044"))
        assertFalse(exception.message.orEmpty().contains("DO_NOT_LOG"))
        assertFalse(exception.message.orEmpty().contains("invalid"))
        assertNull(classifier.httpStatus("credential=status 400"))
        assertNull(classifier.apiCode("credential=(code 1044)"))
        assertNull(classifier.ioCode("credential=code: 13"))
        assertNull(classifier.httpStatus("Error: Api(\"status 40012345\")"))
        assertNull(classifier.apiCode("Error: Api(\"(code 123456789)\")"))
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
