package io.pelle.sak.backend.restic

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.pelle.sak.core.RateLimiter
import io.pelle.sak.model.SakDatabase
import io.pelle.sak.s3.storage.WriteGuard
import io.pelle.sak.storage.FilesystemBinaryStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HexFormat

private const val ANNA = "anna@example.org"

private const val BERT = "bert@example.org"

// the REST protocol of restic (BACKUP-1, BACKUP-2) without restic: its routes, its login and what it
// refuses; ResticRestIntegrationTest runs restic against it
class ResticRestTest {
    @TempDir
    lateinit var tempDir: Path

    // the owners that may no longer sign in, e.g. a removed user
    private val removed = mutableSetOf<String>()

    private val resticDir by lazy { Files.createDirectories(tempDir.resolve("restic")) }

    // how many more looks at the free bytes find room, then the volume is full
    private var roomFor = Long.MAX_VALUE

    private val store by lazy {
        ResticStore(
            FilesystemBinaryStorage(resticDir),
            WriteGuard({ if (roomFor-- > 0) Long.MAX_VALUE else 0L }, minFreeBytes = 1, maxBytes = 1024, checkEvery = 100),
        )
    }

    private val database by lazy { SakDatabase.open(tempDir.resolve("sak.db")) }

    private val service by lazy { ResticRepositoryService(database, mayLogin = { it !in removed }, store = store) }

    private val http = HttpClient.newHttpClient()

