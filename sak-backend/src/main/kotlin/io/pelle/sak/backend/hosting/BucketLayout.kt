package io.pelle.sak.backend.hosting

import io.pelle.sak.model.ResourceId
import io.pelle.sak.model.s3.S3BucketLocation
import io.pelle.sak.storage.BlobPath

// where a bucket is in the storage of the buckets (s3DataDir): below its resource id (MODEL-8). What
// is below that path, the objects and their metadata, only sak-s3 knows
object BucketLayout {
    // the directory the releases before the resource ids kept the buckets in, each below its name
    const val OLD_DIRECTORY = "buckets"

    fun atResourceId(resourceId: ResourceId) = BlobPath(listOf(resourceId.value))

    // no resource id is the name of the old directory, so both layouts are next to each other
    fun atName(name: String) = BlobPath(listOf(OLD_DIRECTORY, name))

    // a bucket created before the resource ids stays below its name until its objects are copied
    // (BucketLayoutMigration), so it is served from there when a copy has to wait for room
    fun of(location: S3BucketLocation) = if (location.objectsAtName) atName(location.name) else atResourceId(location.resourceId)
}
