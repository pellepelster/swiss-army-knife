package io.pelle.sak.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.auth.RequireRole
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.auth.SmtpMailSender
import io.pelle.sak.auth.TokenService
import io.pelle.sak.auth.auth
import io.pelle.sak.auth.session
import io.pelle.sak.config.SakConfig
import io.pelle.sak.model.ingress.RedirectService
import io.pelle.sak.model.labels.LabelService
import io.pelle.sak.model.notes.NoteService
import io.pelle.sak.model.s3.S3BucketService
import io.pelle.sak.logs.SiteLogs
import io.pelle.sak.logs.SiteMetricsService
import io.pelle.sak.logs.VictoriaLogsClient
import io.pelle.sak.logs.sites

const val API_PATH_PREFIX = "/api"

const val API_VERSION = "/v1"

// the api only ever receives small json bodies
const val API_MAX_BODY_SIZE = 16L * 1024

// what caddy lets through to the console at most, uploads go to the s3 host
const val CONSOLE_MAX_BODY_SIZE = 1024L * 1024

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

// the console and its api, caddy proxies console.<root_domain> here unchanged; everything that
// can fail happens before anything is installed, so a failure leaves no half a console behind
fun Application.console(
    config: SakConfig,
    auth: SakConfig.AuthConfig,
    statistics: BucketStatisticsCache,
    bucketService: S3BucketService,
    redirectService: RedirectService,
    labelService: LabelService,
    noteService: NoteService,
) {
    val tokens = TokenService(SigningKey.loadOrCreate(signingKeyFile(config)), config.consoleUrl)
    val mailSender =
        with(auth.smtp) {
            SmtpMailSender(host, port, username, password, from, tls = tls, starttls = starttls)
        }
    val admins = auth.admins.map { it.trim().lowercase() }.toSet()
    val rolesOf = { email: String -> if (email in admins) setOf(Role.ADMIN) else emptySet() }

    install(Authentication) { session(tokens, rolesOf) }

    routing {
        route("/") {
            install(ConsoleSecurityHeaders)

            route(API_PATH_PREFIX) {
                install(RequestBodyLimit) { bodyLimit { API_MAX_BODY_SIZE } }

                route(API_VERSION) {
                    route("/auth") {
                        auth(tokens, mailSender, config.consoleUrl, rolesOf)
                    }
                    authenticate(SESSION_AUTH) {
                        route("/logs") {
                            install(RequireRole) { role = Role.ADMIN }
                            val victoriaLogs = VictoriaLogsClient(config.logs.victorialogsUrl)
                            sites({ sites(config, bucketService.domains(), redirectService.all().map { it.sourceDomain }) }, SiteMetricsService(victoriaLogs), SiteLogs(victoriaLogs))
                        }
                        route("/buckets") {
                            install(RequireRole) { role = Role.ADMIN }
                            buckets(bucketService, statistics)
                        }
                        route("/redirects") {
                            install(RequireRole) { role = Role.ADMIN }
                            redirects(redirectService)
                        }
                        route("/labels") {
                            install(RequireRole) { role = Role.ADMIN }
                            labels(labelService)
                        }
                        route("/notes") {
                            install(RequireRole) { role = Role.ADMIN }
                            notes(noteService)
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
