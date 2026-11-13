package io.pelle.sak.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import io.pelle.sak.config.SakConfig
import io.pelle.sak.ingress.CaddyException
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.ingress.caddyConfig
import io.pelle.sak.ingress.model.CaddyConfig
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.WriteGuard
import io.pelle.sak.storage.FilesystemBinaryStorage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

const val S3_PATH_PREFIX = "/s3"

// the buckets on the binary storage of s3DataDir; where a bucket is on disk only sak-s3 knows
fun bucketStorage(config: SakConfig): BinaryStorageS3Backend {
    val storage = FilesystemBinaryStorage(config.s3DataDir)
    return BinaryStorageS3Backend(storage, guard = WriteGuard({ Files.getFileStore(config.s3DataDir).usableSpace }, config.s3MinFreeBytes))
}

// the most caddy lets through to the s3 api: the largest object or part, with room for the
// signatures of an aws-chunked body
const val S3_MAX_BODY_SIZE = WriteGuard.MAX_OBJECT_BYTES + 64L * 1024 * 1024

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
                    path(S3_PATH_PREFIX, "$S3_PATH_PREFIX/*", API_PATH_PREFIX, "$API_PATH_PREFIX/*")
                    logField(SITE_LOG_FIELD, FRONTEND_SITE)
                    respond(404)
                    terminal()
                }
                route {
                    host(config.frontendHost)
                    logField(SITE_LOG_FIELD, FRONTEND_SITE)
                    responseHeader("Strict-Transport-Security", "max-age=31536000")
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
                    responseHeader("Strict-Transport-Security", "max-age=31536000")
                    maxRequestBodySize(APPS_MAX_BODY_SIZE)
                    rewrite("$API_PATH_PREFIX{http.request.uri}")
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
                responseHeader("Strict-Transport-Security", "max-age=31536000")
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

// pushes the configuration to caddy periodically and when asked to, built anew every time
class CaddyReconciler(
    private val caddyManager: CaddyManager,
    private val config: () -> CaddyConfig,
    private val intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS,
) {
    private val logger = KotlinLogging.logger {}

    // whether the last attempt configured caddy, null before the first one; a success is logged
    // after none or a failure, an unreachable caddy only once at warn, then at debug
    private var configured: Boolean? = null

    private var executor: ScheduledExecutorService? = null

    @Synchronized
    fun reconcile() {
        try {
            caddyManager.updateApps(config())
            if (configured != true) {
                logger.info { "configured caddy at '${caddyManager.adminUrl}'" }
            }
            configured = true
        } catch (e: CaddyException) {
            logger.warn(e) { "caddy at '${caddyManager.adminUrl}' rejected the configuration" }
            configured = false
        } catch (e: Exception) {
            val message = { "caddy at '${caddyManager.adminUrl}' is not reachable (${e.message}), retrying periodically" }
            if (configured == false) logger.debug(message) else logger.warn(message)
            configured = false
        }
    }

    fun start() {
        executor =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "caddy-reconciler").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay(::reconcile, 0, intervalSeconds, TimeUnit.SECONDS)
            }
    }

    // without waiting for the next period, e.g. after the redirects changed
    fun reconcileNow() {
        executor?.execute(::reconcile)
    }

    fun stop() {
        executor?.shutdownNow()
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 30L
    }
}
