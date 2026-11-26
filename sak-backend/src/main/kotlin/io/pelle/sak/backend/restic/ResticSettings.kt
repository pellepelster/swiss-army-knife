package io.pelle.sak.backend.restic

import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.SettingService
import io.pelle.sak.backend.storage.S3StorageManager

// the settings of the backups: where the repositories of restic are kept, on the data volume of the
// server or in the bucket of the S3 storage. One switch for all repositories, it moves no file
// (BACKUP-1)
class ResticSettings(
    private val settings: SettingService,
) {
    init {
        settings.register(GROUP)
    }

    // whether the repositories are kept in the S3 storage: turned on here and the S3 storage turned on
    fun s3(): Boolean = settings.on(S3)

    companion object {
        val SCOPE = SettingScope("restic")

        // off until an admin turns it on: on the data volume. It needs the S3 storage of the deployment,
        // while that is off the repositories are on the data volume
        val S3 =
            BooleanSetting(
                SCOPE,
                SettingName("s3"),
                ResticTexts.S3_LABEL,
                ResticTexts.S3_DESCRIPTION,
                default = false,
                requires = S3StorageManager.ENABLED,
            )

        val GROUP = SettingsGroup(SCOPE, ResticTexts.SCOPE_LABEL, ResticTexts.SCOPE_DESCRIPTION, listOf(S3))
    }
}
