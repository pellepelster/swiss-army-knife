package io.pelle.sak.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.R
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.Screen
import io.pelle.sak.core.UiText
import io.pelle.sak.core.update.AppUpdate

// the text in the language of the phone
@Composable
fun UiText.resolve(): String =
    when (this) {
        is UiText.Res -> androidx.compose.ui.res.stringResource(id, *args.toTypedArray())
        is UiText.Plain -> text
    }

@Composable
fun Busy() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// a screen with its bar at the top: its title, the navigation at the left, the actions at the right
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // the bar a shade apart from the content below it
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = navigationIcon,
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

// the fields of a form, one under the other, scrolled when they do not fit
@Composable
fun Form(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

// an error, read out by talkback when it shows (a live region, AN-34)
@Composable
fun Error(text: UiText?) {
    text?.let {
        Text(
            it.resolve(),
            color = MaterialTheme.colorScheme.error,
            modifier =
                Modifier
                    .testTag("error")
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

// the deployment, its base domain typed in; title is the name of the app
@Composable
fun DeploymentScreen(
    title: String,
    screen: Screen.Deployment,
    connect: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(screen.input) }
    Page(title) { padding ->
        Form(padding) {
            Text(stringResource(R.string.deployment_hint))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.base_domain)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!screen.busy) connect(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Error(screen.error)
            Button(onClick = { connect(input) }, enabled = input.isNotBlank() && !screen.busy) {
                Text(stringResource(if (screen.busy) R.string.connecting else R.string.connect))
            }
        }
    }
}

// the sign-in by a link by e-mail or by the password, as the user mode of the deployment has it (UX-7)
@Composable
fun SignInScreen(
    screen: Screen.SignIn,
    model: SakModel<*>,
) {
    var email by rememberSaveable { mutableStateOf("") }
    // not in the saved state of the activity, which android writes to the disk (AN-9)
    var password by remember { mutableStateOf("") }
    Page(screen.deployment, actions = { TextButton(onClick = { model.changeDeployment() }) { Text(stringResource(R.string.other_deployment)) } }) { padding ->
        Form(padding) {
            Text(stringResource(R.string.login_sign_in), style = MaterialTheme.typography.headlineSmall)
            Error(screen.error)
            when {
                screen.linkSentTo != null -> {
                    Text(
                        stringResource(R.string.link_sent, screen.linkSentTo),
                        modifier = Modifier.testTag("link-sent"),
                    )
                    // the same address again, or another one (AN-42)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.requestLink(screen.linkSentTo) }, enabled = !screen.busy, modifier = Modifier.testTag("send-again")) {
                            Text(stringResource(R.string.send_again))
                        }
                        TextButton(onClick = { model.otherAddress() }, enabled = !screen.busy, modifier = Modifier.testTag("other-address")) {
                            Text(stringResource(R.string.other_address))
                        }
                    }
                }
                // the deployment did not answer: asked again (AN-42)
                screen.multiUser == null ->
                    if (!screen.busy) {
                        Button(onClick = { model.retrySignIn() }, modifier = Modifier.testTag("retry")) { Text(stringResource(R.string.retry)) }
                    }
                !screen.withPassword -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.shared_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!screen.busy) model.requestLink(email) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { model.requestLink(email) }, enabled = email.isNotBlank() && !screen.busy) {
                        Text(stringResource(R.string.login_send_link))
                    }
                    TextButton(onClick = { model.usePassword(true) }, modifier = Modifier.testTag("use-password")) {
                        Text(stringResource(R.string.login_use_password))
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.login_admin_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!screen.busy) model.signInWithPassword(password) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { model.signInWithPassword(password) }, enabled = password.isNotEmpty() && !screen.busy) {
                        Text(stringResource(R.string.login_use_password))
                    }
                    if (screen.multiUser) {
                        TextButton(onClick = { model.usePassword(false) }, modifier = Modifier.testTag("use-email")) {
                            Text(stringResource(R.string.login_use_email))
                        }
                    }
                }
            }
            if (screen.busy) CircularProgressIndicator()
        }
    }
}

// who is signed in and the sign-out, at the top right of every screen of an app; on its home also the
// check for updates of the app (UX-13)
@Composable
fun AccountMenu(
    screen: AppScreen,
    model: SakModel<*>,
    home: Boolean = false,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) {
        Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.menu))
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.signed_in_as, screen.user)) }, onClick = {}, enabled = false)
        if (home) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update_check)) },
                onClick = {
                    menu = false
                    model.checkUpdate()
                },
                // an update offered already is in the banner at the top
                enabled = screen.update == null,
                modifier = Modifier.testTag("check-update"),
            )
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.login_sign_out)) }, onClick = {
            menu = false
            model.signOut()
        })
    }
}

// the newer version of the app the deployment runs, at the top of the home; the update downloads it
// and hands it to the installer of android, which asks the user
@Composable
fun UpdateBanner(
    update: AppUpdate,
    onUpdate: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("update")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_available, update.version))
            Error(update.error)
            Button(onClick = onUpdate, enabled = !update.busy) {
                Text(stringResource(if (update.busy) R.string.downloading else R.string.update))
            }
        }
    }
}

// the answer of the check for updates when there is no update, e.g. that the app is up-to-date
@Composable
fun UpdateNotice(
    notice: UiText,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("update-notice")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice.resolve(), modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        }
    }
}

// "Delete <name>?" before a delete (UX-4.1), Keep the safe default
@Composable
fun ConfirmDelete(
    name: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shared_confirm_delete, name)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onDelete()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.shared_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.shared_keep)) } },
    )
}
