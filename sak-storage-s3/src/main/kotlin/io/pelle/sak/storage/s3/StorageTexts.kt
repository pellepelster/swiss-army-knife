package io.pelle.sak.storage.s3

import io.pelle.sak.core.Catalog

// the texts of the S3 storage (backend.storage in i18n/texts.yml)
object StorageTexts : Catalog("storage") {
    val ENABLED_LABEL = text("settings.enabled.label")
    val ENABLED_DESCRIPTION = text("settings.enabled.description")
    val ENDPOINT_TYPE_LABEL = text("settings.endpoint_type.label")
    val ENDPOINT_TYPE_DESCRIPTION = text("settings.endpoint_type.description")
    val ENDPOINT_TYPE_HETZNER = text("settings.endpoint_type.hetzner")
    val REGION_LABEL = text("settings.region.label")
    val REGION_DESCRIPTION = text("settings.region.description")
    val REGION_FSN1 = text("settings.region.fsn1")
    val REGION_NBG1 = text("settings.region.nbg1")
    val REGION_HEL1 = text("settings.region.hel1")
    val BUCKET_LABEL = text("settings.bucket.label")
    val BUCKET_DESCRIPTION = text("settings.bucket.description")
    val ACCESS_KEY_LABEL = text("settings.access_key.label")
    val ACCESS_KEY_DESCRIPTION = text("settings.access_key.description")
    val SECRET_KEY_LABEL = text("settings.secret_key.label")
    val SECRET_KEY_DESCRIPTION = text("settings.secret_key.description")
    val SCOPE_LABEL = text("settings.label")
    val SCOPE_DESCRIPTION = text("settings.description")
    val BUCKET_MISSING = text("bucket_missing")
    val BUCKET_INVALID = text("bucket_invalid")
    val ACCESS_KEY_MISSING = text("access_key_missing")
    val SECRET_KEY_MISSING = text("secret_key_missing")
    val KEYS_REFUSED = text("keys_refused")
    val NO_BUCKET = text("no_bucket")
    val UNCHECKED = text("unchecked")
}
