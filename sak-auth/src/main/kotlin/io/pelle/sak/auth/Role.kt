package io.pelle.sak.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.routing.Route
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

// what a signed in address may do, the session token names its roles
enum class Role(
    val key: String,
) {
    ADMIN("admin"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

class RequireRoleConfig {
    var role: Role? = null
}

// the routes it is installed on below authenticate answer 401 without a principal and 403 to one
// without the role; a provider that refuses nothing on its own (e.g. API_SIGNATURE_AUTH alone,
// which has no challenge) leaves no route open
val RequireRole =
    createRouteScopedPlugin("RequireRole", ::RequireRoleConfig) {
        // a route without a role would be open to every session, it must not start
        val role = requireNotNull(pluginConfig.role) { "RequireRole needs the role it requires" }
        on(AuthenticationChecked) { call ->
            if (call.isHandled) return@on
            val principal = call.principal<SessionPrincipal>()
            when {
                principal == null -> call.respond(HttpStatusCode.Unauthorized)
                role !in principal.roles -> call.respond(HttpStatusCode.Forbidden)
            }
        }
    }

// the routes below answer 403 to sessions without the role
fun Route.requireRole(role: Role) = install(RequireRole) { this.role = role }
