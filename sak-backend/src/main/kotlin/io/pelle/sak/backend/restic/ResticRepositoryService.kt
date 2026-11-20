package io.pelle.sak.backend.restic

import io.pelle.sak.api.Catalog
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.restic.ResticEnvValue
import io.pelle.sak.model.restic.ResticInsert
import io.pelle.sak.model.restic.ResticRepository
import io.pelle.sak.model.restic.ResticRepositoryRepository
import io.pelle.sak.model.restic.ResticRepositoryValues
import io.pelle.sak.model.restic.ResticUpdate
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock

// one variable of the environment as a request gives it: exactly one of a value, a secret, or kept
data class ResticEnvInput(
    val name: String,
    val value: String? = null,
    val secret: String? = null,
    val kept: Boolean = false,
) {
    override fun toString() = "ResticEnvInput($name, value=$value, secret=${secret?.let { "…" }}, kept=$kept)"
}

// a repository as a request gives it, before it is checked
data class ResticInput(
    val name: String,
    val shared: Boolean,
    val url: String,
    val env: List<ResticEnvInput>,
    val options: List<Pair<String, String>>,
    val upload: String?,
    val download: String?,
)

sealed interface ResticResult {
    data class Saved(
        val repository: ResticRepository,
    ) : ResticResult

    data class Invalid(
        val errors: List<ErrorMessage>,
    ) : ResticResult

    // the user sees no such repository
    data object NotFound : ResticResult

    // a shared repository of another user: only its owner changes it
    data object NotOwner : ResticResult

    // the user has a repository of the name
    data object Exists : ResticResult
}

