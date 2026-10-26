package io.pelle.sak.controller

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.auth.SmtpMailSender
import io.pelle.sak.auth.TokenService
import io.pelle.sak.auth.auth
import io.pelle.sak.auth.session
import io.pelle.sak.config.SakConfig
import io.pelle.sak.logs.SiteLogs
import io.pelle.sak.logs.SiteMetricsService
import io.pelle.sak.logs.VictoriaLogsClient
import io.pelle.sak.logs.sites

const val API_PATH_PREFIX = "/api"

const val API_VERSION = "/v1"

// sak-console packages its build below this resource path
const val CONSOLE_RESOURCES = "console"

const val CONSOLE_CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
        "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"

fun signingKeyFile(config: SakConfig) = config.authDataDir.resolve("signing-key.pem")

// the session token lives in the console's local storage, scripts from anywhere else must
// never run on its pages; no referrer keeps the login token of the signin url to itself
private val ConsoleSecurityHeaders =
    createRouteScopedPlugin("ConsoleSecurityHeaders") {
        onCall { call ->
            call.response.header("Content-Security-Policy", CONSOLE_CONTENT_SECURITY_POLICY)
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("X-Content-Type-Options", "nosniff")
        }
    }

// the console and its api, caddy proxies console.<root_domain> here unchanged
fun Application.console(
    config: SakConfig,
    auth: SakConfig.AuthConfig,
) {
    val tokens = TokenService(SigningKey.loadOrCreate(signingKeyFile(config)), config.consoleUrl)
    val mailSender =
        with(auth.smtp) {
            SmtpMailSender(host, port, username, password, from, tls = tls, starttls = starttls)
        }
    val allowedEmails = auth.allowedEmails.map { it.trim().lowercase() }.toSet()

    install(Authentication) { session(tokens) }

    routing {
        route("/") {
            install(ConsoleSecurityHeaders)

            route(API_PATH_PREFIX) {
                route(API_VERSION) {
                    route("/auth") {
                        auth(tokens, mailSender, config.consoleUrl, mayLogin = { it in allowedEmails })
                    }
                    authenticate(SESSION_AUTH) {
                        route("/logs") {
                            val victoriaLogs = VictoriaLogsClient(config.logs.victorialogsUrl)
                            sites(sites(config), SiteMetricsService(victoriaLogs), SiteLogs(victoriaLogs))
                        }
                    }
                }
                // unknown api paths and versions are no console pages
                route("{...}") {
                    handle { call.respond(HttpStatusCode.NotFound) }
                }.hide()
            }

            apiDoc()

            singlePageApplication {
                useResources = true
                filesPath = CONSOLE_RESOURCES
            }
        }
    }
}
