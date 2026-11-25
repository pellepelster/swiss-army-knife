package io.pelle.sak.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.pelle.sak.api.model.UserSettingResponse
import io.pelle.sak.core.R
import io.pelle.sak.core.settings.UserSettings
import io.pelle.sak.core.settings.UserSettingsDialog
import io.pelle.sak.core.settings.locked
import kotlinx.serialization.json.booleanOrNull

// the settings of the user signed in, over the screen of the app: for each scope its switches with
// what they do, saved when turned; one that does not count now is greyed out and says why
@Composable
fun UserSettingsDialog(
    dialog: UserSettingsDialog,
    settings: UserSettings,
) {
    AlertDialog(
        onDismissRequest = settings::close,
        confirmButton = {
            TextButton(onClick = settings::close, modifier = Modifier.testTag("user-settings-close")) { Text(stringResource(R.string.ok)) }
        },
        title = { Text(stringResource(R.string.shared_my_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("user-settings"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Error(dialog.error)
                val scopes = dialog.scopes
                if (scopes == null) {
                    if (dialog.error == null) CircularProgressIndicator()
                } else {
                    scopes.forEach { scope ->
                        Text(scope.label, style = MaterialTheme.typography.titleMedium)
                        scope.settings.filter { it.type == UserSettingResponse.Type.BOOLEAN }.forEach { setting ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(setting.label)
                                    Text(setting.description, style = MaterialTheme.typography.bodySmall)
                                    setting.unavailableReason?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("unavailable"))
                                    }
                                }
                                Switch(
                                    checked = setting.value.booleanOrNull == true,
                                    onCheckedChange = { settings.set(scope.name, setting.name, it) },
                                    enabled = !dialog.saving && !setting.locked,
                                    modifier = Modifier.testTag("user-setting-${scope.name}-${setting.name}"),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}
