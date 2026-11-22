package io.pelle.sak.backend.restic

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.isDnsLabel
import io.pelle.sak.api.Catalog
import io.pelle.sak.api.Language
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.restic.ResticRepositoryInsertResult
import io.pelle.sak.model.restic.ResticRepository
import io.pelle.sak.model.restic.ResticRepositoryRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

sealed interface ResticRepositoryCreateResult {
    data class Created(
        val repository: ResticRepository,
    ) : ResticRepositoryCreateResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : ResticRepositoryCreateResult

    // the user has a repository of the name
    data object Exists : ResticRepositoryCreateResult
}

// the repositories of restic the instance keeps, the only way to them (BACKUP-1, BACKUP-2)
class ResticRepositoryService(
    private val repository: ResticRepositoryRepository,
    // whether an owner may still sign in: a removed user, or an admin no longer in the configuration,
    // reaches no repository of theirs (BK-8); asked on every login of restic
    private val mayLogin: (owner: String) -> Boolean,
    // where the files of the repositories are, none where only their rows are needed
    private val store: ResticStore? = null,
    private val random: SecureRandom = SecureRandom(),
) : LoginListener {
    // a repository is never given a resource id whose directory is in `store` (MODEL-8)
    constructor(
        database: SakDatabase,
        mayLogin: (owner: String) -> Boolean,
        store: ResticStore? = null,
        clock: Clock = Clock.systemUTC(),
    ) : this(ResticRepositoryRepository(database, clock, free = { store?.exists(it) != true }), mayLogin, store)

    // the bytes the files of the repository take on the data volume (BK-16), 0 without a store
    fun usage(repository: ResticRepository): Long = store?.usage(repository.resourceId) ?: 0

    fun repositories(user: String): List<ResticRepository> = repository.of(user)

    // the token of a repository of the user, null when the user has none of the id
    fun token(
        user: String,
        id: Int,
    ): String? = repository.token(user, id)

    // the first repository of a user who never had one (BACKUP-7), the same name in every language: it
    // is the one the command line names
    override fun signedIn(
        owner: String,
        language: Language,
    ) {
        repository.insertFirst(owner, FIRST_REPOSITORY, newToken())
    }

    // a repository with a new token
    fun create(
        user: String,
        name: String,
    ): ResticRepositoryCreateResult {
        val trimmed = name.trim()
        if (!isDnsLabel(trimmed)) return ResticRepositoryCreateResult.Invalid(listOf(ErrorMessage(ResticTexts.INVALID_NAME)))
        val token = newToken()
        return when (val inserted = repository.insert(user, trimmed, token)) {
            is ResticRepositoryInsertResult.Inserted -> ResticRepositoryCreateResult.Created(inserted.repository)
            ResticRepositoryInsertResult.NameTaken -> ResticRepositoryCreateResult.Exists
        }
    }

    // the resource id of the live repository when `token` is its one, compared in constant time, and
    // its owner may still sign in
    fun authorize(
        id: Int,
        token: String,
    ): ResourceId? {
        val login = repository.loginOf(id) ?: return null
        if (!MessageDigest.isEqual(login.token.toByteArray(), token.toByteArray())) return null
        return if (mayLogin(login.owner)) login.resourceId else null
    }

    // the resource ids of all repositories, the deleted ones included
    fun resourceIds(): Set<ResourceId> = repository.resourceIds()

    // 256 random bits
    private fun newToken() = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        const val FIRST_REPOSITORY = "my-backups"

    }
}

// the texts of the repositories of restic (backend.restic in i18n/texts.yml)
object ResticTexts : Catalog("restic") {
    val INVALID_NAME = text("invalid_name")
    val REPOSITORY_EXISTS = text("repository_exists")
    val INVALID_HOST = text("invalid_host")
    val NO_PATHS = text("no_paths")
    val TOO_MANY_PATHS = text("too_many_paths")
    val DOUBLE_PATH = text("double_path")
    val INVALID_PATH = text("invalid_path")
    val UNKNOWN_REPOSITORY = text("unknown_repository")
    val PLAN_EXISTS = text("plan_exists")
    val INVALID_KEEP = text("invalid_keep")
}
