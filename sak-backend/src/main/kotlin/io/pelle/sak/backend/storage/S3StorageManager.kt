package io.pelle.sak.backend.storage

import io.pelle.sak.storage.s3.S3Bucket
import io.pelle.sak.storage.s3.S3Endpoint
import io.pelle.sak.storage.s3.S3EndpointType
import io.pelle.sak.storage.s3.S3Choice
import io.pelle.sak.storage.s3.S3KeyCheck
import io.pelle.sak.storage.s3.HeadBucketKeyCheck
import io.pelle.sak.storage.s3.S3KeyStatus
import io.pelle.sak.storage.s3.StorageTexts
import io.pelle.sak.api.ErrorMessage
import io.pelle.sak.settings.model.internal.BooleanSetting
import io.pelle.sak.settings.model.internal.ChoiceSetting
import io.pelle.sak.settings.model.internal.SettingChoice
import io.pelle.sak.settings.model.internal.SettingDefinition
import io.pelle.sak.settings.model.internal.SettingName
import io.pelle.sak.settings.model.internal.SettingsGroup
import io.pelle.sak.settings.model.internal.SettingScope
import io.pelle.sak.settings.ChangedValues
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.SettingsRegistration
import io.pelle.sak.settings.model.internal.TextSetting

// the S3 storage of the deployment, a bucket at a provider (Hetzner Object Storage): its settings
// and the one place the provider is asked and its client is made (EXT-1). The repositories of restic
// are kept in the bucket while the settings of the backups say so (ResticSettings)
class S3StorageManager(
    private val settings: SettingService,
    // the name of the deployment, every object in the bucket is below it
    private val deployment: String,
    // the endpoint of a type and a region; another one for a test
    private val endpoint: (S3EndpointType, String) -> S3Endpoint = S3EndpointType::endpoint,
    private val keyCheck: S3KeyCheck = HeadBucketKeyCheck(),
) {
    // its group, with the check of the bucket and the keys at the provider; registered by the backend
    // (SET-6)
    val registration = SettingsRegistration(GROUP, { check(it) })

    // made again when a setting changes
    @Volatile
    private var bucket: S3Bucket? = null

    // the bucket and its client, null while the storage is turned off
    fun bucket(): S3Bucket? {
        // null while turned off
        val name = settings.get(BUCKET)?.trim()?.ifEmpty { null } ?: return null
        val accessKey = settings.get(ACCESS_KEY)?.trim()?.ifEmpty { null } ?: return null
        val secretKey = settings.get(SECRET_KEY)?.trim()?.ifEmpty { null } ?: return null
        val endpoint = endpoint(S3EndpointType.of(settings.get(ENDPOINT_TYPE) ?: ENDPOINT_TYPE.default), settings.get(REGION) ?: REGION.default)
        bucket?.takeIf { it.isOf(endpoint, name, accessKey, secretKey) }?.let { return it }
        return S3Bucket(endpoint, name, deployment, accessKey, secretKey).also { bucket = it }
    }

    // turned on it needs a bucket and both keys. They are checked at the provider when it is turned
    // on and when one of them, the type or the region changes while it is on: nothing goes to the
    // provider before
    private fun check(values: ChangedValues): List<ErrorMessage> {
        val enabled = values[ENABLED]
        if (!enabled) return emptyList()
        val bucket = values[BUCKET].trim()
        val accessKey = values[ACCESS_KEY].trim()
        val secretKey = values[SECRET_KEY].trim()
        val missing =
            listOfNotNull(
                StorageTexts.BUCKET_MISSING.takeIf { bucket.isEmpty() },
                StorageTexts.ACCESS_KEY_MISSING.takeIf { accessKey.isEmpty() },
                StorageTexts.SECRET_KEY_MISSING.takeIf { secretKey.isEmpty() },
            )
        if (missing.isNotEmpty()) return missing.map { ErrorMessage(it) }
        val changed = !values.stored(ENABLED) || CHECKED.any { values.changed(it) }
        if (!changed) return emptyList()
        return when (keyCheck.check(endpoint(S3EndpointType.of(values[ENDPOINT_TYPE]), values[REGION]), bucket, accessKey, secretKey)) {
            S3KeyStatus.VALID -> emptyList()
            S3KeyStatus.KEYS_REFUSED -> listOf(ErrorMessage(StorageTexts.KEYS_REFUSED))
            S3KeyStatus.NO_BUCKET -> listOf(ErrorMessage(StorageTexts.NO_BUCKET))
            S3KeyStatus.UNKNOWN -> listOf(ErrorMessage(StorageTexts.UNCHECKED))
        }
    }

    // whether the change gives the setting another value
    private fun <T : Any> ChangedValues.changed(setting: SettingDefinition<T>) = new(setting)?.let { it != stored(setting) } == true

    // the name of a bucket; none is the default, the storage is not turned on with it
    private class BucketSetting :
        TextSetting(
            SCOPE,
            SettingName("bucket"),
            StorageTexts.BUCKET_LABEL,
            StorageTexts.BUCKET_DESCRIPTION,
            default = "",
            maxLength = 63,
        ) {
        override fun check(value: String) =
            super.check(value) ?: ErrorMessage(StorageTexts.BUCKET_INVALID).takeUnless { value.isEmpty() || BUCKET_NAME.matches(value) }
    }

    companion object {
        val SCOPE = SettingScope("storage")

        // the names S3 takes for a bucket, which need no encoding in the path of a request
        private val BUCKET_NAME = Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")

        // off until an admin turns it on, nothing is sent to the provider before
        val ENABLED =
            BooleanSetting(
                SCOPE,
                SettingName("enabled"),
                StorageTexts.ENABLED_LABEL,
                StorageTexts.ENABLED_DESCRIPTION,
                default = false,
            )

        val ENDPOINT_TYPE =
            ChoiceSetting(
                SCOPE,
                SettingName("endpoint_type"),
                StorageTexts.ENDPOINT_TYPE_LABEL,
                StorageTexts.ENDPOINT_TYPE_DESCRIPTION,
                S3EndpointType.entries.map { it.choice.setting() },
                default = S3EndpointType.HETZNER.value,
            )

        // the regions of Hetzner, the only type; a second type brings a choice of its own regions
        val REGION =
            ChoiceSetting(
                SCOPE,
                SettingName("region"),
                StorageTexts.REGION_LABEL,
                StorageTexts.REGION_DESCRIPTION,
                S3EndpointType.HETZNER.regions.map { it.setting() },
                default = "fsn1",
            )

        val BUCKET: TextSetting = BucketSetting()

        val ACCESS_KEY =
            TextSetting(
                SCOPE,
                SettingName("access_key"),
                StorageTexts.ACCESS_KEY_LABEL,
                StorageTexts.ACCESS_KEY_DESCRIPTION,
                default = "",
            )

        // the api never answers a character of it
        val SECRET_KEY =
            TextSetting(
                SCOPE,
                SettingName("secret_key"),
                StorageTexts.SECRET_KEY_LABEL,
                StorageTexts.SECRET_KEY_DESCRIPTION,
                default = "",
                mask = 0,
            )

        // a change of one of these is checked at the provider
        private val CHECKED: List<SettingDefinition<*>> = listOf(ENDPOINT_TYPE, REGION, BUCKET, ACCESS_KEY, SECRET_KEY)

        val GROUP =
            SettingsGroup(
                SCOPE,
                StorageTexts.SCOPE_LABEL,
                StorageTexts.SCOPE_DESCRIPTION,
                listOf(ENABLED, ENDPOINT_TYPE, REGION, BUCKET, ACCESS_KEY, SECRET_KEY),
                enabledBy = ENABLED,
            )
    }
}

// a choice of the S3 storage as a choice of the settings
private fun S3Choice.setting() = SettingChoice(value, label)
