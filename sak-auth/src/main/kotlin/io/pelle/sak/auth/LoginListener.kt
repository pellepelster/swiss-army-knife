package io.pelle.sak.auth

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

// called once for each sign-in that issued a session, by e-mail or by password, before the session
// token is answered; a signed request of sakctl is no sign-in. owner is who the data of the session
// belongs to (MODEL-5): the e-mail address, or the admin of the password login. It prepares the
// data of the user, e.g. a first list (LIST-1), and must be idempotent
fun interface LoginListener {
    fun signedIn(owner: String)
}

// the sign-in succeeds whatever the listener does; neither the owner nor the message of the error
// is logged, both can hold the address (PII)
internal suspend fun LoginListener.notifySignedIn(owner: String) {
    try {
        withContext(Dispatchers.IO) { signedIn(owner) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error { "a login listener failed: ${e::class.qualifiedName}" }
    }
}
