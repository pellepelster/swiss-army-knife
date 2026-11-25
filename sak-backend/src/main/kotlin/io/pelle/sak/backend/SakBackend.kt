package io.pelle.sak.backend

import io.pelle.sak.backend.hosting.bucketBlobs
import io.pelle.sak.backend.hosting.bucketStorage
import io.pelle.sak.backend.hosting.RESERVED_BUCKETS
import io.pelle.sak.backend.hosting.S3_PATH_PREFIX
import io.pelle.sak.s3.storage.S3BucketStorage
import io.pelle.sak.auth.Role
import io.pelle.sak.backend.hosting.removeStaleUploads
import io.pelle.sak.backend.hosting.STALE_UPLOAD_CHECK
import java.time.Instant
import io.pelle.sak.backend.hosting.STALE_UPLOAD_AGE
import io.pelle.sak.core.logCause
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
import io.pelle.sak.settings.SettingService
import io.pelle.sak.backend.notes.NotesUserSettings
import io.pelle.sak.settings.user.UserSettingsService
import io.pelle.sak.settings.SettingsRegistry
import io.pelle.sak.backend.openrouter.OpenRouterManager
import io.pelle.sak.backend.storage.S3StorageManager
import io.pelle.sak.backend.storage.StorageUsageService
import io.pelle.sak.backend.redirects.RedirectService
import io.pelle.sak.backend.files.FileService
import io.pelle.sak.backend.files.FileTextService
import io.pelle.sak.backend.grocery.GroceryDiscovery
import io.pelle.sak.backend.grocery.GroceryUserSettings
import io.pelle.sak.backend.labels.LabelService
import io.pelle.sak.backend.notes.NoteService
import io.pelle.sak.backend.grocery.GroceryService
import io.pelle.sak.backend.todo.TodoService
import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.LocalBlobStore
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.backend.restic.resticRest
import io.pelle.sak.backend.restic.ResticStore
import io.pelle.sak.backend.restic.ResticSettingsService
import io.pelle.sak.backend.restic.BlobResticFileStorage
import io.pelle.sak.backend.restic.ResticRepositoryService
import io.pelle.sak.backend.restic.ResticBackupPlanService
import io.pelle.sak.backend.restic.RESTIC_PATH_PREFIX
import io.pelle.sak.backend.hosting.S3BucketService
import io.pelle.sak.ingress.CaddyManager
import io.pelle.sak.logs.AccessLogsManager
import io.pelle.sak.s3.api.s3
import java.nio.file.Files
import kotlin.system.exitProcess
import io.pelle.sak.api.installUnexpectedErrors
import java.time.Duration

private val logger = KotlinLogging.logger {}

const val BUNDLED_CONFIG = "application.yaml"

fun main(args: Array<String>) {
    if (args.none { it.startsWith("-config=") }) {
        System.err.println("usage: sak-backend -config=<config.yaml>")
        exitProcess(1)
    }

    EngineMain.main(arrayOf("-config=$BUNDLED_CONFIG") + args)
}

// what the job of the reconciler is called in the log
private const val CONFIGURE_CADDY = "configure caddy"

fun Application.module() {
    val config = SakConfigLoader.load(environment.config)
    val port = environment.config.property("ktor.deployment.port").getString().toInt()
    logger.info { "starting sak-backend on port $port with data dir ${config.dataDir}" }

    val services = sak(config)

    val reconciler =
        CaddyReconciler(CaddyManager(config.caddyAdminUrl), config = {
            caddyConfig(config, services.bucketService.domains(), services.redirectService.locations(), port, services.bucketStorage::objectsDirectory)
        })
    // at once after a change of the domains, without waiting for the next period
    val configureNow = { services.jobs.once(CONFIGURE_CADDY, reconciler::reconcile) }
    services.bucketService.onChange(configureNow)
    services.redirectService.onChange(configureNow)
    monitor.subscribe(ApplicationStarted) { services.jobs.every(CONFIGURE_CADDY, CaddyReconciler.INTERVAL, reconciler::reconcile) }
}

