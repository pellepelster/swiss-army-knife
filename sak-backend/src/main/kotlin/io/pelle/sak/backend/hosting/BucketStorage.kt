package io.pelle.sak.backend.hosting

import io.pelle.sak.backend.restic.RESTIC_PATH_PREFIX
import io.pelle.sak.config.SakConfig
import io.pelle.sak.s3.storage.LocalStorageS3Backend
import io.pelle.sak.s3.storage.S3BucketPathResolver
import io.pelle.sak.s3.storage.S3Limits
import io.pelle.sak.api.storage.LocalStorage
import io.pelle.sak.storage.FilesystemLocalStorage
import io.pelle.sak.storage.WriteGuard
import java.nio.file.Files

// where the buckets keep their objects and what the s3 api may take

// the path of the s3 api below the api host
const val S3_PATH_PREFIX = "/s3"

// the names no bucket may have: the REST backend of restic is below /restic of the api host (SERVER-7)
val RESERVED_BUCKETS = setOf(RESTIC_PATH_PREFIX.removePrefix("/"))

// the local storage of the buckets, s3DataDir
fun bucketBlobs(config: SakConfig): LocalStorage = FilesystemLocalStorage(config.s3DataDir)

// the buckets on their local storage: resolver tells where each bucket is in it (its resource id), what
// is below that only sak-s3 knows
fun bucketStorage(
    config: SakConfig,
    storage: LocalStorage,
    resolver: S3BucketPathResolver,
): LocalStorageS3Backend = LocalStorageS3Backend(storage, resolver, WriteGuard({ Files.getFileStore(config.s3DataDir).usableSpace }, config.minFreeBytes, S3Limits.MAX_OBJECT_BYTES))

// the most caddy lets through to the s3 api: the largest object or part, with room for the
// signatures of an aws-chunked body
const val S3_MAX_BODY_SIZE = S3Limits.MAX_OBJECT_BYTES + 64L * 1024 * 1024
