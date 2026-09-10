package com.photonspark.pocketexit.ui

import com.photonspark.pocketexit.network.PairingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * The one claim that may be in flight, owned outside composition.
 *
 * A claim spends a single-use pairing code, so an activity recreation in the
 * middle of one must not start a second: turning the phone over mid-request
 * would otherwise burn the code and leave the person with a failure they cannot
 * recover from without minting another. The recreated composition looks the
 * running claim up by its key and awaits the same answer instead.
 *
 * Only one claim is ever held. Starting a claim under a different key abandons
 * the previous one, which is what confirming a freshly scanned code means.
 */
internal object PairingClaims {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var heldKey = ""
    private var running: Deferred<PairingClient.Result>? = null

    /**
     * The claim for [key], started with [block] only when no claim for that key
     * is already running or finished. Awaiting the result more than once is
     * safe: every caller sees the same answer.
     */
    fun claim(
        key: String,
        block: suspend () -> PairingClient.Result,
    ): Deferred<PairingClient.Result> {
        synchronized(lock) {
            val current = running
            if (current != null && heldKey == key) return current
            current?.cancel()
            val started = scope.async { block() }
            heldKey = key
            running = started
            return started
        }
    }

    /** Drops the claim for [key], if that is the one being held. */
    fun forget(key: String) {
        synchronized(lock) {
            if (heldKey != key) return
            running?.cancel()
            heldKey = ""
            running = null
        }
    }
}