// the repositories of restic, the only way to them; it checks the values (BACKUP-1 to BACKUP-3,
// BACKUP-6), ResticRepositoryRepository who sees and changes what (MODEL-6)
class ResticRepositoryService(
    private val repository: ResticRepositoryRepository,
) {
    constructor(
        database: SakDatabase,
        clock: Clock = Clock.systemUTC(),
    ) : this(ResticRepositoryRepository(database, clock))

    // the repositories of the user and the shared ones of the others, by name
    fun repositories(user: String): List<ResticRepository> = repository.visible(user)

    fun repository(
        user: String,
        id: Int,
    ): ResticRepository? = repository.byId(user, id)

    // the values of the secrets of the connection, for whoever sees the repository (BACKUP-5, BACKUP-6)
    fun secrets(
        user: String,
        id: Int,
    ): Map<String, String>? = repository.secrets(user, id)

    fun create(
        user: String,
        input: ResticInput,
    ): ResticResult {
        val (values, errors) = values(input, kept = emptyMap())
        if (values == null) return ResticResult.Invalid(errors)
        return when (val inserted = repository.insert(user, values)) {
            is ResticInsert.Inserted -> ResticResult.Saved(inserted.repository)
            ResticInsert.NameTaken -> ResticResult.Exists
        }
    }

    // replaces the values of a repository of the user; a secret marked kept keeps its value
    fun update(
        user: String,
        id: Int,
        input: ResticInput,
    ): ResticResult {
        val existing = repository.byId(user, id) ?: return ResticResult.NotFound
        if (existing.owner != user) return ResticResult.NotOwner
        val (values, errors) = values(input, kept = repository.secrets(user, id).orEmpty())
        if (values == null) return ResticResult.Invalid(errors)
        return when (val updated = repository.update(user, id, values)) {
            is ResticUpdate.Updated -> ResticResult.Saved(updated.repository)
            ResticUpdate.NotFound -> ResticResult.NotFound
            ResticUpdate.NotOwner -> ResticResult.NotOwner
            ResticUpdate.NameTaken -> ResticResult.Exists
        }
    }

    companion object {
        private val NAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        private const val MAX_URL_LENGTH = 1024

        private const val MAX_VALUE_LENGTH = 1024

        private val RATE = Regex("^([1-9][0-9]{0,6})(KiB|MiB|GiB)/s$")

        private val UNITS = mapOf("KiB" to 1, "MiB" to 1024, "GiB" to 1024 * 1024)

        // the most a limit may be, in KiB/s
        private const val MAX_RATE_KIB = 10 * 1024 * 1024

        // what restic needs to reach an s3 repository, nothing that starts a program (BACKUP-3)
        val S3_ENV = setOf("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_DEFAULT_REGION", "AWS_PROFILE")

        // the options of an s3 repository and the values each takes
        val S3_OPTIONS: Map<String, Regex> =
            mapOf(
                "s3.storage-class" to Regex("^[A-Z_]{1,32}$"),
                "s3.region" to Regex("^[a-z0-9-]{1,32}$"),
                "s3.bucket-lookup" to Regex("^(auto|dns|path)$"),
                "s3.list-objects-v1" to Regex("^(true|false)$"),
            )

        // the url as it is stored: a local path as it is, an s3 one as s3:https://<host>[:<port>]/<bucket>...
        // (BACKUP-2); null with the error when it is neither
        fun url(url: String): Pair<Location?, ErrorMessage?> {
            val text = url.trim()
            if (text.isEmpty() || text.length > MAX_URL_LENGTH || text.any { it.isISOControl() }) {
                return null to ErrorMessage(ResticTexts.INVALID_URL)
            }
            val local = text.removePrefix("local:")
            if (local.startsWith("/")) return Location(local, s3 = false) to null
            if (!text.startsWith("s3:")) return null to ErrorMessage(ResticTexts.INVALID_URL)
            val rest = text.removePrefix("s3:")
            // the secrets of the connection travel over https only
            if (rest.startsWith("http://")) return null to ErrorMessage(ResticTexts.INVALID_URL)
            val uri =
                try {
                    URI(if ("://" in rest) rest else "https://$rest")
                } catch (_: URISyntaxException) {
                    return null to ErrorMessage(ResticTexts.INVALID_URL)
                }
            if (uri.rawUserInfo != null) return null to ErrorMessage(ResticTexts.LOGIN_IN_URL)
            val bucket = uri.rawPath.orEmpty().removePrefix("/").substringBefore('/')
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || bucket.isEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
                return null to ErrorMessage(ResticTexts.INVALID_URL)
            }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return Location("s3:https://${uri.host}$port${uri.rawPath.trimEnd('/')}", s3 = true) to null
        }

        // KiB/s of a rate like 20MiB/s, null for none
        fun rate(rate: String?): Result<Int?> {
            val text = rate?.trim()?.ifEmpty { null } ?: return Result.success(null)
            val match = RATE.matchEntire(text) ?: return Result.failure(IllegalArgumentException(text))
            val kib = match.groupValues[1].toLong() * UNITS.getValue(match.groupValues[2])
            return if (kib > MAX_RATE_KIB) Result.failure(IllegalArgumentException(text)) else Result.success(kib.toInt())
        }

        // a rate of KiB/s in the largest unit it is a whole number of
        fun rate(kib: Int?): String? =
            kib?.let {
                UNITS.entries
                    .sortedByDescending { it.value }
                    .first { (_, size) -> kib % size == 0 }
                    .let { (unit, size) -> "${kib / size}$unit/s" }
            }

        private fun plain(value: String) = value.length <= MAX_VALUE_LENGTH && value.none { it.isISOControl() }
    }

    // where a repository is, and whether it is an s3 bucket
    data class Location(
        val url: String,
        val s3: Boolean,
    )

    // the values to store, or why there are none; `kept` are the secrets of the repository so far
    private fun values(
        input: ResticInput,
        kept: Map<String, String>,
    ): Pair<ResticRepositoryValues?, List<ErrorMessage>> {
        val errors = mutableListOf<ErrorMessage>()
        val name = input.name.trim()
        if (!NAME.matches(name)) errors += ErrorMessage(ResticTexts.INVALID_NAME)
        val (location, urlError) = url(input.url)
        urlError?.let { errors += it }
        val s3 = location?.s3 == true
        if (location != null && !s3 && input.shared) errors += ErrorMessage(ResticTexts.LOCAL_NOT_SHARED)

        val env = mutableListOf<ResticEnvValue>()
        for (variable in input.env) {
            val given = listOfNotNull(variable.value, variable.secret, variable.kept.takeIf { it }?.toString())
            when {
                location != null && (!s3 || variable.name !in S3_ENV) -> errors += ErrorMessage(ResticTexts.UNKNOWN_ENV, "name" to variable.name)
                given.size != 1 -> errors += ErrorMessage(ResticTexts.INVALID_ENV, "name" to variable.name)
                variable.kept ->
                    kept[variable.name]?.let { env += ResticEnvValue(variable.name, it, secret = true) }
                        ?: run { errors += ErrorMessage(ResticTexts.NOTHING_KEPT, "name" to variable.name) }
                !plain(given.single()) -> errors += ErrorMessage(ResticTexts.INVALID_VALUE, "name" to variable.name)
                else -> env += ResticEnvValue(variable.name, given.single(), secret = variable.secret != null)
            }
        }

        for ((option, value) in input.options) {
            val allowed = S3_OPTIONS[option]
            when {
                location != null && (!s3 || allowed == null) -> errors += ErrorMessage(ResticTexts.UNKNOWN_OPTION, "name" to option)
                allowed != null && !allowed.matches(value) -> errors += ErrorMessage(ResticTexts.INVALID_VALUE, "name" to option)
            }
        }

        val upload = rate(input.upload)
        val download = rate(input.download)
        if (upload.isFailure) errors += ErrorMessage(ResticTexts.INVALID_LIMIT, "limit" to input.upload)
        if (download.isFailure) errors += ErrorMessage(ResticTexts.INVALID_LIMIT, "limit" to input.download)

        if (errors.isNotEmpty() || location == null) return null to errors
        val options = input.options.map { (option, value) -> option to value }
        return ResticRepositoryValues(name, input.shared, location.url, env, options, upload.getOrNull(), download.getOrNull()) to errors
    }
}

// the texts of the repositories of restic (backend.restic in i18n/texts.yml)
object ResticTexts : Catalog("restic") {
    val INVALID_NAME = text("invalid_name")
    val INVALID_URL = text("invalid_url")
    val LOGIN_IN_URL = text("login_in_url")
    val LOCAL_NOT_SHARED = text("local_not_shared")
    val UNKNOWN_ENV = text("unknown_env")
    val INVALID_ENV = text("invalid_env")
    val NOTHING_KEPT = text("nothing_kept")
    val UNKNOWN_OPTION = text("unknown_option")
    val INVALID_VALUE = text("invalid_value")
    val INVALID_LIMIT = text("invalid_limit")
    val REPOSITORY_EXISTS = text("repository_exists")
    val ONLY_OWNER_CHANGES = text("only_owner_changes")
}
