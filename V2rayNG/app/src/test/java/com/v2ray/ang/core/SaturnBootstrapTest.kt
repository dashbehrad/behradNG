package com.v2ray.ang.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SaturnBootstrapTest {
    @Test fun readyAndTimedOutBootstrapsHaveDistinctResults(): Unit = runBlocking {
        assertTrue(SaturnSession.awaitBootstrap(1000, { true }, { true }))
        assertFalse(SaturnSession.awaitBootstrap(10, { false }, { true }))
    }

    @Test fun deadCoreIsFailureAndOwnerCancellationIsNotTimeout(): Unit = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { SaturnSession.awaitBootstrap(1000, { false }, { false }) }
        }
        val entered = CompletableDeferred<Unit>()
        val pending = async {
            SaturnSession.awaitBootstrap(10000, { false }, { entered.complete(Unit); true })
        }
        entered.await()
        pending.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { pending.await() } }
    }
}
