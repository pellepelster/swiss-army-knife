package io.pelle.sak.backend

import io.pelle.sak.auth.LOGIN_PATH
import io.pelle.sak.auth.AUTH_PATH
import io.pelle.sak.api.installApi
import io.pelle.sak.api.API_VERSION
import io.pelle.sak.api.API_PATH_PREFIX
import io.pelle.sak.backend.health.health
import io.pelle.sak.backend.status.status
import io.pelle.sak.backend.hosting.S3_MAX_BODY_SIZE
import io.pelle.sak.backend.users.AppLinkService
import io.pelle.sak.backend.users.users
import io.pelle.sak.backend.deployment.deployment
import io.ktor.http.HttpHeaders
import io.pelle.sak.auth.ADMIN_USERNAME
import io.pelle.sak.auth.requireRole
import io.pelle.sak.auth.connect
import io.pelle.sak.auth.connectExchange
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
import io.pelle.sak.auth.LoginListener
import io.pelle.sak.backend.settings.SettingName
import io.pelle.sak.auth.Role
import io.pelle.sak.auth.API_SIGNATURE_AUTH
import io.pelle.sak.auth.ApiSignature
import io.pelle.sak.auth.AdminPassword
import io.pelle.sak.auth.passwordLogin
import io.pelle.sak.auth.SESSION_AUTH
import io.pelle.sak.auth.SigningKey
import io.pelle.sak.core.SmtpMailSender
import io.pelle.sak.backend.users.AppLinkMailer
import io.pelle.sak.backend.users.UserService
import io.pelle.sak.auth.TokenService
import io.pelle.sak.auth.login
import io.pelle.sak.auth.whoAmI
import io.pelle.sak.auth.apiSignature
import io.pelle.sak.auth.session
import io.pelle.sak.backend.files.discoverTextLimiter
import io.pelle.sak.backend.files.files
import io.pelle.sak.backend.files.servedFiles
import io.pelle.sak.backend.hosting.buckets
import io.pelle.sak.backend.info.info
import io.pelle.sak.backend.info.wellKnown
import io.pelle.sak.backend.labels.labels
import io.pelle.sak.backend.notes.notes
import io.pelle.sak.backend.grocery.grocery
import io.pelle.sak.backend.todo.todo
import io.pelle.sak.backend.restic.restic
import io.pelle.sak.backend.redirects.redirects
import io.pelle.sak.backend.settings.settings as settingsRoutes
import io.pelle.sak.backend.settings.userSettings
import io.pelle.sak.config.SakConfig
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.logs.accessLogs
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// the path of each part of the api below API_BASE_PATH, written once: the routes are mounted at them and
// the descriptions name them
object ApiPath {
    const val INFO = "/info"
    const val STATIC_HOSTING = "/static-hosting"
    const val REDIRECTS = "/redirects"
    const val LABELS = "/labels"
    const val NOTES = "/notes"
    const val GROCERY = "/grocery"
    const val TODO = "/todo"
    const val RESTIC = "/restic"
    const val USERS = "/users"
    const val SETTINGS = "/settings"
    const val USER_SETTINGS = "/user/settings"
    const val HEALTH = "/health"
    const val STATUS = "/status"
    const val FILES = "/files"
    const val DEPLOYMENT = "/deployment"
}

// small json bodies only; the routes of the file storage have a limit of their own
const val API_MAX_BODY_SIZE = 16L * 1024

// the most caddy lets through to sak-frontend: a file of the file storage with room to spare;
// uploads to buckets have S3_MAX_BODY_SIZE
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

// the apps the login links lead to, sak-frontend; the mails say "Sign in to <base_domain>"
fun loginApp(config: SakConfig) = LoginApp(config.frontendUrl, config.baseDomain)


// the session token is in the local storage of sak-frontend, so no script from elsewhere may run
// on its pages; no referrer keeps the login token of the signin url private
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

// the admin of the password login: the first of the admins, the same user as by e-mail (multi
// user), or ADMIN_USERNAME without admins (single user)
fun adminOf(auth: SakConfig.AuthConfig) = auth.admins.firstOrNull()?.let(UserService::normalize) ?: ADMIN_USERNAME

// the roles of an address: the admins of the configuration, then the users an admin added (the
// table, asked on every request of a session and every login of restic: a deleted user's next
// request is refused)
fun rolesOf(
    auth: SakConfig.AuthConfig,
    users: UserService,
): (String) -> Set<Role> {
    val admins = auth.admins.map(UserService::normalize).toSet() + adminOf(auth)
    return { email ->
        when {
            email in admins -> setOf(Role.ADMIN)
            users.isUser(email) -> setOf(Role.USER)
            else -> emptySet()
        }
    }
}

