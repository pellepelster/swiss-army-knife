package io.pelle.sak.backend.users

import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.auth.normalizeEmail
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.users.SignOutRepository
import io.pelle.sak.model.users.User
import io.pelle.sak.model.users.UserRepository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// a user as the list shows it: an added one with its id, or an admin of the configuration, which
// has none and is not deleted here
data class UserEntry(
    val id: Int?,
    val email: String,
    val name: String?,
    val configured: Boolean,
)

sealed interface UserCreateResult {
    data class Added(
        val user: User,
    ) : UserCreateResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : UserCreateResult

    // an added user of the address exists
    data object Exists : UserCreateResult

    // the address is an admin of the configuration, never also a user
    data object Admin : UserCreateResult
}

// who may sign in besides the admins of the configuration: the users an admin adds by address, with
// an optional name. An added user has the role user (the apps of the lists); deleting one ends their
// sessions with their next request and renews the tokens of what they have (SEC-39), their data stays
class UserService(
    private val repository: UserRepository,
    // the sign outs everywhere, of the users and of the admins of the configuration (SEC-4)
    private val signOuts: SignOutRepository,
    // the admins of the configuration, normalized; they are no rows and stay as they are
    private val admins: List<String> = emptyList(),
) {
    constructor(
        database: SakDatabase,
        admins: List<String> = emptyList(),
        clock: Clock = Clock.systemUTC(),
    ) : this(UserRepository(database, clock), SignOutRepository(database, clock), admins.map(::normalize).distinct())

    // the admins of the configuration first, then the added users by address
    fun all(): List<UserEntry> =
        admins.map { UserEntry(null, it, null, configured = true) } +
            repository.all().map { UserEntry(it.id, it.email, it.name, configured = false) }

    fun create(
        owner: String,
        email: String,
        name: String?,
    ): UserCreateResult {
        // as the login takes it: an address the login refuses would never get a link
        val address = normalizeEmail(email)
        val named = name?.trim()?.ifEmpty { null }
        val errors =
            listOfNotNull(
                ErrorMessage(UsersTexts.INVALID_EMAIL).takeIf { address == null },
                ErrorMessage(UsersTexts.INVALID_NAME, "max" to MAX_NAME_LENGTH).takeIf { named != null && (named.length > MAX_NAME_LENGTH || named.any { it.isISOControl() }) },
            )
        if (address == null || errors.isNotEmpty()) return UserCreateResult.Invalid(errors)
        if (address in admins) return UserCreateResult.Admin
        return repository.insert(owner, address, named)?.let { UserCreateResult.Added(it) } ?: UserCreateResult.Exists
    }

    // told of the normalized address of every sign out everywhere, a delete included, e.g. to renew the
    // tokens of its repositories of restic: a session copied before has none after (SEC-39, SEC-47)
    private val signedOutListeners = mutableListOf<(email: String) -> Unit>()

    fun whenSignedOut(listener: (email: String) -> Unit) {
        signedOutListeners += listener
    }

    // by the owner, an admin; false when there is no such user. Also a sign out everywhere: a token
    // knows its time in seconds only, so the time the user is added again alone would let one of the
    // same second through (SEC-39)
    fun delete(
        owner: String,
        id: Int,
    ): DeleteResult {
        val deleted = repository.delete(id) ?: return DeleteResult.of(false)
        signOutEverywhere(owner, deleted.email)
        return DeleteResult.of(true)
    }

    // from when the sessions, links and codes of an address count: those of an added user from when they
    // were added, so a user deleted and added again has none of before (SEC-39), and of everyone from
    // after the latest sign out everywhere (SEC-4); null for an address that is neither, which the
    // roles refuse. A sign out counts from the next second on: a token knows its time in seconds only,
    // so the one that signed out is refused too
    fun validFrom(email: String): Instant? {
        val address = normalize(email)
        val added = if (address in admins) null else repository.byEmail(address)?.createdAt
        val signedOut = signOuts.latest(address)?.truncatedTo(ChronoUnit.SECONDS)?.plusSeconds(1)
        return listOfNotNull(added, signedOut).maxOrNull()
    }

    // ends every session, link and code of the address, by the owner: the user, or an admin (SEC-4), and
    // tells the listeners, which end what else a session gave (SEC-47)
    fun signOutEverywhere(
        owner: String,
        email: String,
    ) {
        val address = normalize(email)
        signOuts.insert(owner, address)
        signedOutListeners.forEach { it(address) }
    }

    // signs the added user of the id out everywhere, by an admin; false when there is no such user
    fun signOut(
        owner: String,
        id: Int,
    ): Boolean {
        val user = repository.all().firstOrNull { it.id == id } ?: return false
        signOutEverywhere(owner, user.email)
        return true
    }

    // whether the address is an added user, the role user; asked on every request of a session
    fun isUser(email: String): Boolean = repository.byEmail(normalize(email)) != null

    // the name of an added user, null without one or for another address
    fun name(email: String): String? = repository.byEmail(normalize(email))?.name

    companion object {
        const val MAX_NAME_LENGTH = 64

        // as the configuration compares them: trimmed, without case
        fun normalize(email: String) = email.trim().lowercase()
    }
}
