package io.pelle.sak.grocery.session

import java.time.Instant

// a login link the app asked for and waits for: a link is only taken while one is pending, from the
// same deployment and at most PENDING_FOR old, so nobody signs the user in with a link of their own
data class PendingLogin(
    val apiUrl: String,
    val startedAt: Instant,
)

// what the app keeps: the deployment, the session and a pending login
data class Stored(
    val apiUrl: String? = null,
    // the name of the deployment, shown on the sign-in
    val deployment: String? = null,
    val token: String? = null,
    // the e-mail address, or admin for the password login
    val email: String? = null,
    val expiresAt: Instant? = null,
    val pending: PendingLogin? = null,
)

// where the app keeps what it stores; the token never in plain text (KeystoreCipher)
interface SessionStore {
    suspend fun load(): Stored

    suspend fun save(stored: Stored)
}

class MemorySessionStore(
    private var stored: Stored = Stored(),
) : SessionStore {
    override suspend fun load() = stored

    override suspend fun save(stored: Stored) {
        this.stored = stored
    }
}
