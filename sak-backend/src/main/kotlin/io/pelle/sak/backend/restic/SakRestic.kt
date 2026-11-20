package io.pelle.sak.backend.restic

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
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
import io.pelle.sak.api.installApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.restic.model.ResticEnvResponse
import io.pelle.sak.backend.restic.model.ResticLimitsResponse
import io.pelle.sak.backend.restic.model.ResticRepositoryRequest
import io.pelle.sak.backend.restic.model.ResticRepositoryResponse
import io.pelle.sak.backend.restic.model.ResticSecretsResponse
import io.pelle.sak.model.restic.ResticRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RESTIC_TAG = "restic"

private fun ResticRepository.response(user: String) =
    ResticRepositoryResponse(
        id,
        name,
        shared,
        owner == user,
        url,
        env.associate { it.name to ResticEnvResponse(it.value, it.secret) },
        options.toMap(),
        ResticLimitsResponse(ResticRepositoryService.rate(limitUploadKib), ResticRepositoryService.rate(limitDownloadKib)),
        createdAt.apiTime(),
        updatedAt?.apiTime(),
    )

private fun ResticRepositoryRequest.input() =
    ResticInput(
        name,
        shared,
        url,
        env.map { (name, value) -> ResticEnvInput(name, value.value, value.secret, value.kept) },
        options.toList(),
        limits.upload,
        limits.download,
    )

private suspend fun ApplicationCall.respondRepository(
    result: ResticResult,
    created: Boolean = false,
) = when (result) {
    is ResticResult.Saved -> respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, result.repository.response(owner))
    is ResticResult.Invalid -> respondErrors(result.errors)
    ResticResult.NotFound -> respond(HttpStatusCode.NotFound)
    ResticResult.NotOwner -> respondErrors(ResticTexts.ONLY_OWNER_CHANGES, status = HttpStatusCode.Forbidden)
    ResticResult.Exists -> respondErrors(ResticTexts.REPOSITORY_EXISTS, status = HttpStatusCode.Conflict)
}

// the repositories of restic of the signed in user and the shared ones of the others
// (BACKUP-1 to BACKUP-6), meant to be mounted behind the authentication
@OptIn(ExperimentalKtorApi::class)
fun Route.restic(service: ResticRepositoryService) {
    installApi()

    route("/repositories") {
        get {
            val page = call.pageRequest() ?: return@get
            val repositories = withContext(Dispatchers.IO) { service.repositories(call.owner) }
            call.respond(page.of(page.of(repositories.map { it.response(call.owner) })))
        }.describe {
            tag(RESTIC_TAG)
            summary = "The repositories of restic"
            description = "The repositories of the signed in user and the shared ones of the others, by name; never the values of their secrets."
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
            call.respondRepository(withContext(Dispatchers.IO) { service.create(call.owner, request.input()) }, created = true)
        }.describe {
            tag(RESTIC_TAG)
            summary = "Create a repository of restic"
            description =
                "A local path or an s3 bucket, with the environment and the options restic needs to reach it; only the names " +
                "of its kind are taken. There is no password: restic gets it on the computer that runs it."
            requestBody {
                required = true
                schema = jsonSchema<ResticRepositoryRequest>()
            }
            responses {
                HttpStatusCode.Created {
                    description = "The new repository"
                    schema = jsonSchema<ResticRepositoryResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid value, or a malformed request, e.g. one with a password")
                errors(HttpStatusCode.Conflict, "The user has a repository of the name")
            }
        }

        get("/{id}") {
            val id = call.idParameter() ?: return@get
            when (val repository = withContext(Dispatchers.IO) { service.repository(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(repository.response(call.owner))
            }
        }.describe {
            tag(RESTIC_TAG)
            summary = "A repository of restic"
            description = "A repository of the signed in user or a shared one of another user, without the values of its secrets."
            parameters { repositoryId() }
            responses {
                HttpStatusCode.OK {
                    description = "The repository"
                    schema = jsonSchema<ResticRepositoryResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such repository")
            }
        }

        put("/{id}") {
            val id = call.idParameter() ?: return@put
            val request = call.receive<ResticRepositoryRequest>()
            call.respondRepository(withContext(Dispatchers.IO) { service.update(call.owner, id, request.input()) })
        }.describe {
            tag(RESTIC_TAG)
            summary = "Change a repository of restic"
            description =
                "Replaces all values of a repository of the signed in user; a secret given as kept keeps its value. Only the " +
                "owner changes a repository."
            parameters { repositoryId() }
            requestBody {
                required = true
                schema = jsonSchema<ResticRepositoryRequest>()
            }
            responses {
                HttpStatusCode.OK {
                    description = "The changed repository"
                    schema = jsonSchema<ResticRepositoryResponse>()
                }
                errors(HttpStatusCode.BadRequest, "An invalid value, or a malformed request")
                errors(HttpStatusCode.Forbidden, "A shared repository of another user")
                errors(HttpStatusCode.NotFound, "The user sees no such repository")
                errors(HttpStatusCode.Conflict, "The user has another repository of the name")
            }
        }

        get("/{id}/secrets") {
            val id = call.idParameter() ?: return@get
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (val secrets = withContext(Dispatchers.IO) { service.secrets(call.owner, id) }) {
                null -> call.respond(HttpStatusCode.NotFound)
                else -> call.respond(ResticSecretsResponse(secrets))
            }
        }.describe {
            tag(RESTIC_TAG)
            summary = "The secrets of the connection of a repository"
            description =
                "The values of the secrets of the environment, for whoever sees the repository: the app that runs restic needs " +
                "them to reach it (BACKUP-5, BACKUP-6)."
            parameters { repositoryId() }
            responses {
                HttpStatusCode.OK {
                    description = "The secrets by name"
                    schema = jsonSchema<ResticSecretsResponse>()
                }
                errors(HttpStatusCode.NotFound, "The user sees no such repository")
            }
        }
    }
}

private fun Parameters.Builder.repositoryId() =
    path("id") {
        description = "The id of the repository"
        required = true
        schema = jsonSchema<Int>()
    }
