package io.pelle.sak.backend.storage

import io.pelle.sak.backend.settings.SettingChoice

// where the requests to an S3 storage go: the url of its api without a bucket, and the region the
// requests are signed for
data class S3Endpoint(
    val url: String,
    val region: String,
)

// the providers of an S3 storage the settings know, each with its regions; the one place another
// provider is added
enum class S3EndpointType(
    // the value of the setting
    val value: String,
    val choice: SettingChoice,
    val regions: List<SettingChoice>,
) {
    // Hetzner Object Storage, a host for each location
    HETZNER(
        "hetzner",
        SettingChoice("hetzner", StorageTexts.ENDPOINT_TYPE_HETZNER),
        listOf(
            SettingChoice("fsn1", StorageTexts.REGION_FSN1),
            SettingChoice("nbg1", StorageTexts.REGION_NBG1),
            SettingChoice("hel1", StorageTexts.REGION_HEL1),
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
