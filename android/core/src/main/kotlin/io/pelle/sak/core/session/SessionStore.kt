package io.pelle.sak.core.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

// a login link the app asked for and waits for: a link is only taken while one is pending, from the
// same deployment and at most PENDING_FOR old, so nobody signs the user in with a link of their own
data class PendingLogin(
    val apiUrl: String,
    val startedAt: Instant,
)

// what the app keeps: the deployment, the session, a pending login and the list open last
data class Stored(
    val apiUrl: String? = null,
    // the name of the deployment, shown on the sign-in
    val deployment: String? = null,
    val token: String? = null,
    // the e-mail address, or admin for the password login
    val email: String? = null,
    val expiresAt: Instant? = null,
    val pending: PendingLogin? = null,
    // the id of the list open when the app was left, opened again on the next start; none on the home
    val openList: Int? = null,
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
