package io.pelle.sak.backend

import io.pelle.sak.backend.users.UserService
import io.pelle.sak.auth.LoginListener
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
import io.pelle.sak.config.SakConfig
import io.pelle.sak.config.SakConfigLoader
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.backend.settings.SettingService
import io.pelle.sak.backend.notes.NotesSettingsService
import io.pelle.sak.backend.settings.SettingsRegistry
import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.redirects.RedirectService
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.FileTextService
import io.pelle.sak.backend.grocery.GroceryDiscovery
import io.pelle.sak.backend.grocery.GrocerySettingsService
import io.pelle.sak.backend.labels.LabelService
import io.pelle.sak.backend.notes.NoteService
import io.pelle.sak.backend.grocery.GroceryService
import io.pelle.sak.backend.todo.TodoService
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.logs.AccessLogsManager
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import java.nio.file.Files
import kotlin.system.exitProcess
import io.pelle.sak.api.installUnexpectedErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

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
    // unloads liquibase, which only migrated the database (migrateIsolated in sak-model); the serial
    // gc would do a full gc only when the old generation is full
    System.gc()

    val reconciler =
        CaddyReconciler(CaddyManager(config.caddyAdminUrl), config = {
            caddyConfig(config, services.bucketService.domains(), services.redirectService.locations(), port, services.bucketStorage::objectsDirectory)
        })
    services.bucketService.onChange(reconciler::reconcileNow)
    services.redirectService.onChange(reconciler::reconcileNow)
    monitor.subscribe(ApplicationStarted) { reconciler.start() }
    monitor.subscribe(ApplicationStopping) { reconciler.stop() }
}

// every service of the backend, made once by sak(); the routes of the apps use them, the caddy
// reconciler hears of the changes of the buckets and the redirects
class SakServices(
    val settings: SettingService,
    // every scope of settings the settings api serves
    val settingsRegistry: SettingsRegistry,
    val statistics: BucketStatisticsCache,
    val bucketService: S3BucketService,
    val redirectService: RedirectService,
    val labelService: LabelService,
    val noteService: NoteService,
    val groceryService: GroceryService,
    val todoService: TodoService,
    val fileService: FileService,
    val fileTextService: FileTextService,
    val groceryDiscovery: GroceryDiscovery,
    // whether the domains of the buckets and the redirects point to the server
    val hostingHealth: HostingHealth,
    val redirectsHealth: RedirectsHealth,
    // the users an admin added, the role user
    val userService: UserService,
    // victorialogs, the access logs of the buckets (EXT-1)
    val accessLogsManager: AccessLogsManager,
    // the objects of the buckets, and the directory caddy serves them from
    val bucketStorage: BinaryStorageS3Backend,
) {
    // the apps that prepare the data of a user who signs in, e.g. the list of their notes (NOTES-1)
    val loginListeners: List<LoginListener> get() = listOf(noteService)
}

// the database of the backend: the buckets and their domains, the redirects, the labels, the
// notes, the grocery and todo lists, the records of the files and the settings
fun databaseFile(config: SakConfig) = config.dataDir.resolve("sak.db")

// the content of the file storage, below the first two characters of its sha-256
fun filesDir(config: SakConfig) = config.dataDir.resolve("files")

fun Application.sak(config: SakConfig): SakServices {
    // every failure and every bare error of the api, for the s3 api and the apps alike
    installUnexpectedErrors()
    Files.createDirectories(config.dataDir)
    Files.createDirectories(config.s3DataDir)

    val storage = bucketStorage(config)
    val database = SakDatabase.open(databaseFile(config))
    // the settings, the signing key of the login among them, plain in the database until the
    // whole database is encrypted (FDR-002)
    val settings = SettingService(database)
    val openRouter = OpenRouterManager(config.openrouter, settings)
    val notesSettings = NotesSettingsService(settings)
    val grocerySettings = GrocerySettingsService(settings)
    // a bucket is not served at a redirected domain and the other way round
    val domains = DomainRegistry(database, reservedDomains(config))
    val bucketService = S3BucketService(database, baseDomain = config.baseDomain, reservedNames = RESERVED_SITES, domains = domains)
    val redirectService = RedirectService(database, bucketService, domains = domains)
    val labelService = LabelService(database)
    val fileService = FileService(database, filesDir(config))
    val fileTextService = FileTextService(fileService, openRouter::textRecognizer, openRouter::speechTranscriber, notesSettings::aiEnabled)
    val noteService = NoteService(database, labelService, fileService)
    val groceryService = GroceryService(database)
    val todoService = TodoService(database)
    val accessCallback = BucketKeysAccessCallback(bucketService)
    val statistics = BucketStatisticsCache(storage)
    statistics.warm(bucketService.all().map { it.name })
    removeStaleUploads(storage, bucketService, statistics)

    routing {
        route(S3_PATH_PREFIX) {
            s3(InvalidatingS3StorageBackend(storage, statistics::invalidate), accessCallback, proxiedPathPrefix = S3_PATH_PREFIX)
        }
    }

    val domainCheck = DomainDnsCheck(config.dns.servers.map { DnsjavaNameServer(it) })
    val services =
        SakServices(
            settings,
            SettingsRegistry(listOf(openRouter, notesSettings, grocerySettings)),
            statistics,
            bucketService,
            redirectService,
            labelService,
            noteService,
            groceryService,
            todoService,
            fileService,
            fileTextService,
            GroceryDiscovery(fileService, openRouter::ingredientRecognizer, grocerySettings::aiEnabled),
            HostingHealth(bucketService, domainCheck, config.addresses),
            RedirectsHealth(redirectService, domainCheck, config.addresses),
            UserService(database, config.auth?.admins.orEmpty()),
            AccessLogsManager(config.logs.victorialogsUrl),
            storage,
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

// the multipart uploads of the buckets that were neither completed nor aborted and had no part for
// STALE_UPLOAD_AGE go, at the start and every STALE_UPLOAD_CHECK (SEC-9): one key of a bucket can
// not fill the data volume with parts it never completes
private fun Application.removeStaleUploads(
    storage: BinaryStorageS3Backend,
    buckets: S3BucketService,
    statistics: BucketStatisticsCache,
) = launch(Dispatchers.IO) {
    while (isActive) {
        try {
            val before = Instant.now().minus(STALE_UPLOAD_AGE)
            buckets.all().forEach { bucket ->
                val removed = storage.removeStaleUploads(bucket.name, before)
                if (removed > 0) {
                    logger.info { "removed $removed unfinished uploads of the bucket ${bucket.name}" }
                    statistics.invalidate(bucket.name)
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "could not remove the unfinished uploads of the buckets" }
        }
        delay(STALE_UPLOAD_CHECK.toMillis())
    }
}

private val STALE_UPLOAD_AGE: Duration = Duration.ofDays(1)

private val STALE_UPLOAD_CHECK: Duration = Duration.ofHours(1)
