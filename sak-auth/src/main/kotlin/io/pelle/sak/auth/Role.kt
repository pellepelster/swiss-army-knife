package io.pelle.sak.auth

import io.ktor.http.HttpStatusCode
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
    lateinit var role: Role
}

// the routes it is installed on below authenticate(SESSION_AUTH) answer 403 to sessions
// without the role
val RequireRole =
    createRouteScopedPlugin("RequireRole", ::RequireRoleConfig) {
        val role = pluginConfig.role
        on(AuthenticationChecked) { call ->
            val principal = call.principal<SessionPrincipal>()
            if (principal != null && role !in principal.roles) {
                call.respond(HttpStatusCode.Forbidden)
            }
        }
    }
