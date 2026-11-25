package io.pelle.sak.backend.restic

import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.pelle.sak.backend.respondDeleted
import io.ktor.server.routing.delete
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveStream
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.apiTime
import io.pelle.sak.api.errors
import io.pelle.sak.api.idParameter
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.api.respondErrors
import io.pelle.sak.core.RateLimiter
import io.pelle.sak.api.clientNetwork
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.restic.model.ResticBackupPlanRequest
import io.pelle.sak.backend.restic.model.ResticBackupPlanResponse
import io.pelle.sak.backend.restic.model.ResticRepositoryRequest
import io.pelle.sak.backend.restic.model.ResticRepositoryResponse
import io.pelle.sak.backend.restic.model.ResticTokenResponse
import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.restic.ResticExcludes
import io.pelle.sak.model.restic.ResticBackupPlan
import io.pelle.sak.model.restic.ResticBackupPlanValues
import io.pelle.sak.model.restic.ResticKeep
import io.pelle.sak.model.restic.ResticRepository
import io.pelle.sak.api.storage.model.EntityTooLargeException
import io.pelle.sak.api.storage.model.InsufficientStorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Base64

private const val RESTIC_TAG = "restic"

// where the REST backend of restic is, at api.<base domain> (SERVER-7) and below the backend
const val RESTIC_PATH_PREFIX = "/restic"

// the most caddy lets through to the REST backend of restic: its largest file, with room for the
// headers
const val RESTIC_MAX_BODY_SIZE = ResticStore.MAX_FILE_BYTES + 1024L * 1024

// the answer of a listing in version 2 of the REST protocol: names with their sizes
private val V2 = ContentType("application", "vnd.x.restic.rest.v2")

private val V1 = ContentType("application", "vnd.x.restic.rest.v1")

// the url restic takes for a repository: the token is the password of the user named like its id
fun resticUrl(
    apiHost: String,
    id: Int,
) = "rest:https://$apiHost$RESTIC_PATH_PREFIX/$id/"

private fun ResticRepository.response(
    apiHost: String,
    usage: Long,
) = ResticRepositoryResponse(id, name, description, resticUrl(apiHost, id), usage, createdAt.apiTime())

private fun ResticBackupPlan.response() =
    ResticBackupPlanResponse(
        id,
        name,
        repositoryId,
        host,
        paths,
        createdAt.apiTime(),
        updatedAt?.apiTime(),
        keep.last,
        keep.daily,
        keep.weekly,
        keep.monthly,
        keep.yearly,
        excludes.patterns,
        excludes.caches,
    )

private fun ResticBackupPlanRequest.values() =
    ResticBackupPlanValues(
        name,
        repositoryId,
        host,
        paths,
        ResticKeep(keepLast, keepDaily, keepWeekly, keepMonthly, keepYearly),
        ResticExcludes(excludes, excludeCaches),
    )

private suspend fun ApplicationCall.respondPlan(
    result: ResticBackupPlanSaveResult,
    created: Boolean = false,
) = when (result) {
    is ResticBackupPlanSaveResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.plan.response())
    is ResticBackupPlanSaveResult.Invalid -> respondErrors(result.errors)
    ResticBackupPlanSaveResult.NotFound -> respond(HttpStatusCode.NotFound)
    ResticBackupPlanSaveResult.UnknownRepository -> respondErrors(ResticTexts.UNKNOWN_REPOSITORY, status = HttpStatusCode.UnprocessableEntity)
    ResticBackupPlanSaveResult.Exists -> respondErrors(ResticTexts.PLAN_EXISTS, status = HttpStatusCode.Conflict)
}

// the most a request of a backup plan has: its excludes (ResticBackupPlanService.MAX_EXCLUDES_BYTES)
// with room for its paths and the rest
const val PLAN_MAX_BODY_SIZE = 128L * 1024

