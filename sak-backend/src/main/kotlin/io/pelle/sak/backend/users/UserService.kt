package io.pelle.sak.backend.users

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.users.User
import io.pelle.sak.model.users.UserRepository
import java.time.Clock

// a user as the list shows it: an added one with its id, or an admin of the configuration, which
// has none and is not deleted here
data class UserEntry(
    val id: Int?,
    val email: String,
    val name: String?,
    val configured: Boolean,
)

sealed interface AddUserResult {
    data class Added(
        val user: User,
    ) : AddUserResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : AddUserResult

    // an added user of the address exists
    data object Exists : AddUserResult

    // the address is an admin of the configuration, never also a user
    data object Admin : AddUserResult
}

// who may sign in besides the admins of the configuration: the users an admin adds by address, with
// an optional name. An added user has the role user (the apps of the lists); deleting one ends their
// sessions with their next request, their data stays
class UserService(
    private val repository: UserRepository,
    // the admins of the configuration, normalized; they are no rows and stay as they are
    private val admins: List<String> = emptyList(),
) {
    constructor(
        database: SakDatabase,
        admins: List<String> = emptyList(),
        clock: Clock = Clock.systemUTC(),
    ) : this(UserRepository(database, clock), admins.map(::normalize).distinct())

    // the admins of the configuration first, then the added users by address
    fun users(): List<UserEntry> =
        admins.map { UserEntry(null, it, null, configured = true) } +
            repository.all().map { UserEntry(it.id, it.email, it.name, configured = false) }

    fun add(
        owner: String,
        email: String,
        name: String?,
    ): AddUserResult {
        val address = normalize(email)
        val named = name?.trim()?.ifEmpty { null }
        val errors =
            listOfNotNull(
                ErrorMessage(UsersTexts.INVALID_EMAIL).takeUnless { isEmail(address) },
                ErrorMessage(UsersTexts.INVALID_NAME, "max" to MAX_NAME_LENGTH).takeIf { named != null && (named.length > MAX_NAME_LENGTH || named.any { it.isISOControl() }) },
            )
        if (errors.isNotEmpty()) return AddUserResult.Invalid(errors)
        if (address in admins) return AddUserResult.Admin
        return repository.insert(owner, address, named)?.let { AddUserResult.Added(it) } ?: AddUserResult.Exists
    }

    // false when there is no such user
    fun delete(id: Int): Boolean = repository.delete(id)

    // whether the address is an added user, the role user; asked on every request of a session
    fun isUser(email: String): Boolean = repository.byEmail(normalize(email)) != null

    // the name of an added user, null without one or for another address
    fun name(email: String): String? = repository.byEmail(normalize(email))?.name

    companion object {
        const val MAX_EMAIL_LENGTH = 254

        const val MAX_NAME_LENGTH = 64

        // as the configuration compares them: trimmed, without case
        fun normalize(email: String) = email.trim().lowercase()

        // one @ with something before it, a domain with a dot after it, no spaces
        fun isEmail(email: String) = email.length <= MAX_EMAIL_LENGTH && EMAIL.matches(email)

        private val EMAIL = Regex("""[^\s@]+@[^\s@]+\.[^\s@]+""")
    }
}

// the texts of the users (backend.users in i18n/texts.yml)
object UsersTexts : Catalog("users") {
    val INVALID_EMAIL = text("invalid_email")
    val INVALID_NAME = text("invalid_name")
    val EXISTS = text("exists")
    val IS_ADMIN = text("is_admin")
}
