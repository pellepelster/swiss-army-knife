package io.pelle.sak.backend

import io.ktor.http.HttpHeaders
import io.pelle.sak.auth.requireRole
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.auth.LoginApp
import io.pelle.sak.backend.secrets.SecretType
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.auth.SmtpMailSender
import io.pelle.sak.auth.TokenService
import io.pelle.sak.auth.login
import io.pelle.sak.auth.me
import io.pelle.sak.auth.session
import io.pelle.sak.backend.files.files
import io.pelle.sak.backend.files.servedFiles
import io.pelle.sak.backend.hosting.buckets
import io.pelle.sak.backend.labels.labels
import io.pelle.sak.backend.notes.notes
import io.pelle.sak.backend.redirects.domains
import io.pelle.sak.backend.redirects.redirects
import io.pelle.sak.config.SakConfig
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.logs.SiteLogs
import io.pelle.sak.logs.SiteMetricsService
import io.pelle.sak.logs.VictoriaLogsClient
import io.pelle.sak.logs.accessLogs
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val API_PATH_PREFIX = "/api"

const val API_VERSION = "/v1"

// the api only receives small json bodies, but for the files of the file storage
const val API_MAX_BODY_SIZE = 16L * 1024

// what caddy lets through to sak-frontend at most, a file of the file storage with room to
// spare; uploads to buckets go to the s3 api at api.<root_domain>, without a limit
const val APPS_MAX_BODY_SIZE = FileService.DEFAULT_MAX_SIZE + 1024L * 1024

// how long a browser keeps the answer to a preflight request
private const val CORS_MAX_AGE_SECONDS = 24L * 60 * 60

// sak-frontend packages its build below this resource path
const val FRONTEND_RESOURCES = "frontend"

// the pages call the api at api.<root_domain>; blob: are the files of the file storage, fetched
// with the session and shown as object urls
fun contentSecurityPolicy(config: SakConfig) =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
        "media-src 'self' blob:; connect-src 'self' https://${config.apiHost}; object-src 'none'; base-uri 'self'; " +
        "form-action 'self'; frame-ancestors 'none'"

// the origins sak-frontend is served from, apps.<root_domain> and the one of its login links,
// e.g. the dev server; only they may call the api from another origin
fun frontendOrigins(config: SakConfig): Set<URI> =
    setOf(URI("https://${config.appsHost}"), URI(config.frontendUrl).let { URI(it.scheme, null, it.host, it.port, null, null, null) })

// the apps the login links lead to, sak-frontend; the mails say "Sign in to <root_domain>"
fun loginApp(config: SakConfig) = LoginApp(config.frontendUrl, config.rootDomain)


// the session token lives in the local storage of sak-frontend, scripts from anywhere else must
// never run on its pages; no referrer keeps the login token of the signin url to itself
private class SecurityHeadersConfig {
    var contentSecurityPolicy = ""
}

private val SecurityHeaders =
    createRouteScopedPlugin("SecurityHeaders", ::SecurityHeadersConfig) {
        val contentSecurityPolicy = pluginConfig.contentSecurityPolicy
        onCall { call ->
            call.response.header("Content-Security-Policy", contentSecurityPolicy)
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("X-Content-Type-Options", "nosniff")
        }
    }

// sak-frontend and its api, caddy proxies apps.<root_domain> here unchanged and api.<root_domain>/v1
// below /api; everything that can fail happens before anything is installed, so a failure leaves
// no half an app behind
fun Application.apps(
    config: SakConfig,
    auth: SakConfig.AuthConfig,
    services: SakServices,
) = with(services) {
    // generated once and kept with the secrets, so sessions survive restarts
    val signingKey = secrets.getOrCreate(SecretType.AUTH_SIGNING_KEY) { SigningKey.generate().toPem() }
    val tokens = TokenService(SigningKey.fromPem(signingKey.value), config.frontendUrl)
    val mailSender =
        with(auth.smtp) {
            SmtpMailSender(host, port, username, password, from, tls = tls, starttls = starttls)
        }
    val admins = auth.admins.map { it.trim().lowercase() }.toSet()
    val rolesOf = { email: String -> if (email in admins) setOf(Role.ADMIN) else emptySet() }

    install(Authentication) { session(tokens, rolesOf) }

    // the session travels in the authorization header, never in a cookie, so no credentials
    install(CORS) {
        frontendOrigins(config).forEach { allowHost(it.authority, schemes = listOf(it.scheme)) }
        listOf(HttpMethod.Put, HttpMethod.Delete).forEach { allowMethod(it) }
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        // the requests of the frontend go past its service worker
        allowHeader("ngsw-bypass")
        maxAgeInSeconds = CORS_MAX_AGE_SECONDS
    }

    routing {
        route("/") {
            install(SecurityHeaders) { contentSecurityPolicy = contentSecurityPolicy(config) }

            route(API_PATH_PREFIX) {
                install(RequestBodyLimit) { bodyLimit { API_MAX_BODY_SIZE } }

                route(API_VERSION) {
                    // the only routes without a session, the rest of the api needs one
                    route("/login") {
                        login(tokens, mailSender, loginApp(config), rolesOf)
                    }
                    authenticate(SESSION_AUTH) {
                        route("/auth") {
                            me()
                        }
                        route("/static-hosting") {
                            requireRole(Role.ADMIN)
                            buckets(bucketService, statistics)
                            // the access logs of all the domains a bucket is served at
                            route("/{bucket}/access-logs") {
                                val victoriaLogs = VictoriaLogsClient(config.logs.victorialogsUrl)
                                accessLogs(
                                    { call -> withContext(Dispatchers.IO) { bucketSite(bucketService, call.parameters["bucket"].orEmpty()) } },
                                    SiteMetricsService(victoriaLogs),
                                    SiteLogs(victoriaLogs),
                                )
                            }
                        }
                        route("/redirects") {
                            requireRole(Role.ADMIN)
                            redirects(redirectService)
                        }
                        route("/labels") {
                            requireRole(Role.ADMIN)
                            labels(labelService)
                        }
                        route("/notes") {
                            requireRole(Role.ADMIN)
                            notes(noteService)
                        }
                        route("/files") {
                            requireRole(Role.ADMIN)
                            install(RequestBodyLimit) { bodyLimit { fileService.maxSize } }
                            files(fileService, fileTextService)
                            servedFiles(fileService)
                        }
                    }
                }
                // unknown api paths and versions are no pages of the apps
                route("{...}") {
                    handle { call.respond(HttpStatusCode.NotFound) }
                }.hide()
            }

            apiDoc()

            // caddy only lets apps.<root_domain> through, its swagger ui is part of sak-frontend
            singlePageApplication {
                useResources = true
                filesPath = FRONTEND_RESOURCES
            }
        }
    }
}