// the repositories of restic of the signed in user (BACKUP-1, BACKUP-2) and the backup plans into them
// (BACKUP-5, BACKUP-6), meant to be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.restic(
    service: ResticRepositoryService,
    plans: ResticBackupPlanService,
    apiHost: String,
) {
    route("/plans") {
        // a plan has up to 64 KiB of excludes, more than the 16 KiB of the other json bodies
        install(RequestBodyLimit) { bodyLimit { PLAN_MAX_BODY_SIZE } }
        get {
            val page = call.pageRequest() ?: return@get
            val all = withContext(Dispatchers.IO) { plans.plans(call.owner) }
            call.respond(page.of(page.of(all.map { it.response() })))
        }.describe {
            tag(RESTIC_TAG)
            summary = "The backup plans of restic"
            description = "The backup plans of the signed in user, of every computer, by name."
            parameters { pagination() }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the backup plans"
                    schema = jsonSchema<PageResponse<ResticBackupPlanResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
            }
        }

        post {
            val request = call.receive<ResticBackupPlanRequest>()
            call.respondPlan(withContext(Dispatchers.IO) { plans.create(call.owner, request.values()) }, created = true)
        }.describe {
            tag(RESTIC_TAG)
            summary = "Create a backup plan of restic"
            description = "A repository of the user and the absolute paths of one computer, named by its host name."
            requestBody {
                required = true
                schema = jsonSchema<ResticBackupPlanRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new backup plan"
                    schema = jsonSchema<ResticBackupPlanResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name, host or path, or a malformed request")
                errors(HttpStatusCode.Conflict, "The user has a backup plan of the name")
                errors(HttpStatusCode.UnprocessableEntity, "The repository is none of the user")
            }
        }

        get("/{id}") {
            val id = call.idParameter() ?: return@get
            when (val plan = withContext(Dispatchers.IO) { plans.plan(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(plan.response())
            }
        }.describe {
            tag(RESTIC_TAG)
            summary = "A backup plan of restic"
            parameters { planId() }
            responses {
                HttpStatusCode.OK {
                    description = "The backup plan"
                    schema = jsonSchema<ResticBackupPlanResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user has no such backup plan")
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<ResticBackupPlanRequest>()
            call.respondPlan(withContext(Dispatchers.IO) { plans.update(call.owner, id, request.values()) })
        }.describe {
            tag(RESTIC_TAG)
            summary = "Change a backup plan of restic"
            description = "Replaces all values of a backup plan of the user, its paths as a whole."
            parameters { planId() }
            requestBody {
                required = true
                schema = jsonSchema<ResticBackupPlanRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The changed backup plan"
                    schema = jsonSchema<ResticBackupPlanResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid name, host or path, or a malformed request")
                errors(HttpStatusCode.NotFound, "The user has no such backup plan")
                errors(HttpStatusCode.Conflict, "The user has another backup plan of the name")
                errors(HttpStatusCode.UnprocessableEntity, "The repository is none of the user")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            call.respondDeleted(withContext(Dispatchers.IO) { plans.delete(call.owner, id) })
        }.describe {
            tag(RESTIC_TAG)
            summary = "Delete a backup plan of restic"
            description = "Only its owner deletes a backup plan. Its repository and the snapshots in it stay."
            parameters { planId() }
            responses {
                HttpStatusCode.NoContent { description = "The backup plan is gone" }
                errors(HttpStatusCode.NotFound, "The user has no such backup plan")
            }
        }
    }

    route("/repositories") {
        get {
            val page = call.pageRequest() ?: return@get
            // cut before the sizes are looked up
            val repositories = withContext(Dispatchers.IO) { page.of(service.repositories(call.owner)).map { it.response(apiHost, service.usage(it)) } }
            call.respond(page.of(repositories))
        }.describe {
            tag(RESTIC_TAG)
            summary = "The repositories of restic"
            description = "The repositories of the signed in user, by name, with the bytes they take, without their tokens."
            parameters { pagination() }
            responses {
                HttpStatusCode.OK {
                    description = "A page of the repositories"
                    schema = jsonSchema<PageResponse<ResticRepositoryResponse>>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid page")
            }
        }

        post {
            val request = call.receive<ResticRepositoryRequest>()
            val description = request.description ?: request.name
            if (description == null) {
                call.respondErrors(ErrorMessage(ResticTexts.INVALID_DESCRIPTION, "max" to ResticRepositoryService.MAX_DESCRIPTION))
                return@post
            }
            when (val created = withContext(Dispatchers.IO) { service.create(call.owner, description) }) {
                is ResticRepositoryCreateResult.Created -> call.respond(HttpStatusCode.Created, created.repository.response(apiHost, withContext(Dispatchers.IO) { service.usage(created.repository) }))
                is ResticRepositoryCreateResult.Invalid -> call.respondErrors(created.errors)
                is ResticRepositoryCreateResult.Exists ->
                    call.respondErrors(ErrorMessage(ResticTexts.REPOSITORY_EXISTS, "name" to created.name), status = HttpStatusCode.Conflict)
            }
        }.describe {
            tag(RESTIC_TAG)
            summary = "Create a repository of restic"
            description =
                "A repository the instance keeps and serves with the REST protocol of restic, with a new token as its only " +
                "login. Its name, what the command line takes, is derived from the description: lower case, umlauts " +
                "written out, a-z, 0-9, - and _. Neither changes afterwards. restic initializes it (restic init) with a " +
                "password the instance never gets."
            requestBody {
                required = true
                schema = jsonSchema<ResticRepositoryRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new repository"
                    schema = jsonSchema<ResticRepositoryResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid description, one that gives no name, or a malformed request")
                errors(HttpStatusCode.Conflict, "The user has a repository of the name the description gives")
            }
        }

        get("/{id}/token") {
            val id = call.idParameter() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (val token = withContext(Dispatchers.IO) { service.token(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(ResticTokenResponse(token))
            }
        }.describe {
            tag(RESTIC_TAG)
            summary = "The token of a repository of restic"
            description = "The only login of the REST backend of the repository: the password of the user named like its id."
            parameters {
                path("id") {
                    description = "The id of the repository"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.OK {
                    description = "The token"
                    schema = jsonSchema<ResticTokenResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user has no such repository")
            }
        }

        delete("/{id}") {
            val id = call.idParameter() ?: return@delete
            call.respondDeleted(withContext(Dispatchers.IO) { service.delete(call.owner, id) })
        }.describe {
            tag(RESTIC_TAG)
            summary = "Delete a repository of restic"
            description = "Only its owner deletes a repository. Its backup plans of every computer go with it, its token is refused from then on; its files stay in the storage until it is purged (BACKUP-9)."
            parameters {
                path("id") {
                    description = "The id of the repository"
                    required = true
                    schema = jsonSchema<Int>()
                }
            }
            responses {
                HttpStatusCode.NoContent { description = "The repository is gone" }
                errors(HttpStatusCode.NotFound, "The user has no such repository")
            }
        }
    }
}

private fun io.ktor.openapi.Parameters.Builder.planId() =
    path("id") {
        description = "The id of the backup plan"
        required = true
        schema = jsonSchema<Int>()
    }

// the REST backend of restic for the repositories of the instance (BACKUP-1), version 1 and 2 of its
// protocol, below /restic/<id>/: each request with the token of the repository as the password of
// the user <id> (BACKUP-2); the files of the repository are below its resource id (MODEL-8). A client
// that failed too often at a repository is refused there, unless its login is valid (BK-17)
fun Route.resticRest(
    service: ResticRepositoryService,
    store: ResticStore,
    failures: RateLimiter = RateLimiter(30, Duration.ofHours(1)),
) {
    route("/{id}/{path...}") {
        handle {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.NotFound)
                return@handle
            }
            val login = basicLogin(call)
            val resourceId = if (login == null || login.first != id.toString()) null else withContext(Dispatchers.IO) { service.authorize(id, login.second) }
            if (resourceId == null) {
                // counted per repository and address: a computer with a token that is gone does not
                // lock out the others behind the address, and a valid login always goes through (BK-17)
                val key = "$id ${call.clientNetwork()}"
                if (failures.exhausted(key)) return@handle call.respond(HttpStatusCode.TooManyRequests)
                failures.tryAcquire(key)
                call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"restic\"")
                call.respond(HttpStatusCode.Unauthorized)
                return@handle
            }
            ResticRequest(call, resourceId, store).handle(call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() })
        }
    }
}

// the user and the password of a basic login, null for none or a malformed one
private fun basicLogin(call: ApplicationCall): Pair<String, String>? {
    val header = call.request.headers[HttpHeaders.Authorization] ?: return null
    if (!header.startsWith("Basic ", ignoreCase = true)) return null
    val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim())) }.getOrNull() ?: return null
    val user = decoded.substringBefore(':', "")
    val password = decoded.substringAfter(':', "")
    return if (user.isEmpty() || password.isEmpty()) null else user to password
}

// a request of a repository whose login was checked; its files are below `repository`
private class ResticRequest(
    private val call: ApplicationCall,
    private val repository: ResourceId,
    private val store: ResticStore,
) {
    suspend fun handle(path: List<String>) {
        val method = call.request.httpMethod
        when {
            path.isEmpty() && method == HttpMethod.Post && call.request.queryParameters["create"] == "true" ->
                // the directories of a repository come with its first file
                call.respond(HttpStatusCode.OK)
            path == listOf(ResticFile.CONFIG) -> file(ResticFile.config, method)
            path.size == 1 && path[0] in ResticFile.TYPES && method == HttpMethod.Get -> list(path[0])
            path.size == 2 && path[0] in ResticFile.TYPES && ResticFile.NAME.matches(path[1]) -> file(ResticFile(path[0], path[1]), method)
            else -> call.respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun file(
        file: ResticFile,
        method: HttpMethod,
    ) = when (method) {
        HttpMethod.Head -> head(file)
        HttpMethod.Get -> get(file)
        HttpMethod.Post -> save(file)
        HttpMethod.Delete ->
            call.respond(if (io { store.delete(repository, file) }) HttpStatusCode.OK else HttpStatusCode.NotFound)
        else -> call.respond(HttpStatusCode.MethodNotAllowed)
    }

    // written entry by entry while the directory is walked, so memory does not grow with the
    // repository (BK-15); a name is 64 hex digits, nothing in it needs escaping
    private suspend fun list(type: String) {
        val v2 = call.request.headers[HttpHeaders.Accept]?.contains(V2.toString()) == true
        call.respondTextWriter(if (v2) V2 else V1) {
            // the walk and the writes block
            withContext(Dispatchers.IO) {
                write("[")
                var first = true
                store.forEach(repository, type) { name, size ->
                    if (!first) write(",")
                    first = false
                    write(if (v2) "{\"name\":\"$name\",\"size\":$size}" else "\"$name\"")
                }
                write("]")
            }
        }
    }

    private suspend fun head(file: ResticFile) {
        val size = io { store.size(repository, file) } ?: return call.respond(HttpStatusCode.NotFound)
        call.respond(
            object : OutgoingContent.NoContent() {
                override val contentType = ContentType.Application.OctetStream
                override val contentLength = size
            },
        )
    }

    private suspend fun get(file: ResticFile) {
        val size = io { store.size(repository, file) } ?: return call.respond(HttpStatusCode.NotFound)
        val range = call.request.headers[HttpHeaders.Range]?.let { range(it, size) }
        if (range != null && (range.isEmpty() || range.first >= size)) {
            call.response.header(HttpHeaders.ContentRange, "bytes */$size")
            return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val (offset, length) = range?.let { it.first to it.last - it.first + 1 } ?: (0L to size)
        // null when the file is gone or another took its place since its size was taken
        val content = io { store.open(repository, file, offset, length, size) } ?: return call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (range != null) call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
        call.respondOutputStream(ContentType.Application.OctetStream, if (range == null) HttpStatusCode.OK else HttpStatusCode.PartialContent, length) {
            content.use { it.copyTo(this) }
        }
    }

    private suspend fun save(file: ResticFile) {
        val content = call.receiveStream()
        val result =
            try {
                io { store.save(repository, file, content) }
            } catch (_: EntityTooLargeException) {
                return call.respond(HttpStatusCode.PayloadTooLarge)
            } catch (_: InsufficientStorageException) {
                return call.respond(HttpStatusCode.InsufficientStorage)
            }
        call.respond(
            when (result) {
                ResticFileSaveResult.Saved -> HttpStatusCode.OK
                ResticFileSaveResult.Exists, ResticFileSaveResult.NotEmpty -> HttpStatusCode.Forbidden
                ResticFileSaveResult.Damaged -> HttpStatusCode.BadRequest
            },
        )
    }

    // a range of one part, bytes=<first>-[<last>] or bytes=-<suffix>; null for a header that is
    // none, which is answered with the whole file
    private fun range(
        header: String,
        size: Long,
    ): LongRange? {
        val spec = header.trim().takeIf { it.startsWith("bytes=") && ',' !in it }?.removePrefix("bytes=") ?: return null
        val first = spec.substringBefore('-').trim()
        val last = spec.substringAfter('-', "").trim()
        return when {
            first.isEmpty() -> last.toLongOrNull()?.takeIf { it > 0 }?.let { maxOf(0, size - it) until size }
            else ->
                first.toLongOrNull()?.let { start ->
                    val end = if (last.isEmpty()) size - 1 else last.toLongOrNull()?.let { minOf(it, size - 1) }
                    end?.let { start..it }
                }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
