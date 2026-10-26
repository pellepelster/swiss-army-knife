package io.pelle.sak.s3.api

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveStream
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.pelle.sak.s3.S3AccessCallback
import io.pelle.sak.s3.S3AccessResult
import io.pelle.sak.s3.S3Operation
import io.pelle.sak.s3.auth.AwsChunkedInputStream
import io.pelle.sak.s3.auth.PayloadHashMismatchException
import io.pelle.sak.s3.auth.Sha256VerifyingInputStream
import io.pelle.sak.s3.auth.SigV4Request
import io.pelle.sak.s3.auth.SigV4Result
import io.pelle.sak.s3.auth.SigV4Verifier
import io.pelle.sak.s3.storage.BinaryStorageS3Backend
import io.pelle.sak.s3.storage.InvalidKeyException
import io.pelle.sak.s3.storage.InvalidPartException
import io.pelle.sak.s3.storage.KeyConflictException
import io.pelle.sak.s3.storage.NoSuchBucketException
import io.pelle.sak.s3.storage.NoSuchUploadException
import io.pelle.sak.s3.storage.ObjectMetadata
import io.pelle.sak.s3.storage.PartRef
import io.pelle.sak.s3.storage.S3StorageBackend
import io.pelle.sak.s3.storage.StoredObjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Route.s3(
    storage: S3StorageBackend,
    accessCallback: S3AccessCallback,
    maxClockSkew: Duration = Duration.ofMinutes(15),
    proxiedPathPrefix: String? = null,
) {
    val api = S3Api(storage, accessCallback, SigV4Verifier(accessCallback, maxClockSkew), proxiedPathPrefix)

    route("/{bucket}/{key...}") {
        get { api.handle(call) { if (key.isEmpty()) listObjects() else getObject() } }
        head { api.handle(call) { if (key.isEmpty()) throw notImplemented() else headObject() } }
        put {
            api.handle(call) {
                when {
                    key.isEmpty() -> throw notImplemented()
                    query("partNumber") != null && query("uploadId") != null -> uploadPart()
                    else -> putObject()
                }
            }
        }
        post {
            api.handle(call) {
                when {
                    key.isEmpty() -> throw notImplemented()
                    query("uploads") != null -> createMultipartUpload()
                    query("uploadId") != null -> completeMultipartUpload()
                    else -> throw notImplemented()
                }
            }
        }
        delete {
            api.handle(call) {
                when {
                    key.isEmpty() -> throw notImplemented()
                    query("uploadId") != null -> abortMultipartUpload()
                    else -> deleteObject()
                }
            }
        }
    }
}

private fun notImplemented() = S3Exception(501, "NotImplemented", "operation is not supported")

