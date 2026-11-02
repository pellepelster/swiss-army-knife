package io.pelle.sak.backend.users

import io.github.oshai.kotlinlogging.KotlinLogging
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
import io.pelle.sak.api.language
import io.pelle.sak.api.respondErrors
import io.pelle.sak.auth.owner
import io.pelle.sak.backend.users.model.AppLinkRequest
import io.pelle.sak.backend.users.model.UserRequest
import io.pelle.sak.backend.users.model.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val USERS_TAG = "users"

private fun UserEntry.response() = UserResponse(id, email, name, configured)

private val logger = KotlinLogging.logger {}

// the users who may sign in, meant to be mounted behind the authentication with the role admin;
// without a mail server (single user mode) no link of the grocery app is sent
@OptIn(ExperimentalKtorApi::class)
fun Route.users(
    userService: UserService,
    appLinks: AppLinkMailer?,
) {
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

    post("/app-link") {
        val email = UserService.normalize(call.receive<AppLinkRequest>().email)
        if (appLinks == null) {
            call.respondErrors(UsersTexts.NO_MAIL_SERVER, status = HttpStatusCode.Conflict)
            return@post
        }
        if (withContext(Dispatchers.IO) { userService.users() }.none { it.email == email }) {
            call.respondErrors(UsersTexts.NOT_A_USER, status = HttpStatusCode.NotFound)
            return@post
        }
        // the mail in the language of the admin who sends it, the language of the user is not known
        val language = call.language()
        try {
            withContext(Dispatchers.IO) { appLinks.send(email, language) }
            logger.info { "sent the link of the grocery app to '$email'" }
            call.respond(HttpStatusCode.NoContent)
        } catch (e: Exception) {
            logger.warn(e) { "could not send the link of the grocery app to '$email'" }
            call.respondErrors(UsersTexts.APP_LINK_NOT_SENT, status = HttpStatusCode.BadGateway)
        }
    }.describe {
        tag(USERS_TAG)
        summary = "Send the link of the grocery app"
        description =
            "Sends a user or an admin of the deployment an e-mail with the link to the page that downloads the Android app " +
            "of the grocery lists ($GROCERY_APP_DOWNLOAD_PATH), in the language of the request."
        requestBody {
            required = true
            schema = jsonSchema<AppLinkRequest>()
        }
        responses {
            HttpStatusCode.NoContent { description = "The e-mail is sent" }
            errors(HttpStatusCode.NotFound, "The address is no user or admin of the deployment")
            errors(HttpStatusCode.Conflict, "The deployment has no mail server")
            errors(HttpStatusCode.BadGateway, "The mail server did not take the e-mail")
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
            errors(HttpStatusCode.NotFound, "No such added user; an admin of the configuration is not deleted here")
        }
    }
}
