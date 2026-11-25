package io.pelle.sak.s3.storage

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.pelle.sak.api.storage.KeyOrderEntry
import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.api.storage.Walk
import io.pelle.sak.api.storage.model.BlobPath
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.WriteGuard
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random

// a listing walks the bucket in the order of the keys and stops once its page is full (#153): it
// answers what a listing of all keys, sorted and cut, answers, and a page reads only what it lists
class LocalStorageS3BackendListingTest {
    @TempDir
    lateinit var tempDir: Path

    private val resolver = S3BucketPathResolver { bucket -> BlobPath(listOf("root", "$bucket-data")) }

    private fun backend(storage: LocalStorage) = LocalStorageS3Backend(storage, resolver, WriteGuard({ Long.MAX_VALUE }, minFreeBytes = 0))

    // the keys put into the bucket, those that conflict with a key put before ('a' and 'a/b') left out
    private fun fill(
        backend: LocalStorageS3Backend,
        keys: List<String>,
    ): Set<String> =
        keys.filterTo(sortedSetOf()) { key ->
            try {
                backend.putObject("bucket", key, "content of $key".byteInputStream(), ObjectMetadata())
                true
            } catch (_: KeyConflictException) {
                false
            } catch (_: java.nio.file.FileSystemException) {
                // an object on the way to the key ('ab' for 'ab/ab'), which the backend tells apart not
                // always as a conflict
                false
            }
        }

    // what a listing answered before #153: all keys sorted, then filtered and cut
    private data class Expected(
        val objects: List<String>,
        val commonPrefixes: List<String>,
        val truncated: Boolean,
    )

    private fun reference(
        keys: Set<String>,
        prefix: String?,
        delimiter: String?,
        startAfter: String?,
        maxKeys: Int,
    ): Expected {
        val objects = mutableListOf<String>()
        val commonPrefixes = sortedSetOf<String>()
        var truncated = false
        for (key in keys.sorted().filter { (prefix == null || it.startsWith(prefix)) && (startAfter == null || it > startAfter) }) {
            val rest = key.substring(prefix?.length ?: 0)
            val rolledUp = delimiter?.let { rest.indexOf(it) }?.takeIf { it >= 0 }?.let { (prefix ?: "") + rest.substring(0, it + delimiter.length) }
            if (rolledUp != null && rolledUp in commonPrefixes) continue
            if (objects.size + commonPrefixes.size >= maxKeys) {
                truncated = true
                break
            }
            if (rolledUp != null) commonPrefixes.add(rolledUp) else objects.add(key)
        }
        return Expected(objects, commonPrefixes.toList(), truncated)
    }

    private fun ObjectListing.answered() = Expected(objects.map { it.key }, commonPrefixes, isTruncated)

    // every page from the first on, joined: their objects and their common prefixes
    private fun allPages(
        backend: LocalStorageS3Backend,
        prefix: String?,
        delimiter: String?,
        maxKeys: Int,
    ): Pair<List<String>, List<String>> {
        val objects = mutableListOf<String>()
        val commonPrefixes = mutableListOf<String>()
        var startAfter: String? = null
        var pages = 0
        do {
            val page = backend.listObjects("bucket", prefix, delimiter, startAfter, maxKeys)
            objects += page.objects.map { it.key }
            commonPrefixes += page.commonPrefixes
            startAfter = page.nextContinuationToken?.let { ContinuationToken.key(it)!! }
            check(++pages < 10_000) { "the listing does not end" }
        } while (page.isTruncated)
        return objects to commonPrefixes
    }

    @Test
    fun `a page is the one of all keys sorted and cut, for keys a sort of the paths would get wrong`() {
        val segments = listOf("a", "b", "a-b", "a.b", "ab", "ä", "0", "z")
        val random = Random(153)
        val keys =
            List(400) {
                List(random.nextInt(1, 4)) { segments.random(random) }.joinToString("/")
            }
        val backend = backend(FilesystemLocalStorage(tempDir))
        val stored = fill(backend, keys)

        val prefixes = listOf(null, "a", "a/", "a-", "a/b", "a/b/", "ä", "z/0", "x")
        val starts = listOf(null, "a", "a/", "a-b", "a-b/zzz", "a/b/ä", "b", "ä/ä/ä", "zz") + stored.shuffled(random).take(10)
        for (prefix in prefixes) {
            for (delimiter in listOf(null, "/")) {
                for (maxKeys in listOf(1, 2, 3, 7, 1000)) {
                    for (startAfter in starts) {
                        withClue("prefix=$prefix delimiter=$delimiter startAfter=$startAfter maxKeys=$maxKeys") {
                            backend.listObjects("bucket", prefix, delimiter, startAfter, maxKeys).answered() shouldBe
                                reference(stored, prefix, delimiter, startAfter, maxKeys)
                        }
                    }
                    // page by page with the tokens: all of it, in order, each once
                    withClue("pages of prefix=$prefix delimiter=$delimiter maxKeys=$maxKeys") {
                        val all = reference(stored, prefix, delimiter, null, Int.MAX_VALUE)
                        allPages(backend, prefix, delimiter, maxKeys) shouldBe (all.objects to all.commonPrefixes)
                    }
                }
            }
        }
    }

    @Test
    fun `a page reads only what it lists, not the bucket`() {
        // the entries the walk met, of all listings
        var met = 0
        val files = FilesystemLocalStorage(tempDir)
        val counting =
            object : LocalStorage by files {
                override fun walkInKeyOrder(
                    root: BlobPath,
                    below: List<String>,
                    startAfter: String?,
                    skipUnder: String?,
                    visit: (KeyOrderEntry) -> Walk,
                ) = files.walkInKeyOrder(root, below, startAfter, skipUnder) { entry ->
                    met++
                    visit(entry)
                }
            }
        val backend = backend(counting)
        // 20 folders of 50 objects and 500 objects at the top: 1,500 objects
        fill(backend, (0 until 20).flatMap { folder -> (0 until 50).map { "folder$folder/object$it" } } + (0 until 500).map { "top$it" })

        met = 0
        backend.listObjects("bucket", null, null, null, 10).objects.size shouldBe 10
        // the first folder and the first ten of its objects, and the one that tells the page is full
        (met <= 12) shouldBe true

        met = 0
        backend.listObjects("bucket", null, "/", null, 10).commonPrefixes.size shouldBe 10
        // ten folders as common prefixes, never gone into
        (met <= 11) shouldBe true

        met = 0
        val next = backend.listObjects("bucket", "folder7/", null, null, 1000)
        next.objects.size shouldBe 50
        // only the folder of the prefix
        met shouldBe 50
    }
}
