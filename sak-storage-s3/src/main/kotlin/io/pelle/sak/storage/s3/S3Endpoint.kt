package io.pelle.sak.storage.s3

import io.pelle.sak.core.TextKey

// where the requests to an S3 storage go: the url of its api without a bucket, and the region the
// requests are signed for
data class S3Endpoint(
    val url: String,
    val region: String,
)

// a value to choose, e.g. a provider or a region, and its label
data class S3Choice(
    val value: String,
    val label: TextKey,
)

// the providers of an S3 storage the settings know, each with its regions; the one place another
// provider is added
enum class S3EndpointType(
    // the value of the setting
    val value: String,
    val choice: S3Choice,
    val regions: List<S3Choice>,
) {
    // Hetzner Object Storage, a host for each location
    HETZNER(
        "hetzner",
        S3Choice("hetzner", StorageTexts.ENDPOINT_TYPE_HETZNER),
        listOf(
            S3Choice("fsn1", StorageTexts.REGION_FSN1),
            S3Choice("nbg1", StorageTexts.REGION_NBG1),
            S3Choice("hel1", StorageTexts.REGION_HEL1),
        ),
    ) {
        override fun endpoint(region: String) = S3Endpoint("https://$region.your-objectstorage.com", region)
    }, ;

    // the endpoint of one of the regions of the type
    abstract fun endpoint(region: String): S3Endpoint

    companion object {
        fun of(value: String) = entries.single { it.value == value }
    }
}
