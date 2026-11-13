package io.pelle.sak.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.api.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

// called for each sign-in that issued a session, before the token is answered (not for sakctl);
// prepares the data of the owner (MODEL-5), e.g. the hidden list of the notes (NOTES-1), so it must
// be idempotent. language is the one of the request, for data named in it
fun interface LoginListener {
    fun signedIn(
        owner: String,
        language: Language,
    )
}

// the sign-in succeeds whatever the listener does; neither the owner nor the error message is
// logged, both can hold the address
internal suspend fun LoginListener.notifySignedIn(
    owner: String,
    language: Language,
) {
    try {
        withContext(Dispatchers.IO) { signedIn(owner, language) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error { "a login listener failed: ${e::class.qualifiedName}" }
    }
}
