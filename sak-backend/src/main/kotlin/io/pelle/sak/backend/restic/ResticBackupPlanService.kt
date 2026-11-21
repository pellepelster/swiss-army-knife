package io.pelle.sak.backend.restic

import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.restic.ResticBackupPlan
import io.pelle.sak.model.restic.ResticBackupPlanRepository
import io.pelle.sak.model.restic.ResticBackupPlanSave
import io.pelle.sak.model.restic.ResticBackupPlanValues
import io.pelle.sak.model.restic.ResticKeep
import java.time.Clock

sealed interface ResticBackupPlanResult {
    data class Saved(
        val plan: ResticBackupPlan,
    ) : ResticBackupPlanResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : ResticBackupPlanResult

    // the user has no such backup plan
    data object NotFound : ResticBackupPlanResult

    // the repository it names is none of the user (API-6)
    data object UnknownRepository : ResticBackupPlanResult

    // the user has another backup plan of the name
    data object Exists : ResticBackupPlanResult
}

// the backup plans of restic, the only way to them; it checks the values (BACKUP-5, BACKUP-6),
// ResticBackupPlanRepository who sees and changes what (MODEL-6)
class ResticBackupPlanService(
    private val repository: ResticBackupPlanRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(ResticBackupPlanRepository(database, clock))

    fun plans(user: String): List<ResticBackupPlan> = repository.of(user)

    fun plan(
        user: String,
        id: Int,
    ): ResticBackupPlan? = repository.byId(user, id)

    fun create(
        user: String,
        values: ResticBackupPlanValues,
    ): ResticBackupPlanResult = save(values) { repository.insert(user, it) }

    // replaces the values of a backup plan of the user
    fun update(
        user: String,
        id: Int,
        values: ResticBackupPlanValues,
    ): ResticBackupPlanResult = save(values) { repository.update(user, id, it) }

    private fun save(
        values: ResticBackupPlanValues,
        store: (ResticBackupPlanValues) -> ResticBackupPlanSave,
    ): ResticBackupPlanResult {
        val checked = values.copy(name = values.name.trim(), host = values.host.trim(), paths = values.paths.map { normalized(it.trim()) })
        val errors =
            listOfNotNull(
                ErrorMessage(ResticTexts.INVALID_NAME).takeUnless { NAME.matches(checked.name) },
                ErrorMessage(ResticTexts.INVALID_HOST).takeUnless { HOST.matches(checked.host) },
                ErrorMessage(ResticTexts.NO_PATHS).takeIf { checked.paths.isEmpty() },
                ErrorMessage(ResticTexts.TOO_MANY_PATHS, "max" to MAX_PATHS).takeIf { checked.paths.size > MAX_PATHS },
                ErrorMessage(ResticTexts.DOUBLE_PATH).takeIf { checked.paths.toSet().size != checked.paths.size },
                ErrorMessage(ResticTexts.INVALID_KEEP, "max" to MAX_KEEP).takeIf { !checked.keep.valid() },
            ) + checked.paths.filterNot(::isPath).take(1).map { ErrorMessage(ResticTexts.INVALID_PATH, "path" to it.take(MAX_SHOWN)) }
        if (errors.isNotEmpty()) return ResticBackupPlanResult.Invalid(errors)
        return when (val saved = store(checked)) {
            is ResticBackupPlanSave.Saved -> ResticBackupPlanResult.Saved(saved.plan)
            ResticBackupPlanSave.NotFound -> ResticBackupPlanResult.NotFound
            ResticBackupPlanSave.UnknownRepository -> ResticBackupPlanResult.UnknownRepository
            ResticBackupPlanSave.NameTaken -> ResticBackupPlanResult.Exists
        }
    }

    companion object {
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        // a host name as the computer has it: letters, digits, '-', '_' and '.', as restic takes it for --host
        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")

        const val MAX_PATHS = 100

        // the most snapshots of one kind a run keeps (BACKUP-8)
        const val MAX_KEEP = 1000

        private fun ResticKeep.valid() = listOf(last, daily, weekly, monthly, yearly).all { it == null || it in 1..MAX_KEEP }

        private const val MAX_PATH_LENGTH = 4096

        // how much of a path an error shows
        private const val MAX_SHOWN = 80

        // an absolute path as restic keeps it in a snapshot (filepath.Clean): no '/' at its end, no
        // empty, '.' or '..' part (BK-11); anything else stays as it is, for isPath to refuse
        fun normalized(path: String): String {
            if (!path.startsWith("/")) return path
            val parts = ArrayDeque<String>()
            path.split('/').forEach { part ->
                when (part) {
                    "", "." -> {}
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            return parts.joinToString("/", prefix = "/")
        }

        // an absolute path of the computer, without control characters
        private fun isPath(path: String) = path.startsWith("/") && path.length <= MAX_PATH_LENGTH && path.none { it.isISOControl() }
    }
}
