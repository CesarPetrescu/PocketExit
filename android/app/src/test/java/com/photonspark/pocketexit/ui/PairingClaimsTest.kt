package com.photonspark.pocketexit.ui

import com.photonspark.pocketexit.network.PairingClient
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class PairingClaimsTest {
    @Test
    fun aSecondCompositionAttachesToTheClaimAlreadyRunning() = runBlocking {
        val key = "attaches"
        val started = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        try {
            // The claim the person confirmed, still in flight.
            val first = PairingClaims.claim(key) {
                started.incrementAndGet()
                release.await()
                paired
            }
            // The same composition after a rotation, asking for the same code.
            val second = PairingClaims.claim(key) {
                started.incrementAndGet()
                paired
            }
            assertSame(first, second)
            release.complete(Unit)
            assertEquals(paired, first.await())
            assertEquals(paired, second.await())
            // One claim ran, so the single-use code was spent exactly once.
            assertEquals(1, started.get())
        } finally {
            PairingClaims.forget(key)
        }
    }

    @Test
    fun anAnswerAlreadyGivenIsHandedBackRatherThanAskedForAgain() = runBlocking {
        val key = "remembers"
        val started = AtomicInteger()
        try {
            val first = PairingClaims.claim(key) {
                started.incrementAndGet()
                rejected
            }
            assertEquals(rejected, first.await())
            val second = PairingClaims.claim(key) {
                started.incrementAndGet()
                paired
            }
            assertEquals(rejected, second.await())
            assertEquals(1, started.get())
        } finally {
            PairingClaims.forget(key)
        }
    }

    @Test
    fun aFreshAttemptStartsAFreshClaim() = runBlocking {
        val first = PairingClaims.claim("retry|1") { rejected }
        assertEquals(rejected, first.await())
        val second = PairingClaims.claim("retry|2") { paired }
        assertNotSame(first, second)
        assertEquals(paired, second.await())
        PairingClaims.forget("retry|2")
        // Forgetting the held claim lets the next one start from nothing.
        val third = PairingClaims.claim("retry|2") { rejected }
        assertEquals(rejected, third.await())
        PairingClaims.forget("retry|2")
    }

    private companion object {
        val paired = PairingClient.Result.Paired(
            nodeId = "pixel-8-a1b2c3d4",
            agentToken = "agent-token-test-2026",
            serverUrl = "https://192.168.1.50:8443",
            socksHost = "127.0.0.1",
            socksPort = 1080,
            socksUsername = "proxy",
        )
        val rejected = PairingClient.Result.Failed(PairingClient.Failure.CODE_REJECTED)
    }
}
