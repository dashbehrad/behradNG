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
    UNKNOWN, DNS, TLS, TIMEOUT, NETWORK, REFUSED, RATE_LIMITED, CONFIG,
    HTTP_ERROR, API_RESPONSE, API, IO, ECH, OTHER;

    private fun normalized(line: String): String =
        line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "").trim().lowercase()

    fun isErrorLine(line: String): Boolean =
        Regex("(?:^|\\s)error:").containsMatchIn(normalized(line))

    fun httpStatus(line: String): Int? = if (isErrorLine(line)) {
        Regex("\\bstatus ([1-5][0-9]{2})\\b").find(normalized(line))?.groupValues?.get(1)?.toIntOrNull()
    } else null

    fun apiCode(line: String): Int? = if (isErrorLine(line)) {
        Regex("\\(code ([0-9]{1,8})\\)").find(normalized(line))?.groupValues?.get(1)?.toIntOrNull()
    } else null

    fun ioCode(line: String): Int? = if (isErrorLine(line)) {
        Regex("\\bcode: ([0-9]{1,4})\\b").find(normalized(line))?.groupValues?.get(1)?.toIntOrNull()
    } else null

    fun observe(line: String): AetherStartupFailure {
        val text = normalized(line)
        if ("identity ready" in text) return UNKNOWN
        if (!isErrorLine(line) && "registration retry" !in text && "key enrollment retry" !in text) return this
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
            httpStatus(line) != null -> HTTP_ERROR
            "registration decode:" in text || "key enrollment decode:" in text -> API_RESPONSE
            "api(" in text -> API
            "io(" in text -> IO
            "ech(" in text -> ECH
            "other(" in text -> OTHER
            else -> if (isErrorLine(line)) UNKNOWN else this
        }
    }
}

class AetherStartupException(val stage: AetherStartupStage, val exitCode: Int? = null,
    val failure: AetherStartupFailure = AetherStartupFailure.UNKNOWN,
    val httpStatus: Int? = null, val apiCode: Int? = null, val ioCode: Int? = null) :
    IllegalStateException("Aether startup stage=${stage.name}, exitCode=${exitCode ?: "timeout"}, failure=${failure.name}, httpStatus=$httpStatus, apiCode=$apiCode, ioCode=$ioCode")