// sak-frontend and its api: caddy proxies sak.<base_domain> unchanged and api.<base_domain>/v1
// below /api. All that can fail happens before anything is installed, so a failure leaves no half app
fun Application.apps(
    config: SakConfig,
    auth: SakConfig.AuthConfig,
    services: SakServices,
) = with(services) {
    // what goes to openrouter costs money: one limit of each user for the text of the notes and the
    // items of the grocery lists
    val discoverLimiter = discoverTextLimiter()
    // generated once and kept in the settings, so sessions survive restarts
    val signingKey = settings.getOrCreate(SettingName.AUTH_SIGNING_KEY) { SigningKey.generate().toPem() }
    val tokens = TokenService(SigningKey.fromPem(signingKey), config.frontendUrl)
    // the e-mail login only with a mail server, the password login only with an admin password
    val mailSender =
        auth.smtp?.run {
            SmtpMailSender(host, port, username, password, from, tls = tls, starttls = starttls)
        }
    val adminPassword = auth.adminPassword?.let(::AdminPassword)
    val admin = adminOf(auth)
    // made with the services, which ask it too; there is a login, so there are roles
    val rolesOf = checkNotNull(services.rolesOf) { "no roles without a login" }
    // what the mails and the pages call the deployment and where they lead
    val loginApp = loginApp(config)
    // every app that prepares the data of a user who signs in: the first list of each list-based app (LIST-1)
    val onLogin = LoginListener { owner, language -> loginListeners.forEach { it.signedIn(owner, language) } }

    install(Authentication) {
        session(tokens, rolesOf)
        // sakadmin acts as the admin, what it creates is theirs
        apiSignature(config.apiPublicKey?.let { ApiSignature(it, config.apiHost, API_PATH_PREFIX) }, owner = admin)
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
                    // json bodies and the errors of the api, once for every route below (API-1)
                    installApi()

                    // the only routes without a session
                    route(ApiPath.INFO) {
                        info(config.name, config.dnsMode, mailServer = mailSender != null, version = config.version)
                    }
                    route(AUTH_PATH) {
                        route(LOGIN_PATH) {
                            if (mailSender != null) {
                                login(tokens, mailSender, loginApp, rolesOf, onLogin = onLogin)
                            }
                            if (adminPassword != null) {
                                passwordLogin(tokens, admin, adminPassword, rolesOf, onLogin = onLogin)
                            }
                            // an app connected with the QR code of a session (SECURITY-3)
                            connectExchange(tokens, rolesOf, onLogin = onLogin)
                        }
                        authenticate(SESSION_AUTH, API_SIGNATURE_AUTH) {
                            whoAmI(nameOf = userService::name)
                            connect(tokens, loginApp)
                        }
                    }
                    authenticate(SESSION_AUTH, API_SIGNATURE_AUTH) {
                        route(ApiPath.STATIC_HOSTING) {
                            requireRole(Role.ADMIN)
                            buckets(bucketService)
                            // the access logs of all the domains a bucket is served at
                            route("/{bucket}/access-logs") {
                                accessLogs(
                                    { call -> withContext(Dispatchers.IO) { bucketSite(bucketService, call.parameters["bucket"].orEmpty()) } },
                                    accessLogsManager,
                                )
                            }
                        }
                        route(ApiPath.REDIRECTS) {
                            requireRole(Role.ADMIN)
                            redirects(redirectService)
                        }
                        route(ApiPath.LABELS) {
                            requireRole(Role.ADMIN, Role.USER)
                            labels(labelService)
                        }
                        route(ApiPath.NOTES) {
                            requireRole(Role.ADMIN, Role.USER)
                            notes(noteService, fileTextService::available, fileTextService::configured)
                        }
                        route(ApiPath.GROCERY) {
                            requireRole(Role.ADMIN, Role.USER)
                            grocery(groceryService, groceryDiscovery, discoverLimiter)
                        }
                        route(ApiPath.TODO) {
                            requireRole(Role.ADMIN, Role.USER)
                            todo(todoService)
                        }
                        route(ApiPath.RESTIC) {
                            requireRole(Role.ADMIN, Role.USER)
                            restic(resticService, resticBackupPlanService, config.apiHost)
                        }
                        route(ApiPath.USERS) {
                            requireRole(Role.ADMIN)
                            users(userService, AppLinkService(userService, mailSender?.let { AppLinkMailer(it, loginApp) }))
                        }
                        route(ApiPath.SETTINGS) {
                            requireRole(Role.ADMIN)
                            settingsRoutes(settingsRegistry)
                        }
                        route(ApiPath.USER_SETTINGS) {
                            requireRole(Role.ADMIN, Role.USER)
                            userSettings(services.userSettings)
                        }
                        route(ApiPath.HEALTH) {
                            requireRole(Role.ADMIN)
                            health(listOf(hostingHealth, redirectsHealth))
                        }
                        route(ApiPath.STATUS) {
                            requireRole(Role.ADMIN)
                            status(listOf(hostingHealth, redirectsHealth), storageUsage)
                        }
                        route(ApiPath.FILES) {
                            requireRole(Role.ADMIN, Role.USER)
                            install(RequestBodyLimit) { bodyLimit { fileService.maxSize } }
                            files(fileService, fileTextService, discoverLimiter)
                            servedFiles(fileService)
                        }
                    }
                    // sakadmin only, signed: holds the password of the mail server, no browser session gets it
                    authenticate(API_SIGNATURE_AUTH) {
                        route(ApiPath.DEPLOYMENT) {
                            requireRole(Role.ADMIN)
                            deployment(config)
                        }
                    }
                }
            }

            apiDoc()

            // where the api of the SAK address is, before the pages of the apps
            wellKnown(config)

            // the pages of sak-frontend, swagger ui included. Unknown api paths are no pages: routing
            // answers 404 there, or 405 for a method a known path does not take
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