    // a request of `path` with a basic login of `user` and `password`, a body and headers
    private class Client(
        private val http: HttpClient,
        val port: Int,
    ) {
        fun send(
            method: String,
            path: String,
            login: Pair<String, String>? = null,
            body: ByteArray? = null,
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse<String> =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                    .apply {
                        login?.let { (user, password) ->
                            header("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))
                        }
                        headers.forEach { (name, value) -> header(name, value) }
                    }.method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
    }

    private fun restTest(
        failures: RateLimiter = RateLimiter(30, Duration.ofHours(1)),
        block: Client.(id: Int, token: String) -> Unit,
    ) {
        val server = embeddedServer(Netty, port = 0) { routing { route(RESTIC_PATH_PREFIX) { resticRest(service, store, failures) } } }.start()
        try {
            val repository = (service.create(ANNA, "laptop") as ResticRepositoryCreateResult.Created).repository
            Client(http, runBlocking { server.engine.resolvedConnectors().first().port }).block(repository.id, service.token(ANNA, repository.id)!!)
        } finally {
            server.stop()
        }
    }

    private fun sha256(content: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))

    @Test
    fun `a file is saved once under the hash of its content, read whole or in part, listed and deleted`() =
        restTest { id, token ->
            val login = "$id" to token
            val content = "packed and encrypted".toByteArray()
            val name = sha256(content)
            val path = "/restic/$id/data/$name"
            send("POST", "/restic/$id/?create=true", login).statusCode() shouldBe 200
            send("POST", path, login, content).statusCode() shouldBe 200
            // never twice
            send("POST", path, login, content).statusCode() shouldBe 403
            send("HEAD", path, login).headers().firstValue("Content-Length").get() shouldBe "${content.size}"
            send("GET", path, login).body() shouldBe "packed and encrypted"
            send("GET", path, login, headers = mapOf("Range" to "bytes=7-9")).let {
                it.statusCode() shouldBe 206
                it.body() shouldBe "and"
                it.headers().firstValue("Content-Range").get() shouldBe "bytes 7-9/${content.size}"
            }
            send("GET", path, login, headers = mapOf("Range" to "bytes=99-")).statusCode() shouldBe 416
            send("GET", "/restic/$id/data/", login, headers = mapOf("Accept" to "application/vnd.x.restic.rest.v2")).let {
                it.headers().firstValue("Content-Type").get() shouldContain "application/vnd.x.restic.rest.v2"
                it.body() shouldBe """[{"name":"$name","size":${content.size}}]"""
            }
            send("GET", "/restic/$id/data/", login).body() shouldBe """["$name"]"""
            send("DELETE", path, login).statusCode() shouldBe 200
            send("GET", path, login).statusCode() shouldBe 404
            send("DELETE", path, login).statusCode() shouldBe 404
        }

    @Test
    fun `the config has no hash, a damaged or too large file is not kept`() =
        restTest { id, token ->
            val login = "$id" to token
            send("POST", "/restic/$id/config", login, "any config".toByteArray()).statusCode() shouldBe 200
            send("GET", "/restic/$id/config", login).body() shouldBe "any config"
            send("POST", "/restic/$id/keys/${"0".repeat(64)}", login, "not its hash".toByteArray()).statusCode() shouldBe 400
            val large = ByteArray(2048)
            send("POST", "/restic/$id/data/${sha256(large)}", login, large).statusCode() shouldBe 413
            send("GET", "/restic/$id/keys/", login).body() shouldBe "[]"
            send("GET", "/restic/$id/data/", login).body() shouldBe "[]"
            // nothing staged is left
            Files.walk(resticDir).use { files ->
                files.filter { Files.isRegularFile(it) }.map { resticDir.relativize(it).toString() }.toList()
            } shouldBe listOf("${service.authorize(id, token)}/config")
        }

    @Test
    fun `the files of a repository are below its resource id, never reached by the id of a later one (MODEL-8)`() =
        restTest { id, token ->
            val rid = service.authorize(id, token)!!
            send("POST", "/restic/$id/config", "$id" to token, "any config".toByteArray()).statusCode() shouldBe 200
            Files.exists(resticDir.resolve("$rid/config")) shouldBe true
            Files.exists(resticDir.resolve("$id")) shouldBe false
            // the resource id is no address of the REST backend
            send("GET", "/restic/$rid/config", "$rid" to token).statusCode() shouldBe 404

            // a restore of the instance puts back a database without the repository: its files stay, nobody
            // reaches them, the next repository gets a directory of its own
            val second = (ResticRepositoryService(SakDatabase.open(tempDir.resolve("restored.db")), mayLogin = { true }, store = store).create(ANNA, "laptop") as ResticRepositoryCreateResult.Created).repository
            second.resourceId shouldNotBe rid
            store.exists(second.resourceId) shouldBe false
            store.leftovers(setOf(second.resourceId)) shouldBe listOf(rid.value)
            store.leftovers(service.resourceIds()) shouldBe emptyList()
        }

    @Test
    fun `a repository with data but no config gets no new config, one with a key only does (BK-6)`() =
        restTest { id, token ->
            val login = "$id" to token
            // restic init writes its key first
            send("POST", "/restic/$id/keys/${sha256("key".toByteArray())}", login, "key".toByteArray()).statusCode() shouldBe 200
            send("POST", "/restic/$id/config", login, "config".toByteArray()).statusCode() shouldBe 200
            send("DELETE", "/restic/$id/config", login).statusCode() shouldBe 200
            listOf("data", "index", "snapshots").forEach { type ->
                val content = "$type of a backup".toByteArray()
                send("POST", "/restic/$id/$type/${sha256(content)}", login, content).statusCode() shouldBe 200
                send("POST", "/restic/$id/config", login, "a new config".toByteArray()).statusCode() shouldBe 403
                send("GET", "/restic/$id/config", login).statusCode() shouldBe 404
                send("DELETE", "/restic/$id/$type/${sha256(content)}", login).statusCode() shouldBe 200
            }
        }

    @Test
    fun `a removed user reaches no repository of theirs, their files stay`() =
        restTest { id, token ->
            send("POST", "/restic/$id/config", "$id" to token, "any config".toByteArray()).statusCode() shouldBe 200
            removed += ANNA
            send("GET", "/restic/$id/config", "$id" to token).statusCode() shouldBe 401
            send("POST", "/restic/$id/keys/${sha256("key".toByteArray())}", "$id" to token, "key".toByteArray()).statusCode() shouldBe 401
            removed -= ANNA
            send("GET", "/restic/$id/config", "$id" to token).body() shouldBe "any config"
        }

    @Test
    fun `only the files of a repository are reached`() =
        restTest { id, token ->
            listOf(
                "/restic/$id/",
                "/restic/$id/other/",
                "/restic/$id/data/short",
                "/restic/$id/data/${"g".repeat(64)}",
                "/restic/$id/data/${"a".repeat(64)}/more",
                "/restic/$id/staging/",
                "/restic/$id/..%2F..%2Fsak.db",
            ).forEach { path -> send("GET", path, "$id" to token).statusCode() shouldBe 404 }
        }

    @Test
    fun `the token of the repository is its only login, a client that fails too often is refused`() =
        restTest(failures = RateLimiter(3, Duration.ofHours(1))) { id, token ->
            val other = (service.create(ANNA, "other") as ResticRepositoryCreateResult.Created).repository
            send("GET", "/restic/$id/config").let {
                it.statusCode() shouldBe 401
                it.headers().firstValue("WWW-Authenticate").get() shouldContain "Basic"
            }
            // the token of another repository, or the user of another
            send("GET", "/restic/$id/config", "$id" to service.token(ANNA, other.id)!!).statusCode() shouldBe 401
            send("GET", "/restic/${other.id}/config", "$id" to token).statusCode() shouldBe 401
            send("GET", "/restic/$id/config", "$id" to "wrong").statusCode() shouldBe 401
            // the failures used up at this repository: a wrong login is refused, the right one goes through,
            // another repository from the same address is not locked out (BK-17)
            send("GET", "/restic/$id/config", "$id" to "wrong").statusCode() shouldBe 429
            send("GET", "/restic/$id/config", "$id" to token).statusCode() shouldBe 404
            send("GET", "/restic/${other.id}/config", "${other.id}" to service.token(ANNA, other.id)!!).statusCode() shouldBe 404
            send("GET", "/restic/${other.id}/config", "${other.id}" to "wrong").statusCode() shouldBe 401
        }

    @Test
    fun `a login is refused unless it is the user and the token of the repository of the path`() =
        restTest { id, token ->
            val berts = (service.create(BERT, "laptop") as ResticRepositoryCreateResult.Created).repository
            val bertsToken = service.token(BERT, berts.id)!!
            val content = "of bert".toByteArray()
            send("POST", "/restic/${berts.id}/data/${sha256(content)}", "${berts.id}" to bertsToken, content).statusCode() shouldBe 200
            listOf(
                "${berts.id}" to bertsToken,
                "$id" to bertsToken,
                "${berts.id}" to token,
            ).forEach { login -> send("GET", "/restic/$id/data/", login).statusCode() shouldBe 401 }
            // an id that is no repository, a malformed header, an empty password
            send("GET", "/restic/4711/data/", "4711" to token).statusCode() shouldBe 401
            send("GET", "/restic/x/data/", "x" to token).statusCode() shouldBe 404
            send("GET", "/restic/$id/data/", headers = mapOf("Authorization" to "Basic not base64!")).statusCode() shouldBe 401
            send("GET", "/restic/$id/data/", headers = mapOf("Authorization" to "Bearer $token")).statusCode() shouldBe 401
            send("GET", "/restic/$id/data/", headers = mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("$id:".toByteArray()))).statusCode() shouldBe 401
            // the list of one never has a file of the other
            send("GET", "/restic/$id/data/", "$id" to token).body() shouldBe "[]"
            send("GET", "/restic/${berts.id}/data/", "${berts.id}" to bertsToken).body() shouldBe "[\"${sha256(content)}\"]"
        }