private class S3Api(
    private val storage: S3StorageBackend,
    private val accessCallback: S3AccessCallback,
    private val verifier: SigV4Verifier,
    private val proxiedPathPrefix: String?,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun handle(
        call: ApplicationCall,
        block: suspend S3Call.() -> Unit,
    ) {
        try {
            val s3Call = authenticate(call) ?: return
            s3Call.block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: S3Exception) {
            error(call, e.status, e.code, e.message)
        } catch (e: NoSuchBucketException) {
            error(call, 404, "NoSuchBucket", e.message ?: "no such bucket")
        } catch (e: NoSuchUploadException) {
            error(call, 404, "NoSuchUpload", e.message ?: "no such upload")
        } catch (e: InvalidKeyException) {
            error(call, 400, "InvalidRequest", e.message ?: "invalid key")
        } catch (e: PayloadHashMismatchException) {
            error(call, 400, "XAmzContentSHA256Mismatch", e.message ?: "payload hash mismatch")
        } catch (e: KeyConflictException) {
            error(call, 400, "InvalidRequest", e.message ?: "key conflict")
        } catch (e: InvalidPartException) {
            error(call, 400, "InvalidPart", e.message ?: "invalid part")
        } catch (e: Exception) {
            logger.error(e) { "S3 request failed: ${call.request.httpMethod.value} ${call.request.uri}" }
            error(call, 500, "InternalError", "internal error")
        }
    }

    private suspend fun authenticate(call: ApplicationCall): S3Call? {
        val uri = call.request.uri
        val bucket = call.parameters["bucket"]!!
        val headers = call.request.headers

        val sigV4Request =
            SigV4Request(
                method = call.request.httpMethod.value,
                rawUri = signedUri(call, uri.substringBefore('?')),
                rawQuery = if ('?' in uri) uri.substringAfter('?') else null,
                headers = headers.entries().associate { (name, values) -> name.lowercase() to values },
                bucket = bucket,
            )

        return when (val result = verifier.verify(sigV4Request)) {
            is SigV4Result.Rejected -> {
                error(call, result.status, result.code, result.message)
                null
            }

            is SigV4Result.Authenticated ->
                S3Call(
                    call = call,
                    accessKeyId = result.accessKeyId,
                    bucket = bucket,
                    key = call.parameters.getAll("key").orEmpty().joinToString("/"),
                    awsChunked = headers["x-amz-content-sha256"] in SigV4Verifier.STREAMING_PAYLOAD_MODES,
                )
        }
    }

    private fun signedUri(
        call: ApplicationCall,
        path: String,
    ): String {
        val prefix = proxiedPathPrefix ?: return path
        if (call.request.headers[HttpHeaders.XForwardedHost] == null || !path.startsWith("$prefix/")) {
            return path
        }
        return path.removePrefix(prefix)
    }

    private suspend fun error(
        call: ApplicationCall,
        status: Int,
        code: String,
        message: String,
    ) {
        logger.warn { "${call.request.httpMethod.value} ${call.request.uri} failed: $status $code ($message)" }
        call.respondText(S3ErrorXml.body(code, message, call.request.path()), ContentType.Application.Xml, HttpStatusCode.fromValue(status))
    }

    inner class S3Call(
        val call: ApplicationCall,
        val accessKeyId: String,
        val bucket: String,
        val key: String,
        private val awsChunked: Boolean,
    ) {
        fun query(name: String): String? = call.request.queryParameters[name]

        suspend fun listObjects() {
            checkAccess(S3Operation.LIST_OBJECTS, null)

            val prefix = query("prefix")
            val delimiter = query("delimiter")
            val continuationToken = query("continuation-token")
            val startAfter = query("start-after")
            val maxKeys =
                query("max-keys")?.let { it.toIntOrNull() ?: throw S3Exception.invalidRequest("invalid max-keys '$it'") }

            val effectiveStartAfter =
                continuationToken?.let {
                    BinaryStorageS3Backend.decodeToken(it) ?: throw S3Exception.invalidRequest("invalid continuation token")
                } ?: startAfter
            val effectiveMaxKeys = (maxKeys ?: 1000).coerceIn(0, 1000)

            val listing =
                io {
                    storage.listObjects(
                        bucket = bucket,
                        prefix = prefix?.ifEmpty { null },
                        delimiter = delimiter?.ifEmpty { null },
                        startAfter = effectiveStartAfter,
                        maxKeys = effectiveMaxKeys,
                    )
                }

            respondXml(
                ListBucketResult(
                    name = bucket,
                    prefix = prefix ?: "",
                    delimiter = delimiter?.ifEmpty { null },
                    startAfter = startAfter,
                    continuationToken = continuationToken,
                    nextContinuationToken = listing.nextContinuationToken,
                    keyCount = listing.objects.size + listing.commonPrefixes.size,
                    maxKeys = effectiveMaxKeys,
                    isTruncated = listing.isTruncated,
                    contents =
                        listing.objects.map {
                            ListBucketResult.Contents(
                                key = it.key,
                                lastModified = S3Xml.timestamp(it.lastModified),
                                etag = S3Xml.quote(it.etag),
                                size = it.size,
                            )
                        },
                    commonPrefixes = listing.commonPrefixes.map { ListBucketResult.CommonPrefix(it) },
                ),
            )
        }

        suspend fun getObject() {
            checkAccess(S3Operation.GET_OBJECT, key)

            val stored = io { storage.getObject(bucket, key) } ?: throw S3Exception.noSuchKey(key)
            val size = stored.info.size
            val range = call.request.headers[HttpHeaders.Range]?.let { parseRange(it, size) }

            objectHeaders(stored.info)
            if (range == null) {
                call.respondOutputStream(contentType(stored.info), HttpStatusCode.OK, size) {
                    stored.open().use { it.copyTo(this) }
                }
            } else {
                call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/$size")
                call.respondOutputStream(contentType(stored.info), HttpStatusCode.PartialContent, range.last - range.first + 1) {
                    stored.open(range.first, range.last - range.first + 1).use { it.copyTo(this) }
                }
            }
        }

        suspend fun headObject() {
            checkAccess(S3Operation.HEAD_OBJECT, key)

            val info = io { storage.headObject(bucket, key) } ?: throw S3Exception.noSuchKey(key)
            objectHeaders(info)
            call.respond(
                object : OutgoingContent.NoContent() {
                    override val contentType = contentType(info)
                    override val contentLength = info.size
                },
            )
        }

        suspend fun putObject() {
            checkAccess(S3Operation.PUT_OBJECT, key)

            val content = body()
            val result = io { storage.putObject(bucket, key, content, metadata()) }
            call.response.header(HttpHeaders.ETag, S3Xml.quote(result.etag))
            call.respond(HttpStatusCode.OK)
        }

        suspend fun deleteObject() {
            checkAccess(S3Operation.DELETE_OBJECT, key)

            io { storage.deleteObject(bucket, key) }
            call.respond(HttpStatusCode.NoContent)
        }

        suspend fun createMultipartUpload() {
            checkAccess(S3Operation.CREATE_MULTIPART_UPLOAD, key)

            val uploadId = io { storage.createMultipartUpload(bucket, key, metadata()) }
            respondXml(InitiateMultipartUploadResult(bucket, key, uploadId))
        }

        suspend fun uploadPart() {
            checkAccess(S3Operation.UPLOAD_PART, key)

            val partNumber = query("partNumber")!!.toIntOrNull() ?: throw S3Exception.invalidRequest("invalid partNumber")
            val content = body()
            val etag = io { storage.uploadPart(bucket, key, query("uploadId")!!, partNumber, content) }
            call.response.header(HttpHeaders.ETag, S3Xml.quote(etag))
            call.respond(HttpStatusCode.OK)
        }

        suspend fun completeMultipartUpload() {
            checkAccess(S3Operation.COMPLETE_MULTIPART_UPLOAD, key)

            val content = body()
            val xml = io { content.use { it.readBytes().toString(Charsets.UTF_8) } }
            val parts =
                try {
                    S3Xml.fromXml(xml, CompleteMultipartUploadRequest::class.java).parts
                } catch (e: Exception) {
                    throw S3Exception.invalidRequest("malformed CompleteMultipartUpload body")
                }

            val result = io { storage.completeMultipartUpload(bucket, key, query("uploadId")!!, parts.map { PartRef(it.partNumber, it.etag) }) }

            respondXml(
                CompleteMultipartUploadResult(
                    location = "/$bucket/$key",
                    bucket = bucket,
                    key = key,
                    etag = S3Xml.quote(result.etag),
                ),
            )
        }

        suspend fun abortMultipartUpload() {
            checkAccess(S3Operation.ABORT_MULTIPART_UPLOAD, key)

            io { storage.abortMultipartUpload(bucket, key, query("uploadId")!!) }
            call.respond(HttpStatusCode.NoContent)
        }

        private suspend fun checkAccess(
            operation: S3Operation,
            key: String?,
        ) {
            when (io { accessCallback.checkAccess(accessKeyId, bucket, operation, key) }) {
                S3AccessResult.GRANTED -> {}
                S3AccessResult.DENIED -> throw S3Exception.accessDenied()
                S3AccessResult.NO_SUCH_BUCKET -> throw S3Exception.noSuchBucket(bucket)
            }
        }

        // the signature covers the declared payload hash, verifying the body against it makes
        // the signature cover the body; the chunk signatures of aws-chunked bodies and the
        // trailing checksums are not verified
        private suspend fun body(): InputStream {
            val stream = call.receiveStream()
            val declaredHash = call.request.headers["x-amz-content-sha256"] ?: SigV4Verifier.EMPTY_BODY_SHA256
            return when {
                awsChunked -> AwsChunkedInputStream(stream)
                declaredHash == SigV4Verifier.UNSIGNED_PAYLOAD -> stream
                else -> Sha256VerifyingInputStream(stream, declaredHash)
            }
        }

        private fun metadata(): ObjectMetadata {
            val headers = call.request.headers
            return ObjectMetadata(
                contentType = headers[HttpHeaders.ContentType],
                cacheControl = headers[HttpHeaders.CacheControl],
                contentEncoding = contentEncoding(),
                userMetadata =
                    headers
                        .names()
                        .map { it.lowercase() }
                        .filter { it.startsWith(USER_METADATA_PREFIX) }
                        .associate { it.removePrefix(USER_METADATA_PREFIX) to headers[it]!! },
            )
        }

        private fun contentEncoding(): String? {
            val value = call.request.headers[HttpHeaders.ContentEncoding] ?: return null
            if (!awsChunked) return value
            return value
                .split(',')
                .map { it.trim() }
                .filter { !it.equals("aws-chunked", ignoreCase = true) }
                .joinToString(", ")
                .ifEmpty { null }
        }

        private fun parseRange(
            header: String,
            size: Long,
        ): LongRange? {
            val spec = header.trim().removePrefix("bytes=").takeIf { it != header.trim() && !it.contains(',') } ?: return null
            val start = spec.substringBefore('-').trim()
            val end = spec.substringAfter('-', "").trim()

            val range =
                when {
                    start.isEmpty() -> end.toLongOrNull()?.takeIf { it > 0 }?.let { maxOf(0, size - it) until size }
                    else ->
                        start.toLongOrNull()?.let { first ->
                            val last = if (end.isEmpty()) size - 1 else end.toLongOrNull()?.let { minOf(it, size - 1) }
                            last?.let { first..it }
                        }
                } ?: return null

            if (range.isEmpty() || range.first >= size) {
                call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                throw S3Exception(416, "InvalidRange", "the requested range is not satisfiable")
            }
            return range
        }

        private fun objectHeaders(info: StoredObjectInfo) {
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.response.header(HttpHeaders.ETag, S3Xml.quote(info.etag))
            call.response.header(HttpHeaders.LastModified, HTTP_DATE_FORMAT.format(info.lastModified))
            info.metadata.cacheControl?.let { call.response.header(HttpHeaders.CacheControl, it) }
            info.metadata.contentEncoding?.let { call.response.header(HttpHeaders.ContentEncoding, it) }
            info.metadata.userMetadata.forEach { (name, value) -> call.response.header("$USER_METADATA_PREFIX$name", value) }
        }

        private fun contentType(info: StoredObjectInfo) =
            info.metadata.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                ?: ContentType.Application.OctetStream

        private suspend fun respondXml(value: Any) = call.respondText(S3Xml.toXml(value), ContentType.Application.Xml)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val USER_METADATA_PREFIX = "x-amz-meta-"

        private val HTTP_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
    }
}
