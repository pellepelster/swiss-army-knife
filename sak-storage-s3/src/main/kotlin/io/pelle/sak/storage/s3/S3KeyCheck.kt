package io.pelle.sak.storage.s3

import com.github.davidmoten.aws.lw.client.Client
import com.github.davidmoten.aws.lw.client.HttpMethod
import java.time.Duration
import java.util.concurrent.TimeUnit

enum class S3KeyStatus {
    // the bucket is there and the keys may use it
    VALID,

    // the storage refuses the keys, or the keys may not use the bucket
    KEYS_REFUSED,

    // the region has no bucket of the name
    NO_BUCKET,

    // the storage did not answer, or not as expected
    UNKNOWN,
}

// tells whether a bucket of an S3 storage is there and the keys may use it
fun interface S3KeyCheck {
    fun check(
        endpoint: S3Endpoint,
        bucket: String,
        accessKey: String,
        secretKey: String,
    ): S3KeyStatus
}

// asks the storage for the bucket itself (HeadBucket, HEAD <url>/<bucket>): it sends the access key
// and the name of the bucket, and reads and changes nothing. The request is signed and sent by
// aws-lightweight-client-java; asked once, the client would ask again after a 403
class HeadBucketKeyCheck(
    private val timeout: Duration = Duration.ofSeconds(10),
) : S3KeyCheck {
    override fun check(
        endpoint: S3Endpoint,
        bucket: String,
        accessKey: String,
        secretKey: String,
    ): S3KeyStatus {
        val client =
            Client
                .s3()
                .region(endpoint.region)
                .accessKey(accessKey)
                .secretKey(secretKey)
                .baseUrlFactory { _, _ -> endpoint.url.trimEnd('/') + "/" }
                .retryMaxAttempts(1)
                .connectTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .build()
        val status =
            try {
                client.path(bucket).method(HttpMethod.HEAD).response().statusCode()
            } catch (_: RuntimeException) {
                // no answer: the client throws what the connection threw, unchecked
                return S3KeyStatus.UNKNOWN
            }
        return when (status) {
            200 -> S3KeyStatus.VALID
            401, 403 -> S3KeyStatus.KEYS_REFUSED
            // a redirect: the bucket is in another region
            301, 307, 404 -> S3KeyStatus.NO_BUCKET
            else -> S3KeyStatus.UNKNOWN
        }
    }
}
