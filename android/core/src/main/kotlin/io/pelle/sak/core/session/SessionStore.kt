package io.pelle.sak.core.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

// a login link the app asked for and waits for: a link is only taken while one is pending, from the
// same deployment and at most PENDING_FOR old, and only with the nonce the app sent with the request,
// so nobody signs the user in with a link of their own (AN-8)
data class PendingLogin(
    val apiUrl: String,
    val startedAt: Instant,
    // the random value the request carried, the exchange needs it; kept encrypted like the token
    val nonce: String,
)

// what the app keeps: the deployment, the session, a pending login and the values of the app
data class Stored(
    val apiUrl: String? = null,
    // the name of the deployment, shown on the sign-in
    val deployment: String? = null,
    val token: String? = null,
    // the e-mail address, or admin for the password login
    val email: String? = null,
    val expiresAt: Instant? = null,
    val pending: PendingLogin? = null,
    // what the app keeps with the session, by its own names (e.g. the list open last of the grocery
    // lists); they go with the session, at the sign-out, its end and another deployment
    val app: Map<String, String> = emptyMap(),
)

// where the app keeps what it stores; the token never in plain text (KeystoreCipher). update changes
// what is stored at once, so two changes at the same time do not undo each other
interface SessionStore {
    suspend fun load(): Stored

    suspend fun update(change: (Stored) -> Stored)

    suspend fun save(stored: Stored) = update { stored }
}

class MemorySessionStore(
    private var stored: Stored = Stored(),
) : SessionStore {
    private val lock = Mutex()

    override suspend fun load() = stored

    override suspend fun update(change: (Stored) -> Stored) = lock.withLock { stored = change(stored) }
}