    @Test
    fun `a range is a suffix or from a start, clamped to the file, anything else is the whole file`() =
        restTest { id, token ->
            val login = "$id" to token
            val content = "0123456789".toByteArray()
            val path = "/restic/$id/data/${sha256(content)}"
            send("POST", path, login, content).statusCode() shouldBe 200
            fun range(header: String) = send("GET", path, login, headers = mapOf("Range" to header))
            range("bytes=-3").let {
                it.statusCode() shouldBe 206
                it.body() shouldBe "789"
                it.headers().firstValue("Content-Range").get() shouldBe "bytes 7-9/10"
            }
            range("bytes=-30").body() shouldBe "0123456789"
            range("bytes=5-999").let {
                it.statusCode() shouldBe 206
                it.body() shouldBe "56789"
            }
            range("bytes=10-").statusCode() shouldBe 416
            range("bytes=5-2").statusCode() shouldBe 416
            // several parts or no bytes: the whole file
            listOf("bytes=0-1,3-4", "lines=1-2", "bytes=x-y").forEach { header ->
                range(header).let {
                    it.statusCode() shouldBe 200
                    it.body() shouldBe "0123456789"
                }
            }
            send("HEAD", "/restic/$id/data/${sha256("none".toByteArray())}", login).statusCode() shouldBe 404
            send("PUT", path, login, content).statusCode() shouldBe 405
            send("PATCH", "/restic/$id/config", login, content).statusCode() shouldBe 405
        }