// what sak() makes once and the apps and the start use: the services, and the few things next to them
// that are no service (the roles, the manager of the access logs, the storage of the buckets, the jobs)
class SakServices(
    val settings: SettingService,
    // every scope of settings the settings api serves
    val settingsRegistry: SettingsRegistry,
    // the settings each user sets for themselves
    val userSettings: UserSettingsService,
    val bucketService: S3BucketService,
    val redirectService: RedirectService,
    val labelService: LabelService,
    val noteService: NoteService,
    val groceryService: GroceryService,
    val todoService: TodoService,
    val resticService: ResticRepositoryService,
    val resticBackupPlanService: ResticBackupPlanService,
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
    val bucketStorage: S3BucketStorage,
    // where the backend runs what it does on its own
    val jobs: BackgroundJobs,
    // what the storages keep
    val storageUsage: StorageUsageService,
    // the roles of an address, asked with every request of a session; none without a login
    val rolesOf: ((email: String) -> Set<Role>)?,
) {
    // the apps that prepare the data of a user who signs in: the first list of each list-based app
    // (LIST-1), the first repository of restic (BACKUP-7)
    val loginListeners: List<LoginListener> get() = listOf(noteService, groceryService, todoService, resticService)
}

// the database of the backend: the buckets and their domains, the redirects, the labels, the
// notes, the grocery and todo lists, the records of the files and the settings
fun databaseFile(config: SakConfig) = config.dataDir.resolve("sak.db")

// the repositories of restic: in the bucket of the S3 storage while the settings of the backups say
// so and the S3 storage is turned on, else on the data volume (BACKUP-1). Asked for each call, a change
// of the setting moves no file
fun resticStore(
    config: SakConfig,
    s3Storage: S3StorageManager,
    resticSettings: ResticSettingsService,
): ResticStore {
    Files.createDirectories(config.resticDir)
    val volume =
        BlobResticFileStorage(
            LocalBlobStore(
                FilesystemLocalStorage(config.resticDir),
                WriteGuard({ Files.getFileStore(config.resticDir).usableSpace }, config.minFreeBytes, ResticStore.MAX_FILE_BYTES),
            ),
        )
    return ResticStore { s3Storage.bucket()?.takeIf { resticSettings.s3() }?.let { BlobResticFileStorage.s3(it) } ?: volume }
}

