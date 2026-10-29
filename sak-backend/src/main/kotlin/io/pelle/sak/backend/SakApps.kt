package io.pelle.sak.backend

import io.ktor.http.HttpHeaders
import io.pelle.sak.auth.ADMIN_USERNAME
import io.pelle.sak.auth.requireRole
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.auth.LoginApp
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.API_TOKEN_AUTH
import io.pelle.sak.auth.AdminPassword
import io.pelle.sak.auth.loginMethods
import io.pelle.sak.auth.passwordLogin
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.auth.SmtpMailSender
import io.pelle.sak.auth.TokenService
import io.pelle.sak.auth.login
import io.pelle.sak.auth.whoAmI
import io.pelle.sak.auth.apiToken
import io.pelle.sak.auth.session
import io.pelle.sak.backend.files.files
import io.pelle.sak.backend.files.servedFiles
import io.pelle.sak.backend.hosting.buckets
import io.pelle.sak.backend.config.config
import io.pelle.sak.backend.info.info
import io.pelle.sak.backend.health.health
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
// spare; uploads to buckets go to the s3 api at api.<base_domain>, without a limit
const val APPS_MAX_BODY_SIZE = FileService.DEFAULT_MAX_SIZE + 1024L * 1024

// how long a browser keeps the answer to a preflight request
private const val CORS_MAX_AGE_SECONDS = 24L * 60 * 60

// sak-frontend packages its build below this resource path
const val FRONTEND_RESOURCES = "frontend"

// the pages call the api at api.<base_domain>; blob: are the files of the file storage, fetched
// with the session and shown as object urls
fun contentSecurityPolicy(config: SakConfig) =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; " +
        "media-src 'self' blob:; connect-src 'self' https://${config.apiHost}; object-src 'none'; base-uri 'self'; " +
        "form-action 'self'; frame-ancestors 'none'"

// the origins sak-frontend is served from, sak.<base_domain> and the one of its login links,
// e.g. the dev server; only they may call the api from another origin
fun frontendOrigins(config: SakConfig): Set<URI> =
    setOf(URI("https://${config.frontendHost}"), URI(config.frontendUrl).let { URI(it.scheme, null, it.host, it.port, null, null, null) })

// the apps the login links lead to, sak-frontend; the mails say "Sign in to <root_domain>"
fun loginApp(config: SakConfig) = LoginApp(config.frontendUrl, config.baseDomain)


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

// sak-frontend and its api, caddy proxies sak.<base_domain> here unchanged and api.<base_domain>/v1
// below /api; everything that can fail happens before anything is installed, so a failure leaves
// no half an app behind
fun Application.apps(
    config: SakConfig,
    auth: SakConfig.AuthConfig,
    services: SakServices,
) = with(services) {
    // generated once and kept in the settings, so sessions survive restarts
    val signingKey = settings.getOrCreate(SettingName.AUTH_SIGNING_KEY) { SigningKey.generate().toPem() }
    val tokens = TokenService(SigningKey.fromPem(signingKey), config.frontendUrl)
    // the e-mail login only with a mail server, the password login only with an admin password
    val mailSender =
        auth.smtp?.run {
            SmtpMailSender(host, port, username, password, from, tls = tls, starttls = starttls)
        }
    val adminPassword = auth.adminPassword?.let(::AdminPassword)
    // the admin of the password login: the first of the admins, the same user as by e-mail (multi
    // user), or ADMIN_USERNAME without admins (single user)
    val admin = auth.admins.firstOrNull()?.trim()?.lowercase() ?: ADMIN_USERNAME
    val admins = auth.admins.map { it.trim().lowercase() }.toSet() + admin
    val rolesOf = { email: String -> if (email in admins) setOf(Role.ADMIN) else emptySet() }

    install(Authentication) {
        session(tokens, rolesOf)
        apiToken(config.apiTokenSha256)
    }

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
                    // the only routes without a session, the info and the login; the rest of
                    // the api needs one
                    route("/info") {
                        info(config.name, config.dnsMode)
                    }
                    route("/auth") {
                        route("/login") {
                            if (mailSender != null) {
                                login(tokens, mailSender, loginApp(config), rolesOf)
                            }
                            if (adminPassword != null) {
                                passwordLogin(tokens, admin, adminPassword, rolesOf)
                            }
                            loginMethods(email = mailSender != null, password = adminPassword != null)
                        }
                        authenticate(SESSION_AUTH, API_TOKEN_AUTH) {
                            whoAmI()
                        }
                    }
                    authenticate(SESSION_AUTH, API_TOKEN_AUTH) {
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
                        route("/config") {
                            requireRole(Role.ADMIN)
                            config(config)
                        }
                        route("/health") {
                            requireRole(Role.ADMIN)
                            health(listOf(hostingHealth, redirectsHealth))
                        }
                        route("/files") {
                            requireRole(Role.ADMIN)
                            install(RequestBodyLimit) { bodyLimit { fileService.maxSize } }
                            files(fileService, fileTextService)
                            servedFiles(fileService)
                        }
                    }
                }
            }

            apiDoc()

            // caddy only lets sak.<base_domain> through, its swagger ui is part of sak-frontend.
            // Unknown api paths and versions are no pages of the apps: there routing answers 404,
            // and 405 for a method a known path does not take
            outsideOf(API_PATH_PREFIX) {
                singlePageApplication {
                    useResources = true
                    filesPath = FRONTEND_RESOURCES
                }
            }
        }
    }
}

// the routes of `build` for every path but those below `prefix`
private fun Route.outsideOf(
    prefix: String,
    build: Route.() -> Unit,
) {
    val segment = prefix.trim('/')
    createChild(
        object : RouteSelector() {
            override suspend fun evaluate(
                context: RoutingResolveContext,
                segmentIndex: Int,
            ) = if (context.segments.getOrNull(segmentIndex) == segment) RouteSelectorEvaluation.FailedPath else RouteSelectorEvaluation.Transparent

            override fun toString() = "(outside of $prefix)"
        },
    ).build()
}
