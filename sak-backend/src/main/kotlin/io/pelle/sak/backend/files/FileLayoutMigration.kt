package io.pelle.sak.backend.files

import io.pelle.sak.backend.files.FileService.Companion.CONTENT
import io.pelle.sak.backend.files.FileService.Companion.NO_THUMBNAIL
import io.pelle.sak.backend.files.FileService.Companion.blob
import io.pelle.sak.backend.files.FileService.Companion.thumbnailName
import io.pelle.sak.model.files.FileLocation
import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.BlobPath

// moves the content of the files from where the releases before the resource ids kept it,
// <first 2 characters of the sha-256>/<sha-256>[suffix of a thumbnail], to <resource id>/<name>
// (MODEL-8). The one reading of what an older release wrote beside the local settings of sakadmin: it
// goes with the column sha256 in a later release.
//
// Runs at every start before the routes, and does nothing once every file has its content. A file
// whose <resource id>/content is there is moved, so a run that stops anywhere is taken up by the next
// one: the thumbnails go first, the content last. The files of one content (the same upload of
// several) get a copy each, the last of them the content itself, so the content stays where it was
// until no file needs it there any more. A thumbnail that is not moved is made again when asked for.
class FileLayoutMigration internal constructor(
    private val storage: BinaryStorage,
    private val locations: List<FileLocation>,
) {
    data class Result(
        // the files whose content was moved or copied to their resource id
        val migrated: Int,
        // the files whose content is neither at their resource id nor below their sha-256
        val missing: Int,
        // the directories of the old layout that are still there, with what no file refers to
        val oldDirectories: Int,
    )

    internal fun run(): Result {
        var migrated = 0
        var missing = 0
        locations
            .filter { storage.stat(blob(it.resourceId, CONTENT)) == null }
            .groupBy { it.sha256 }
            .forEach { (sha256, files) ->
                val blobs = oldBlobs(sha256)
                if (CONTENT !in blobs.values) {
                    missing += files.size
                    return@forEach
                }
                // the content last, it is what tells a file as moved
                val order = blobs.entries.sortedBy { it.value == CONTENT }
                files.dropLast(1).forEach { file ->
                    order.forEach { (from, name) -> storage.open(from)?.let { storage.write(blob(file.resourceId, name), it) } }
                }
                order.forEach { (from, name) -> storage.move(from, blob(files.last().resourceId, name)) }
                migrated += files.size
            }
        val oldDirectories = storage.children(BlobPath(emptyList())).count { OLD_DIRECTORY.matches(it) }
        return Result(migrated, missing, oldDirectories)
    }

    // the blobs of the old layout of the content and the names they get below a resource id
    private fun oldBlobs(sha256: String): Map<BlobPath, String> {
        val directory = BlobPath(listOf(sha256.take(2)))
        return storage
            .children(directory)
            .filter { it.startsWith(sha256) }
            .mapNotNull { blob -> newName(blob.removePrefix(sha256))?.let { directory.resolve(blob) to it } }
            .toMap()
    }

    private fun newName(suffix: String): String? =
        when {
            suffix.isEmpty() -> CONTENT
            suffix == ".thumbnail.jpg" -> thumbnailName(FileService.THUMBNAIL_SIZE)
            suffix == ".no-thumbnail" -> NO_THUMBNAIL
            else ->
                OLD_THUMBNAIL.matchEntire(suffix)?.groupValues?.get(1)?.toInt()
                    ?.takeIf { it in FileService.SCALED_SIZES }
                    ?.let { thumbnailName(it) }
        }

    private companion object {
        val OLD_DIRECTORY = Regex("^[0-9a-f]{2}$")

        val OLD_THUMBNAIL = Regex("^\\.thumbnail-(\\d+)\\.jpg$")
    }
}
