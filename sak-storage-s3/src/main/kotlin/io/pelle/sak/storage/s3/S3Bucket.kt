package io.pelle.sak.backend.storage

import com.github.davidmoten.aws.lw.client.Client
import com.github.davidmoten.aws.lw.client.Request
import java.util.concurrent.TimeUnit

// a bucket of an S3 storage with the client that reaches it (aws-lightweight-client-java), made by
// S3StorageManager only (EXT-1). Every object of a deployment is below the name of the deployment, so
// deployments that share a bucket keep apart. The keys are in the client and in no text of the bucket
class S3Bucket(
    val endpoint: S3Endpoint,
    val name: String,
    // the name of the deployment, the first segment of the key of every object
    val prefix: String,
    private val accessKey: String,
    private val secretKey: String,
) {
    // asks again after an answer that may be another one the next time, never after a 403
    val client: Client =
        Client
            .s3()
            .region(endpoint.region)
            .accessKey(accessKey)
            .secretKey(secretKey)
            .baseUrlFactory { _, _ -> endpoint.url.trimEnd('/') + "/" }
            .retryStatusCodes(429, 500, 502, 503)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    init {
        require(prefix.isNotEmpty() && '/' !in prefix) { "the prefix of the objects is one segment of a key" }
    }

    // the key of an object of the deployment: the segments below its prefix
    fun key(vararg segments: String): String = (listOf(prefix) + segments).joinToString("/")

    // a request of the object of the deployment at the segments
    fun request(vararg segments: String): Request = client.path(name, prefix, *segments)

    // a request of the bucket itself, e.g. a listing
    fun bucketRequest(): Request = client.path(name)

    fun isOf(
        endpoint: S3Endpoint,
        name: String,
        accessKey: String,
        secretKey: String,
    ) = this.endpoint == endpoint && this.name == name && this.accessKey == accessKey && this.secretKey == secretKey

    override fun toString() = "S3Bucket(${endpoint.url}/$name/$prefix)"
}
