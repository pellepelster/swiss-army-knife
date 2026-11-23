package io.pelle.sak.backend

import io.pelle.sak.api.API_VERSION
import io.pelle.sak.api.API_PATH_PREFIX
import io.pelle.sak.backend.restic.RESTIC_MAX_BODY_SIZE
import io.pelle.sak.backend.hosting.S3_MAX_BODY_SIZE
import io.pelle.sak.backend.hosting.S3_PATH_PREFIX
import io.pelle.sak.s3.storage.S3Limits
import java.time.Duration
import io.pelle.sak.core.logCause
import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.config.SakConfig
import io.pelle.sak.ingress.CaddyException
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.ingress.caddyConfig
import io.pelle.sak.ingress.model.CaddyConfig
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.s3.storage.S3BucketPathResolver
import io.pelle.sak.storage.LocalStorage
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.backend.restic.RESTIC_PATH_PREFIX
import io.pelle.sak.backend.restic.ResticStore
import io.pelle.sak.storage.FilesystemLocalStorage
import java.nio.file.Files
import java.nio.file.Path

// every host of the backend is https only, for a year
private const val HSTS_HEADER = "Strict-Transport-Security"
private const val HSTS_ONE_YEAR = "max-age=31536000"

// the domains the backend serves itself; no bucket and no redirect takes them
fun reservedDomains(config: SakConfig): Set<String> = setOf(config.apiHost, config.frontendHost)

// the base domain (by the dns mode the root domain or <name>.<root_domain>) leads to sak-frontend,
// unless a bucket or a redirect takes it; sak-frontend is served only with the login configured
fun redirectsRootDomain(
    config: SakConfig,
    bucketDomains: Map<String, List<String>>,
    redirectedDomains: Collection<String>,
): Boolean =
    config.auth != null &&
        bucketDomains.values.none { config.baseDomain in it } &&
        config.baseDomain !in redirectedDomains

// redirects maps every redirected domain to its target; objectsDir is where caddy finds the objects
// of a bucket
fun caddyConfig(
    config: SakConfig,
    bucketDomains: Map<String, List<String>>,
    redirects: Map<String, String>,
    port: Int,
    objectsDir: (bucket: String) -> Path,
): CaddyConfig =
    caddyConfig {
        config.acmeCa?.let { acmeCa(it) }
        server("sak") {
            listen(":80", ":443")
            // the ingress writes the access log to the file its caddyfile configures
            enableAccessLog()

            route {
                protocol("http")
                logField(SITE_LOG_FIELD, REDIRECT_SITE)
                redirect("https://{http.request.host}{http.request.uri}")
                terminal()
            }

            if (config.auth != null) {
                // the api and the s3 api belong to api.<base_domain> only
                route {
                    host(config.frontendHost)
                    path(S3_PATH_PREFIX, "$S3_PATH_PREFIX/*", API_PATH_PREFIX, "$API_PATH_PREFIX/*", RESTIC_PATH_PREFIX, "$RESTIC_PATH_PREFIX/*")
                    logField(SITE_LOG_FIELD, FRONTEND_SITE)
                    respond(404)
                    terminal()
                }
                route {
                    host(config.frontendHost)
                    logField(SITE_LOG_FIELD, FRONTEND_SITE)
                    responseHeader(HSTS_HEADER, HSTS_ONE_YEAR)
                    maxRequestBodySize(APPS_MAX_BODY_SIZE)
                    reverseProxy("localhost:$port")
                    terminal()
                }

                // below /api of the backend; no bucket is named v1, bucket names have at least
                // three characters
                route {
                    host(config.apiHost)
                    path(API_VERSION, "$API_VERSION/*")
                    logField(SITE_LOG_FIELD, API_SITE)
                    responseHeader(HSTS_HEADER, HSTS_ONE_YEAR)
                    maxRequestBodySize(APPS_MAX_BODY_SIZE)
                    rewrite("$API_PATH_PREFIX{http.request.uri}")
                    reverseProxy("localhost:$port")
                    terminal()
                }

                // the REST backend of restic (SERVER-7), below /restic of the backend too; before
                // the s3 api, no bucket is named restic. Its repositories have users, so only with
                // the login
                route {
                    host(config.apiHost)
                    path(RESTIC_PATH_PREFIX, "$RESTIC_PATH_PREFIX/*")
                    logField(SITE_LOG_FIELD, API_SITE)
                    responseHeader(HSTS_HEADER, HSTS_ONE_YEAR)
                    maxRequestBodySize(RESTIC_MAX_BODY_SIZE)
                    reverseProxy("localhost:$port")
                    terminal()
                }
            }

            // everything else at api.<base_domain> is the s3 api, below /s3 of the backend; hsts
            // holds for a host, so the same as for the api. The bucket domains and the redirects
            // have none, the owner of a site decides that
            route {
                host(config.apiHost)
                logField(SITE_LOG_FIELD, API_SITE)
                responseHeader(HSTS_HEADER, HSTS_ONE_YEAR)
                maxRequestBodySize(S3_MAX_BODY_SIZE)
                rewrite("$S3_PATH_PREFIX{http.request.uri}")
                reverseProxy("localhost:$port")
                terminal()
            }

            bucketDomains
                .filterValues { it.isNotEmpty() }
                .forEach { (bucket, domains) ->
                    route {
                        host(*domains.toTypedArray())
                        logField(SITE_LOG_FIELD, bucket)
                        fileServer(root = objectsDir(bucket).toString(), indexNames = listOf("index.html"))
                        terminal()
                    }
                }

            redirects.forEach { (from, to) ->
                route {
                    host(from)
                    logField(SITE_LOG_FIELD, REDIRECT_SITE)
                    redirect("https://$to{http.request.uri}")
                    terminal()
                }
            }

            if (redirectsRootDomain(config, bucketDomains, redirects.keys)) {
                route {
                    host(config.baseDomain)
                    logField(SITE_LOG_FIELD, REDIRECT_SITE)
                    redirect("https://${config.frontendHost}{http.request.uri}")
                    terminal()
                }
            }

            route {
                logField(SITE_LOG_FIELD, UNMATCHED_SITE)
                abort()
            }
        }
    }

// pushes the configuration to caddy periodically and when asked to, built again every time
class CaddyReconciler(
    private val caddyManager: CaddyManager,
    private val config: () -> CaddyConfig,
) {
    private val logger = KotlinLogging.logger {}

    // whether the last attempt configured caddy, null before the first one; a success is logged
    // after none or a failure, an unreachable caddy only once at warn, then at debug
    private var configured: Boolean? = null

    @Synchronized
    fun reconcile() {
        try {
            caddyManager.updateApps(config())
            if (configured != true) {
                logger.info { "configured caddy at '${caddyManager.adminUrl}'" }
            }
            configured = true
        } catch (e: CaddyException) {
            logger.warn(logCause(e)) { "caddy at '${caddyManager.adminUrl}' rejected the configuration" }
            configured = false
        } catch (e: Exception) {
            val message = { "caddy at '${caddyManager.adminUrl}' is not reachable (${e.message}), retrying periodically" }
            if (configured == false) logger.debug(message) else logger.warn(message)
            configured = false
        }
    }

    companion object {
        // how often caddy is configured again, so a caddy that started later or lost its configuration
        // gets it (BackgroundJobs runs it)
        val INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
