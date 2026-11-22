package io.pelle.sak.backend.hosting

import io.pelle.sak.model.s3.S3BucketLocation
import io.pelle.sak.storage.BinaryStorage
import io.pelle.sak.storage.BlobPath

// copies the objects of the buckets from where the releases before the resource ids kept them,
// buckets/<name>, to <resource id> (MODEL-8). What is below buckets/ is never changed or deleted: it is
// the way back right after the update, an admin deletes it by hand. It goes with the column
// objects_at_name in a later release.
//
// Runs at every start before the routes and before caddy is told where the objects are, and does
// nothing once no bucket has its objects at its name. A bucket is copied as a whole and shows at its
// resource id at once (BinaryStorage.copyTree), then it is marked as copied; a run that stops anywhere
// is taken up by the next one. A copy needs the room of the bucket a second time: without it the
// bucket stays at its name, is served from there and is copied by a later start
class BucketLayoutMigration internal constructor(
    private val storage: BinaryStorage,
    private val locations: List<S3BucketLocation>,
    // the bytes free on the volume of the buckets
    private val freeBytes: () -> Long,
    // the bytes that stay free after a copy, for the database, the certificates and the logs
    private val minFreeBytes: Long,
    // marks the bucket of the name: its objects are at its resource id
    private val copied: (name: String) -> Unit,
) {
    data class Result(
        // the buckets whose objects were copied to their resource id
        val copied: Int,
        // the buckets the volume has no room to copy, they stay at their name
        val noRoom: Int,
        // the directories of the old layout that are still there, of copied buckets and of none
        val oldDirectories: Int,
    )

    internal fun run(): Result {
        var copies = 0
        var noRoom = 0
        locations.filter { it.objectsAtName }.forEach { location ->
            val old = BucketLayout.atName(location.name)
            val new = BucketLayout.atResourceId(location.resourceId)
            when {
                // a run before copied it and stopped before it marked the bucket
                storage.children(new).isNotEmpty() -> copied(location.name)
                // a bucket without objects has nothing to copy
                storage.children(old).isEmpty() -> copied(location.name)
                storage.usage(old).bytes + minFreeBytes > freeBytes() -> noRoom++
                else -> {
                    if (storage.copyTree(old, new)) copies++
                    copied(location.name)
                }
            }
        }
        return Result(copies, noRoom, storage.children(BlobPath(listOf(BucketLayout.OLD_DIRECTORY))).size)
    }
}
