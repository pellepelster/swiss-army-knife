package io.pelle.sak.core

import kotlinx.coroutines.CancellationException

// a call whose failure is no error of its own, e.g. what the app shows only when it can: its answer,
// null when it failed. A cancellation is no failure, it goes on to the caller (unlike runCatching)
suspend fun <T> attempt(call: suspend () -> T): T? =
    try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
