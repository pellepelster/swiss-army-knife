package io.pelle.sak.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.isHandled
import io.ktor.server.routing.Route
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.pelle.sak.api.respondStatusError

// what a signed in address may do, the session token names its roles: admin everything, user the
// apps of the lists (the notes, the grocery lists, the todo lists) with their labels and files
enum class Role(
    val key: String,
) {
    ADMIN("admin"),
    USER("user"),
    ;

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

class RequireRoleConfig {
    var roles: Set<Role> = emptySet()
}

// 401 without a principal, 403 with none of the roles; so a provider without a challenge of its
// own (e.g. API_SIGNATURE_AUTH alone) leaves no route open
val RequireRole =
    createRouteScopedPlugin("RequireRole", ::RequireRoleConfig) {
        // a route without a role would be open to every session, it must not start
        val roles = pluginConfig.roles.also { require(it.isNotEmpty()) { "RequireRole needs the roles it allows" } }
        on(AuthenticationChecked) { call ->
            if (call.isHandled) return@on
            val principal = call.principal<SessionPrincipal>()
            when {
                principal == null -> call.respondStatusError(HttpStatusCode.Unauthorized)
                principal.roles.none { it in roles } -> call.respondStatusError(HttpStatusCode.Forbidden)
            }
        }
    }

fun Route.requireRole(vararg roles: Role) = install(RequireRole) { this.roles = roles.toSet() }