    @Test
    fun `a config is written once, a second one changes nothing`() =
        restTest { id, token ->
            val login = "$id" to token
            send("POST", "/restic/$id/config", login, "first".toByteArray()).statusCode() shouldBe 200
            send("POST", "/restic/$id/config", login, "second".toByteArray()).statusCode() shouldBe 403
            send("GET", "/restic/$id/config", login).body() shouldBe "first"
        }

    @Test
    fun `without room an upload is refused before and while it streams, and leaves nothing`() =
        restTest { id, token ->
            val login = "$id" to token
            val content = ByteArray(600) { it.toByte() }
            val path = "/restic/$id/data/${sha256(content)}"
            roomFor = 0
            send("POST", path, login, content).statusCode() shouldBe 507
            // room at the start, none after the first 100 bytes
            roomFor = 1
            send("POST", path, login, content).statusCode() shouldBe 507
            roomFor = Long.MAX_VALUE
            send("GET", "/restic/$id/data/", login).body() shouldBe "[]"
            files() shouldBe emptyList()
        }

    @Test
    fun `an upload that breaks off leaves nothing behind`() =
        restTest { id, token ->
            val login = "$id" to token
            val content = ByteArray(800) { it.toByte() }
            val name = sha256(content)
            java.net.Socket("127.0.0.1", port).use { socket ->
                val auth = Base64.getEncoder().encodeToString("$id:$token".toByteArray())
                socket.getOutputStream().apply {
                    write(
                        "POST /restic/$id/data/$name HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Basic $auth\r\nContent-Length: ${content.size}\r\n\r\n"
                            .toByteArray(),
                    )
                    write(content, 0, 300)
                    flush()
                }
                Thread.sleep(200)
            }
            // the server notices the end of the connection on its own time
            val until = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (files().isNotEmpty() && System.nanoTime() < until) Thread.sleep(50)
            files() shouldBe emptyList()
            send("GET", "/restic/$id/data/", login).body() shouldBe "[]"
            store.usage(service.authorize(id, token)!!) shouldBe 0
        }

    @Test
    fun `the size of a repository is counted once, then changed with each save and delete (BK-16)`() =
        restTest { id, token ->
            val login = "$id" to token
            val rid = service.authorize(id, token)!!
            send("POST", "/restic/$id/config", login, "any config".toByteArray()).statusCode() shouldBe 200
            // the first count walks the files, a staged one left out
            Files.createDirectories(resticDir.resolve("$rid/staging"))
            Files.write(resticDir.resolve("$rid/staging/half"), ByteArray(100))
            store.usage(rid) shouldBe 10

            val content = "packed and encrypted".toByteArray()
            send("POST", "/restic/$id/data/${sha256(content)}", login, content).statusCode() shouldBe 200
            store.usage(rid) shouldBe 10L + content.size
            // a file the store did not write is not walked again
            Files.write(resticDir.resolve("$rid/locks/${"1".repeat(64)}").also { Files.createDirectories(it.parent) }, ByteArray(1000))
            store.usage(rid) shouldBe 10L + content.size
            send("DELETE", "/restic/$id/data/${sha256(content)}", login).statusCode() shouldBe 200
            send("DELETE", "/restic/$id/data/${sha256(content)}", login).statusCode() shouldBe 404
            store.usage(rid) shouldBe 10
            // a refused save changes nothing
            send("POST", "/restic/$id/config", login, "other config".toByteArray()).statusCode() shouldBe 403
            store.usage(rid) shouldBe 10
            // a new store counts again
            ResticStore(FilesystemBinaryStorage(resticDir), WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0)).usage(rid) shouldBe 1010
        }

    // every file below the directory of the repositories, staged ones included
    private fun files(): List<String> =
        Files.walk(resticDir).use { files -> files.filter { Files.isRegularFile(it) }.map { resticDir.relativize(it).toString() }.toList() }
}
