package io.pelle.sak.backend.hosting

import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.PutResult
import io.pelle.sak.s3.storage.S3StorageBackend
import java.io.InputStream

// the s3 backend that drops the statistics of a bucket on every write to it, the parts of an upload
// included: they count into its bytes until the upload is completed or aborted (HOSTING-3)
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

    override fun uploadPart(
        bucket: String,
        key: String,
        uploadId: String,
        partNumber: Int,
        content: InputStream,
    ): String =
        try {
            delegate.uploadPart(bucket, key, uploadId, partNumber, content)
        } finally {
            changed(bucket)
        }

    override fun abortMultipartUpload(
        bucket: String,
        key: String,
        uploadId: String,
    ) = try {
        delegate.abortMultipartUpload(bucket, key, uploadId)
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
