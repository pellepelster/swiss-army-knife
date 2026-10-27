package io.pelle.sak.backend.hosting

import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.PutResult
import io.pelle.sak.s3.storage.S3StorageBackend
import java.io.InputStream

// the s3 backend that drops the statistics of a bucket on every write to it
class InvalidatingS3StorageBackend(
    private val delegate: S3StorageBackend,
    private val changed: (bucket: String) -> Unit,
) : S3StorageBackend by delegate {
    override fun putObject(
        bucket: String,
        key: String,
        content: InputStream,
        metadata: ObjectMetadata,
    ): PutResult =
        try {
            delegate.putObject(bucket, key, content, metadata)
        } finally {
            changed(bucket)
        }

    override fun deleteObject(
        bucket: String,
        key: String,
    ) = try {
        delegate.deleteObject(bucket, key)
    } finally {
        changed(bucket)
    }

    override fun completeMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
        parts: List<PartRef>,
    ): PutResult =
        try {
            delegate.completeMultipartUpload(bucket, key, uploadId, parts)
        } finally {
            changed(bucket)
        }
}
