package io.pelle.sak.backend.labels

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.backend.DeleteResult
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.api.Page
import io.pelle.sak.model.labels.Label
import io.pelle.sak.model.labels.LabelLinks
import io.pelle.sak.model.labels.LabelRepository
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import io.pelle.sak.model.labels.LabelNameUpdateResult

sealed interface LabelCreateResult {
    data class Created(
        val label: Label,
    ) : LabelCreateResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : LabelCreateResult

    data object Exists : LabelCreateResult
}

sealed interface LabelRenameResult {
    data class Renamed(
        val label: Label,
    ) : LabelRenameResult

    data object NotFound : LabelRenameResult

    // the values do not fit: every problem as the api answers it (API-1)
    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : LabelRenameResult

    data object Exists : LabelRenameResult
}

// the only way to the labels of a user (the owner); every app shares them, no other user sees them.
// The links of the apps to a label are dropped with it (SHARED-2)
class LabelService(
    private val repository: LabelRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(LabelRepository(database, clock))

    private val links = CopyOnWriteArrayList<LabelLinks>()

    // an app that links labels: its links to a label go with the label, in one transaction
    fun linkedBy(links: LabelLinks) {
        this.links.add(links)
    }

    // sorted by name
    fun page(
        owner: String,
        offset: Long,
        limit: Int,
    ): Page<Label> = Page(repository.page(owner, offset, limit), repository.count(owner))

    fun byId(
        owner: String,
        id: Int,
    ): Label? = repository.byId(owner, id)

    fun byIds(
        owner: String,
        ids: Collection<Int>,
    ): List<Label> = if (ids.isEmpty()) emptyList() else repository.byIds(owner, ids.toSet())

    fun byName(
        owner: String,
        name: String,
    ): Label? = normalize(name)?.let { repository.byNames(owner, listOf(it)).singleOrNull() }

    fun create(
        owner: String,
        name: String,
    ): LabelCreateResult {
        val normalized = normalize(name) ?: return LabelCreateResult.Invalid(listOf(INVALID_LABEL_NAME))
        val label = repository.insert(owner, normalized) ?: return LabelCreateResult.Exists
        return LabelCreateResult.Created(label)
    }

    fun rename(
        owner: String,
        id: Int,
        name: String,
    ): LabelRenameResult {
        val label = repository.byId(owner, id) ?: return LabelRenameResult.NotFound
        val normalized = normalize(name) ?: return LabelRenameResult.Invalid(listOf(INVALID_LABEL_NAME))
        if (normalized == label.name) return LabelRenameResult.Renamed(label)
        // deleted meanwhile, or another label got the name
        return when (val renamed = repository.rename(owner, id, normalized)) {
            is LabelNameUpdateResult.Renamed -> LabelRenameResult.Renamed(renamed.label)
            LabelNameUpdateResult.NotFound -> LabelRenameResult.NotFound
            LabelNameUpdateResult.NameTaken -> LabelRenameResult.Exists
        }
    }

    // the label stays as deleted, the links of the apps to it are dropped with it
    fun delete(
        owner: String,
        id: Int,
    ): DeleteResult = DeleteResult.of(repository.delete(owner, id, links))

    // the names normalized by the caller; the missing labels are created; sorted by name
    fun resolve(
        owner: String,
        names: Set<String>,
    ): List<Label> {
        if (names.isEmpty()) return emptyList()
        val existing = repository.byNames(owner, names).map { it.name }.toSet()
        // one created meanwhile, e.g. on another device, is there all the same
        (names - existing).forEach { repository.insert(owner, it) }
        return repository.byNames(owner, names)
    }

    companion object {
        const val MAX_NAME_LENGTH = 64

        private val WHITESPACE = Regex("\\s+")

        // lower case with single spaces, null when blank, too long or with control characters
        fun normalize(name: String): String? {
            val normalized = name.trim().replace(WHITESPACE, " ").lowercase()
            return normalized.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none(Char::isISOControl) }
        }
    }
}
