package io.pelle.sak.backend.restic

import io.pelle.sak.settings.model.BooleanSetting
import io.pelle.sak.settings.model.Requirement
import io.pelle.sak.settings.model.SettingName
import io.pelle.sak.settings.model.SettingsGroup
import io.pelle.sak.settings.model.SettingScope
import io.pelle.sak.settings.SettingService
import io.pelle.sak.settings.StoredSettingsService
import io.pelle.sak.backend.storage.S3StorageManager

// the settings of the backups: where the repositories of restic are kept, on the data volume of the
// server or in the bucket of the S3 storage. One switch for all repositories, it moves no file
// (BACKUP-1)
class ResticSettingsService(
    settings: SettingService,
) : StoredSettingsService(SCOPE, settings) {
    // whether the repositories are kept in the S3 storage: turned on here and the S3 storage turned on
    fun s3(): Boolean = get(S3) == true

    companion object {
        // off until an admin turns it on: on the data volume. It needs the S3 storage of the deployment,
        // while that is off the repositories are on the data volume
        val S3 =
            BooleanSetting(
                SettingName("s3"),
                ResticTexts.S3_LABEL,
                ResticTexts.S3_DESCRIPTION,
                default = false,
                requires = Requirement(S3StorageManager.SCOPE, S3StorageManager.ENABLED),
            )

        val SCOPE = SettingsGroup(SettingScope("restic"), ResticTexts.SCOPE_LABEL, ResticTexts.SCOPE_DESCRIPTION, listOf(S3))
    }
}