fun Application.sak(config: SakConfig): SakServices {
    // every failure and every bare error of the api, for the s3 api and the apps alike
    installUnexpectedErrors()
    Files.createDirectories(config.dataDir)
    Files.createDirectories(config.s3DataDir)

    val database = SakDatabase.open(databaseFile(config))
    // the settings, the signing key of the login among them, plain in the database until the
    // whole database is encrypted
    val settings = SettingService(database)
    val openRouter = OpenRouterManager(config.openrouter, settings)
    // the AI of an app, turned on by each user for themselves
    val notesUserSettings = NotesUserSettings(openRouter)
    val groceryUserSettings = GroceryUserSettings(openRouter)
    val userSettings = UserSettingsService(settings, listOf(notesUserSettings.scope, groceryUserSettings.scope))
    val s3Storage = S3StorageManager(settings, config.name)
    val resticSettings = ResticSettingsService(settings)
    // a bucket is not served at a redirected domain and the other way round
    val domains = DomainRegistry(reservedDomains(config))
    // everything the backend does on its own runs here and ends with it
    val jobs = BackgroundJobs()
    monitor.subscribe(ApplicationStopping) { jobs.stop() }
    val bucketBlobs = bucketBlobs(config)
    // the objects are where the service of the buckets says, and the service counts them: each needs
    // the other, the service is made last
    lateinit var bucketService: S3BucketService
    val storage = bucketStorage(config, bucketBlobs) { bucketService.objectsPath(it) }
    val statistics = BucketStatisticsCache(storage, executor = jobs)
    bucketService =
        S3BucketService(
            database,
            baseDomain = config.baseDomain,
            reservedNames = RESERVED_SITES + RESERVED_BUCKETS,
            domains = domains,
            storage = bucketBlobs,
            statistics = statistics,
        )
    val redirectService = RedirectService(database, bucketService, domains = domains)
    val labelService = LabelService(database)
    // the guard asks the volume of the directory, so it is there before the first upload
    Files.createDirectories(config.filesDir)
    val fileStorage = FilesystemLocalStorage(config.filesDir)
    val fileService =
        FileService(
            database,
            fileStorage,
            guard = WriteGuard({ Files.getFileStore(config.filesDir).usableSpace }, config.minFreeBytes, FileService.DEFAULT_MAX_SIZE),
        )
    val fileTextService = FileTextService(fileService, openRouter::textRecognizer, openRouter::speechTranscriber) { owner ->
            userSettings.on(owner, notesUserSettings.scope, notesUserSettings.ai)
        }
    val noteService = NoteService(database, labelService, fileService)
    val groceryService = GroceryService(database, fileService)
    val todoService = TodoService(database)
    val userService = UserService(database, config.auth?.admins.orEmpty())
    // without a login there are no users, so no repository is reached
    val rolesOf = config.auth?.let { rolesOf(it, userService) }
    val resticStore = resticStore(config, s3Storage, resticSettings)
    val resticService = ResticRepositoryService(database, mayLogin = { owner -> rolesOf?.invoke(owner).orEmpty().isNotEmpty() }, store = resticStore)
    val resticBackupPlanService = ResticBackupPlanService(database)
    val accessCallback = BucketKeysAccessCallback(bucketService)
    bucketService.start(jobs, storage)
    // what the storages keep, counted now and every 15 minutes
    val storageUsage =
        StorageUsageService(
            mapOf("files" to fileStorage, "buckets" to bucketBlobs, "restic" to resticStore, "database" to database),
            jobs,
        )
    storageUsage.start()
    reportLeftovers(bucketService, fileService)
    reportResticLeftovers(resticStore, resticService)
    // so the first list of the repositories does not wait for it
    jobs.once("count the repositories of restic") { resticStore.count(resticService.resourceIds()) }
    // the uploads of packs a stopped backend left, in the bucket as on the volume (ST-2)
    jobs.every("remove the unfinished uploads of the repositories of restic", STALE_UPLOAD_CHECK) {
        val removed = resticStore.removeStale(Instant.now().minus(STALE_UPLOAD_AGE))
        if (removed > 0) logger.info { "removed $removed unfinished uploads of the repositories of restic" }
    }

    routing {
        route(S3_PATH_PREFIX) {
            s3(InvalidatingS3StorageBackend(storage, statistics::invalidate), accessCallback, proxiedPathPrefix = S3_PATH_PREFIX)
        }
        route(RESTIC_PATH_PREFIX) {
            resticRest(resticService, resticStore)
        }
    }

    val domainCheck = DomainDnsCheck(config.dns.servers.map { DnsjavaNameServer(it) })
    val services =
        SakServices(
            settings = settings,
            settingsRegistry = SettingsRegistry(listOf(openRouter, s3Storage, resticSettings)),
            userSettings = userSettings,
            bucketService = bucketService,
            redirectService = redirectService,
            labelService = labelService,
            noteService = noteService,
            groceryService = groceryService,
            todoService = todoService,
            resticService = resticService,
            resticBackupPlanService = resticBackupPlanService,
            fileService = fileService,
            fileTextService = fileTextService,
            groceryDiscovery = GroceryDiscovery(fileService, openRouter::ingredientRecognizer) { owner ->
                    userSettings.on(owner, groceryUserSettings.scope, groceryUserSettings.ai)
                },
            hostingHealth = HostingHealth(bucketService, domainCheck, config.addresses),
            redirectsHealth = RedirectsHealth(redirectService, domainCheck, config.addresses),
            userService = userService,
            accessLogsManager = AccessLogsManager(config.logs.victorialogsUrl),
            bucketStorage = storage,
            jobs = jobs,
            storageUsage = storageUsage,
            rolesOf = rolesOf,
        )

    // broken apps, e.g. an unreadable signing key, must not take the s3 api down with them
    config.auth?.let {
        try {
            apps(config, it, services)
        } catch (e: Exception) {
            logger.error(logCause(e)) { "the apps could not be set up, they are not served" }
        }
    }

    return services
}

// tells the admin of the directories of the buckets and of the files that no row has, e.g. left by a
// restore of the instance (MODEL-8); they are kept, and no new bucket or file gets one of them
private fun reportLeftovers(
    buckets: S3BucketService,
    files: FileService,
) {
    try {
        val ofBuckets = buckets.leftovers()
        if (ofBuckets.isNotEmpty()) logger.warn { "${ofBuckets.size} directories of buckets have no bucket, they are kept" }
        val ofFiles = files.leftovers()
        if (ofFiles.isNotEmpty()) logger.warn { "${ofFiles.size} directories of files have no file, they are kept" }
    } catch (e: Exception) {
        logger.warn(logCause(e)) { "could not look for directories of buckets and files without a row" }
    }
}

// tells the admin of the directories of restic without a repository, e.g. left by a restore of the
// instance (MODEL-8); they are kept, and no new repository gets one of them. Only their number is
// logged, there is nothing to do about each one
private fun reportResticLeftovers(
    store: ResticStore,
    service: ResticRepositoryService,
) {
    try {
        val leftovers = store.leftovers(service.resourceIds())
        if (leftovers.isNotEmpty()) logger.warn { "${leftovers.size} directories of restic have no repository, they are kept" }
    } catch (e: Exception) {
        logger.warn(logCause(e)) { "could not look for directories of restic without a repository" }
    }
}


