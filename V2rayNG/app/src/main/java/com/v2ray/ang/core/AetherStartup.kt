package com.v2ray.ang.core

/** Only a non-secret phase is retained from native output; raw native lines are never logged. */
enum class AetherStartupStage {
    CORE, REGISTRATION, SCAN, TRANSPORT;

    fun observe(line: String): AetherStartupStage {
        val text = line.lowercase()
        return when {
            "identity ready" in text -> SCAN
            "handshake" in text || "establishing tunnel" in text -> TRANSPORT
            "register" in text || "provision" in text || "enroll" in text -> REGISTRATION
            "scanning" in text || "scan mode" in text || "selected peer" in text -> SCAN
            else -> this
        }
    }
}

/** Only fixed categories leave the native output reader; native error text may contain secrets. */
enum class AetherStartupFailure {
    UNKNOWN, DNS, TLS, TIMEOUT, NETWORK, REFUSED, RATE_LIMITED, CONFIG;

    fun observe(line: String): AetherStartupFailure {
        val text = line.lowercase()
        if ("identity ready" in text) return UNKNOWN
        if (!text.startsWith("error:") && "registration retry" !in text && "key enrollment retry" !in text) return this
        return when {
            "status 429" in text -> RATE_LIMITED
            "status 403" in text || "identityrefused(" in text -> REFUSED
            "config parse" in text || "config field" in text ||
                "permissiondenied" in text || "notadirectory" in text || "readonlyfilesystem" in text -> CONFIG
            "failed to lookup address" in text || "name or service not known" in text ||
                "no address associated" in text || "temporary failure in name resolution" in text ||
                "did not resolve to any address" in text -> DNS
            "tls(" in text || "handshake with" in text -> TLS
            "did not answer within" in text || "timedout" in text || "timed out" in text -> TIMEOUT
            "connect to" in text || "connection refused" in text ||
                "network is unreachable" in text || "connection reset" in text -> NETWORK
            else -> if (text.startsWith("error:")) UNKNOWN else this
        }
    }
}

class AetherStartupException(val stage: AetherStartupStage, val exitCode: Int? = null,
    val failure: AetherStartupFailure = AetherStartupFailure.UNKNOWN) :
    IllegalStateException("Aether startup stage=${stage.name}, exitCode=${exitCode ?: "timeout"}, failure=${failure.name}")
