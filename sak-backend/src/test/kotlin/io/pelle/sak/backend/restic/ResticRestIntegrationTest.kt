package io.pelle.sak.backend.restic

import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.S3Endpoint
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.api.s3
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.storage.model.BlobPath
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.model.restic.ResticRepository
import io.pelle.sak.storage.WriteGuard
import io.pelle.sak.storage.FilesystemLocalStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

// the REST backend against the restic of the machine (TEST-2: an integration test, it needs restic
// on the path): init, backup, check of all data, restore, forget and prune, and a wrong token
@Tag("integration")
class ResticRestIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val service by lazy { ResticRepositoryService(SakDatabase.open(tempDir.resolve("sak.db")), mayLogin = { true }) }

    private val volume by lazy {
        val dir = Files.createDirectories(tempDir.resolve("restic"))
        ResticStore(FilesystemLocalStorage(dir), WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0))
    }

    private fun withServer(
        store: ResticStore,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Netty, port = 0) { routing { route(RESTIC_PATH_PREFIX) { resticRest(service, store) } } }.start()
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop()
        }
    }

    // an S3 storage in the process, the S3 api of SAK itself with the bucket "backups": the store that
    // keeps the repositories there, and the directory the objects of the bucket are in
    private fun withBucket(block: (store: ResticStore, objects: Path) -> Unit) {
        val dir = Files.createDirectories(tempDir.resolve("s3"))
        val access =
            object : S3AccessCallback {
                override fun resolveSecretKey(
                    accessKeyId: String,
                    bucket: String?,
                ) = "the/secret+key".takeIf { accessKeyId == "the-access-key" }

                override fun checkAccess(
                    accessKeyId: String,
                    bucket: String,
                    operation: S3Operation,
                    key: String?,
                ) = S3AccessResult.GRANTED
            }
        val server =
            embeddedServer(Netty, port = 0) {
                routing {
                    route("/s3") {
                        s3(LocalStorageS3Backend(FilesystemLocalStorage(dir), { bucket -> BlobPath(listOf(bucket)) }, WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)), access)
                    }
                }
            }.start()
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val bucket = S3Bucket(S3Endpoint("http://localhost:$port/s3", "fsn1"), "backups", "the-deployment", "the-access-key", "the/secret+key")
            block(ResticStore(S3ResticFileStorage(bucket)), dir)
        } finally {
            server.stop()
        }
    }

    // restic with the repository and its password in the environment; its output and its exit code
    private fun restic(
        repository: String,
        vararg arguments: String,
        password: String = "a password of the test",
    ): Pair<Int, String> {
        val process =
            ProcessBuilder(listOf("restic", "--no-cache", "--json") + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment()["RESTIC_REPOSITORY"] = repository
                    environment()["RESTIC_PASSWORD"] = password
                    environment()["HOME"] = tempDir.toString()
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "restic did not end" }
        return process.exitValue() to output
    }

    private fun ok(
        repository: String,
        vararg arguments: String,
    ): String {
        val (code, output) = restic(repository, *arguments)
        check(code == 0) { "restic ${arguments.joinToString(" ")} failed ($code): $output" }
        return output
    }

    private fun url(
        port: Int,
        repository: ResticRepository,
        token: String,
    ) = "rest:http://${repository.id}:$token@127.0.0.1:$port$RESTIC_PATH_PREFIX/${repository.id}/"

    @Test
    fun `restic backs up into a repository of the instance, checks and restores it`() {
        val repository = backsUpAndRestores(volume)

        // the files are where BACKUP-1 has them, below the resource id (MODEL-8), none is left staged
        Files.list(tempDir.resolve("restic/${repository.resourceId}")).use { dirs ->
            dirs.map { it.fileName.toString() }.toList().sorted() shouldContainExactly listOf("config", "data", "index", "keys", "snapshots")
        }
    }

    @Test
    fun `restic backs up into a repository in the bucket of the S3 storage, checks and restores it`() {
        withBucket { store, objects ->
            val repository = backsUpAndRestores(store)

            // every file is an object of the bucket, where it is on a data volume: below
            // <deployment>/backend/data/restic/<resource id>; the data volume has none
            Files.walk(objects).use { paths -> paths.anyMatch { it.toString().contains("/the-deployment/backend/data/restic/${repository.resourceId}/data/") } } shouldBe true
            Files.exists(tempDir.resolve("restic")) shouldBe false
            (store.usage(repository.resourceId) > 20L * 1024 * 1024) shouldBe true
        }
    }

    // init, two backups, a check of all data, a restore, forget and prune, and a wrong token
    private fun backsUpAndRestores(store: ResticStore): ResticRepository {
        val repository = (service.create("anna@example.org", "laptop") as ResticRepositoryCreateResult.Created).repository
        val token = service.token("anna@example.org", repository.id)!!
        val source = Files.createDirectories(tempDir.resolve("source/documents"))
        source.resolve("letter.txt").writeText("Dear restic")
        // larger than one pack (16 MiB), and random, so it does not deduplicate or compress
        Files.write(source.resolve("big.bin"), ByteArray(20 * 1024 * 1024).also { java.util.Random(1).nextBytes(it) })

        withServer(store) { port ->
            val repo = url(port, repository, token)
            ok(repo, "init")
            ok(repo, "backup", source.toString())
            source.resolve("letter.txt").writeText("Dear restic, again")
            ok(repo, "backup", source.toString())
            ok(repo, "snapshots").let { it.split("\"time\"").size - 1 shouldBe 2 }
            ok(repo, "check", "--read-data")

            val target = tempDir.resolve("target")
            ok(repo, "restore", "latest", "--target", target.toString())
            target.resolve(source.toString().removePrefix("/")).resolve("letter.txt").readText() shouldBe "Dear restic, again"
            Files.mismatch(target.resolve(source.toString().removePrefix("/")).resolve("big.bin"), source.resolve("big.bin")) shouldBe -1L

            ok(repo, "forget", "--keep-last", "1", "--prune")
            ok(repo, "check")
            ok(repo, "snapshots").split("\"time\"").size - 1 shouldBe 1

            // a wrong token is refused
            val (code, output) = restic(url(port, repository, "wrong"), "snapshots")
            code shouldBe 1
            output shouldContain "401"
        }
        return repository
    }
}
