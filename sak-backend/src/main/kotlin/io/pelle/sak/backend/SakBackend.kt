package io.pelle.sak.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.ingress.RedirectService
import io.pelle.sak.model.s3.S3BucketService
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.FilesystemBinaryStorage
import java.nio.file.Files
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

const val BUNDLED_CONFIG = "application.yaml"

fun main(args: Array<String>) {
    if (args.none { it.startsWith("-config=") }) {
        System.err.println("usage: sak-backend -config=<config.yaml>")
        exitProcess(1)
    }

    EngineMain.main(arrayOf("-config=$BUNDLED_CONFIG") + args)
}

fun Application.module() {
    val config = SakConfigLoader.load(environment.config)
    val port = environment.config.property("ktor.deployment.port").getString().toInt()
    logger.info { "starting sak-backend on port $port with data dir ${config.dataDir}" }

    val services = sak(config)

    val reconciler =
        CaddyReconciler(CaddyManager(config.caddyAdminUrl), config = {
            caddyConfig(config, services.bucketService.domains(), services.redirectService.locations(), port)
        })
    services.bucketService.onChange(reconciler::reconcileNow)
    services.redirectService.onChange(reconciler::reconcileNow)
    monitor.subscribe(ApplicationStarted) { reconciler.start() }
    monitor.subscribe(ApplicationStopping) { reconciler.stop() }
}

// what the controller keeps in its database
class SakServices(
    val bucketService: S3BucketService,
    val redirectService: RedirectService,
)

// the controller's database, the buckets, their keys and the redirects are in it
fun databaseFile(config: SakConfig) = config.dataDir.resolve("sak.db")

fun Application.sak(config: SakConfig): SakServices {
    Files.createDirectories(config.dataDir)
    Files.createDirectories(config.s3DataDir)

    val storage = BinaryStorageS3Backend(FilesystemBinaryStorage(config.s3DataDir)) { bucket -> bucketPath(bucket) }
    val database = SakDatabase.open(databaseFile(config))
    // a bucket is not served at a redirected domain and the other way round, each service checks
    // the other's domains
    lateinit var redirectService: RedirectService
    val bucketService =
        S3BucketService(
            database,
            reservedNames = RESERVED_SITES,
            reservedDomains = { reservedDomains(config) + redirectService.sourceDomains() },
        )
    redirectService = RedirectService(database, bucketService, reservedDomains = { reservedDomains(config) })
    val accessCallback = BucketKeysAccessCallback(bucketService)
    val statistics = BucketStatisticsCache(storage)
    statistics.warm(bucketService.all().map { it.name })

    routing {
        route(S3_PATH_PREFIX) {
            s3(InvalidatingS3StorageBackend(storage, statistics::invalidate), accessCallback, proxiedPathPrefix = S3_PATH_PREFIX)
        }
    }

    // a broken console, e.g. an unreadable signing key, must not take the s3 api down with it
    config.auth?.let {
        try {
            console(config, it, statistics, bucketService, redirectService)
        } catch (e: Exception) {
            logger.error(e) { "the console could not be set up, it is not served" }
        }
    }

    return SakServices(bucketService, redirectService)
}
