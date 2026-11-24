package io.pelle.sak.api.storage

import io.pelle.sak.api.storage.model.BlobListEntry
import io.pelle.sak.api.storage.model.BlobPath
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant

// a RemoteStorage in memory, as an S3 storage answers: objects at paths, uploads of parts that show
// once completed, listings a page at a time. What it is told to fail, fails; what it did is in events
class InMemoryRemoteStorage(
    override val partBytes: Int = 5 * 1024 * 1024,
    // how many objects a page has at most, whatever is asked for
    private val pageLimit: Int = 2,
    private val now: () -> Instant = Instant::now,
) : RemoteStorage {
    private class Stored(
        val content: ByteArray,
        val modified: Instant,
    )

    private val objects = sortedMapOf<String, Stored>()

    // the uploads not completed nor aborted yet, by their path, with when they started
    val uploads = mutableMapOf<String, Instant>()

    // what the storage was asked to do, e.g. "complete large" or "abort large"
    val events = mutableListOf<String>()

    // the completion of an upload fails
    var failComplete = false

    // an abort fails
    var failAbort = false

    override val place = "memory"

    private fun key(path: BlobPath) = path.toString()

    override fun size(path: BlobPath) = objects[key(path)]?.content?.size?.toLong()

    override fun openRange(
        path: BlobPath,
        first: Long,
        last: Long,
    ): RemoteRange? {
        val content = objects[key(path)]?.content ?: return null
        if (first >= content.size) return null
        val end = minOf(last + 1, content.size.toLong()).toInt()
        return RemoteRange(content.copyOfRange(first.toInt(), end).inputStream(), content.size.toLong())
    }

    override fun put(
        path: BlobPath,
        content: ByteArray,
    ) {
        events.add("put $path")
        objects[key(path)] = Stored(content.copyOf(), now())
    }

    override fun upload(path: BlobPath): RemoteUpload {
        val key = key(path)
        val parts = ByteArrayOutputStream()
        uploads[key] = now()
        return object : RemoteUpload {
            override fun write(part: ByteArray) {
                events.add("part $path ${part.size}")
                parts.write(part)
            }

            override fun complete() {
                events.add("complete $path")
                if (failComplete) throw IOException("the storage refused the completion")
                uploads.remove(key)
                objects[key] = Stored(parts.toByteArray(), now())
            }

            override fun abort() {
                events.add("abort $path")
                if (failAbort) throw IOException("the storage refused the abort")
                uploads.remove(key)
            }
        }
    }

    override fun delete(path: BlobPath) {
        objects.remove(key(path))
    }

    override fun abortStale(
        prefix: BlobPath,
        before: Instant,
    ): Int {
        val start = if (prefix.segments.isEmpty()) "" else "$prefix/"
        val stale = uploads.filter { (key, started) -> key.startsWith(start) && started.isBefore(before) }.keys
        stale.forEach {
            events.add("abort $it")
            uploads.remove(it)
        }
        return stale.size
    }

    override fun page(
        prefix: BlobPath,
        children: Boolean,
        max: Int?,
        token: String?,
    ): RemotePage {
        val start = if (prefix.segments.isEmpty()) "" else "$prefix/"
        val below = objects.filterKeys { it.startsWith(start) }.map { (key, stored) -> key.removePrefix(start) to stored }
        // with children: the objects right below, and the names of what is deeper, once each
        val entries =
            if (!children) {
                below.map { it.first to it.second }
            } else {
                below.filter { '/' !in it.first }.map { it.first to it.second } +
                    below.filter { '/' in it.first }.map { it.first.substringBefore('/') + "/" }.distinct().map { it to null }
            }.sortedBy { it.first }
        val from = token?.toInt() ?: 0
        val size = minOf(max ?: pageLimit, pageLimit)
        val page = entries.drop(from).take(size)
        val next = (from + size).takeIf { it < entries.size }?.toString()
        return RemotePage(
            page.mapNotNull { (name, stored) -> stored?.let { BlobListEntry(name.split('/'), it.content.size.toLong(), it.modified) } },
            page.filter { it.second == null }.map { it.first.trimEnd('/') },
            next,
        )
    }
}
