package io.pelle.sak.backend.restic

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.core.Language
import io.pelle.sak.core.render
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.restic.ResticRepositoryInsertResult
import io.pelle.sak.model.restic.ResticRepository
import io.pelle.sak.model.restic.ResticRepositoryRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
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

    // the user has a repository of the name the description gives
    data class Exists(
        val name: String,
    ) : ResticRepositoryCreateResult
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

    // deletes a repository of the user with its backup plans, a soft delete: its files stay in the
    // storage until it is purged (BACKUP-9)
    fun delete(
        user: String,
        id: Int,
    ): DeleteResult = DeleteResult.of(repository.delete(user, id))

    // the first repository of a user who never had one, described in the language of the sign-in
    // (BACKUP-7), its name derived from it
    override fun signedIn(
        owner: String,
        language: Language,
    ) {
        val description = ResticTexts.FIRST_REPOSITORY.render(language)
        repository.insertFirst(owner, nameOf(description), description, newToken())
    }

    // a repository with a new token, its name derived from the description (BACKUP-1); neither changes
    // afterwards
    fun create(
        user: String,
        description: String,
    ): ResticRepositoryCreateResult {
        val trimmed = description.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_DESCRIPTION || trimmed.any { it.isISOControl() }) {
            return ResticRepositoryCreateResult.Invalid(listOf(ErrorMessage(ResticTexts.INVALID_DESCRIPTION, "max" to MAX_DESCRIPTION)))
        }
        val name = nameOf(trimmed)
        if (name.isEmpty()) return ResticRepositoryCreateResult.Invalid(listOf(ErrorMessage(ResticTexts.NO_NAME)))
        return when (val inserted = repository.insert(user, name, trimmed, newToken())) {
            is ResticRepositoryInsertResult.Inserted -> ResticRepositoryCreateResult.Created(inserted.repository)
            ResticRepositoryInsertResult.NameTaken -> ResticRepositoryCreateResult.Exists(name)
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
        // the most characters of a description, the column takes 255
        const val MAX_DESCRIPTION = 255

        // the most characters of a name
        private const val MAX_NAME = 63

        // the name the command line takes for a description: lower case, umlauts written out, every
        // other character but a-z, 0-9, - and _ a hyphen, no hyphen twice, none at the start or the
        // end, at most 63 characters; empty when nothing of it is left, e.g. "My Backups" is my-backups
        fun nameOf(description: String): String {
            val written =
                description
                    .lowercase()
                    .replace("ä", "ae")
                    .replace("ö", "oe")
                    .replace("ü", "ue")
                    .replace("ß", "ss")
            // other accents go, the letter stays: é is e
            val plain = Normalizer.normalize(written, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val name = plain.replace(Regex("[^a-z0-9_-]+"), "-").replace(Regex("-{2,}"), "-")
            return name.trim('-', '_').take(MAX_NAME).trim('-', '_')
        }
    }
}
