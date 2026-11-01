package io.pelle.sak.backend.users

import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.utils.io.ExperimentalKtorApi
import io.pelle.sak.api.errors
import io.pelle.sak.api.idParameter
import io.pelle.sak.api.installApi
import io.pelle.sak.api.model.PageResponse
import io.pelle.sak.api.pageRequest
import io.pelle.sak.api.pagination
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.users.model.UserRequest
import io.pelle.sak.backend.users.model.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val USERS_TAG = "users"

private fun UserEntry.response() = UserResponse(id, email, name, configured)

// the users who may sign in, meant to be mounted behind the authentication with the role admin
@OptIn(ExperimentalKtorApi::class)
fun Route.users(userService: UserService) {
    installApi()

    get {
        val page = call.pageRequest() ?: return@get
        val users = withContext(Dispatchers.IO) { userService.users() }
        call.respond(page.of(page.of(users.map { it.response() })))
    }.describe {
        tag(USERS_TAG)
        summary = "The users"
        description = "The admins of the configuration of the deployment first, then the users an admin added, by address."
        parameters { pagination() }
        responses {
            HttpStatusCode.OK {
                description = "A page of the users"
                schema = jsonSchema<PageResponse<UserResponse>>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid page")
        }
    }

    post {
        val request = call.receive<UserRequest>()
        when (val result = withContext(Dispatchers.IO) { userService.add(call.owner, request.email, request.name) }) {
            is AddUserResult.Added ->
                call.respond(HttpStatusCode.Created, UserResponse(result.user.id, result.user.email, result.user.name, configured = false))
            is AddUserResult.Invalid -> call.respondErrors(result.errors)
            AddUserResult.Exists -> call.respondErrors(UsersTexts.EXISTS, status = HttpStatusCode.Conflict)
            AddUserResult.Admin -> call.respondErrors(UsersTexts.IS_ADMIN, status = HttpStatusCode.Conflict)
        }
    }.describe {
        tag(USERS_TAG)
        summary = "Add a user"
        description = "The user signs in with a link by e-mail and uses the notes and the grocery lists (the role user). No mail is sent."
        requestBody {
            required = true
            schema = jsonSchema<UserRequest>()
        }
        responses {
            HttpStatusCode.Created {
                description = "The added user"
                schema = jsonSchema<UserResponse>()
            }
            errors(HttpStatusCode.BadRequest, "An invalid address or name, or a malformed request")
            errors(HttpStatusCode.Conflict, "A user of the address exists, or it is an admin of the configuration")
        }
    }

    delete("/{id}") {
        val id = call.idParameter() ?: return@delete
        val deleted = withContext(Dispatchers.IO) { userService.delete(id) }
        call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }.describe {
        tag(USERS_TAG)
        summary = "Delete a user"
        description = "The user can no longer sign in, their sessions end with their next request; their data stays."
        parameters {
            path("id") {
                description = "The id of the added user"
                required = true
                schema = jsonSchema<Int>()
            }
        }
        responses {
            HttpStatusCode.NoContent { description = "The user is deleted" }
            HttpStatusCode.NotFound { description = "No such added user; an admin of the configuration is not deleted here" }
        }
    }
}
