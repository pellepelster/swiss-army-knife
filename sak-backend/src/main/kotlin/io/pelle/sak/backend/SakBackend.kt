package io.pelle.sak.backend

import io.pelle.sak.backend.hosting.HostingHealth
import io.pelle.sak.backend.redirects.RedirectsHealth
import io.pelle.sak.dns.DnsjavaNameServer
import io.pelle.sak.dns.DomainDnsCheck
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.netty.EngineMain
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.backend.hosting.BucketStatisticsCache
import io.pelle.sak.backend.hosting.InvalidatingS3StorageBackend
import io.pelle.sak.backend.redirects.domains
import io.pelle.sak.backend.redirects.locations
import io.pelle.sak.backend.redirects.reservedDomains
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.secrets.SecretService
import io.pelle.sak.backend.redirects.RedirectService
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.FileTextService
import io.pelle.sak.backend.labels.LabelService
import io.pelle.sak.backend.notes.NoteService
import io.pelle.sak.openrouter.OpenRouterSpeechTranscriber
import io.pelle.sak.openrouter.OpenRouterTextRecognizer
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.FilesystemBinaryStorage
import java.nio.file.Files
import kotlin.system.exitProcess
import io.pelle.sak.api.installUnexpectedErrors

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
    installUnexpectedErrors()
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

// every service of the backend, made once by sak(); the routes of the apps use them, the caddy
// reconciler hears of the changes of the buckets and the redirects
class SakServices(
    val secrets: SecretService,
    val statistics: BucketStatisticsCache,
    val bucketService: S3BucketService,
    val redirectService: RedirectService,
    val labelService: LabelService,
    val noteService: NoteService,
    val fileService: FileService,
    val fileTextService: FileTextService,
    // whether the domains of the buckets and the redirects point to the server
    val hostingHealth: HostingHealth,
    val redirectsHealth: RedirectsHealth,
)

// the database of the backend: the buckets and their domains, the redirects, the labels, the
// notes, the records of the files and the secrets
fun databaseFile(config: SakConfig) = config.dataDir.resolve("sak.db")

// the content of the file storage, below the first two characters of its sha-256
fun filesDir(config: SakConfig) = config.dataDir.resolve("files")

fun Application.sak(config: SakConfig): SakServices {
    Files.createDirectories(config.dataDir)
    Files.createDirectories(config.s3DataDir)

    val storage = BinaryStorageS3Backend(FilesystemBinaryStorage(config.s3DataDir)) { bucket -> bucketPath(bucket) }
    val database = SakDatabase.open(databaseFile(config))
    // every secret, encrypted under sak.secret_keys; the ones under an older key move to the newest
    val secrets = SecretService(database, config.secretKeys, config.rootDomain)
    secrets.reencryptUnderNewestKey()
    // a bucket is not served at a redirected domain and the other way round
    val domains = DomainRegistry(database, reservedDomains(config))
    val bucketService = S3BucketService(database, secrets, rootDomain = config.rootDomain, reservedNames = RESERVED_SITES, domains = domains)
    val redirectService = RedirectService(database, bucketService, domains = domains)
    val labelService = LabelService(database)
    val fileService = FileService(database, filesDir(config))
    val recognizer = config.openrouter?.let { OpenRouterTextRecognizer(it.url, it.apiToken, it.model) }
    val transcriber = config.openrouter?.let { OpenRouterSpeechTranscriber(it.url, it.apiToken, it.speechModel) }
    val fileTextService = FileTextService(fileService, recognizer, transcriber)
    val noteService = NoteService(database, labelService, fileService)
    val accessCallback = BucketKeysAccessCallback(bucketService)
    val statistics = BucketStatisticsCache(storage)
    statistics.warm(bucketService.all().map { it.name })

    routing {
        route(S3_PATH_PREFIX) {
            s3(InvalidatingS3StorageBackend(storage, statistics::invalidate), accessCallback, proxiedPathPrefix = S3_PATH_PREFIX)
        }
    }

    val domainCheck = DomainDnsCheck(config.dns.servers.map { DnsjavaNameServer(it) })
    val services =
        SakServices(
            secrets,
            statistics,
            bucketService,
            redirectService,
            labelService,
            noteService,
            fileService,
            fileTextService,
            HostingHealth(bucketService, domainCheck, config.addresses),
            RedirectsHealth(redirectService, domainCheck, config.addresses),
        )

    // broken apps, e.g. an unreadable signing key, must not take the s3 api down with them
    config.auth?.let {
        try {
            apps(config, it, services)
        } catch (e: Exception) {
            logger.error(e) { "the apps could not be set up, they are not served" }
        }
    }

    return services
}
