package io.pelle.sak.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
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

// a screen with its bar at the top: its title, the back arrow at the left, the actions at the right.
// With onBack the arrow shows (back its text for a screen reader, backTag its test tag), and the back of
// the system does the same (ANDROID-2)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)? = null,
    back: String? = null,
    backTag: String = "back",
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    onBack?.let { BackHandler(onBack = it) }
    Scaffold(
        // the bar a shade apart from the content below it
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    onBack?.let {
                        IconButton(onClick = it, modifier = Modifier.testTag(backTag)) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = back)
                        }
                    }
                },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

// an entry of a menu: a tap closes the menu (close), then acts
@Composable
fun MenuEntry(
    text: String,
    close: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = {
            close()
            onClick()
        },
        modifier = modifier,
        leadingIcon = leadingIcon,
        enabled = enabled,
        colors = colors,
    )
}

// the Delete of a menu, in the color of an error while it is enabled (UX-3)
@Composable
fun DeleteMenuEntry(
    close: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = stringResource(R.string.shared_delete),
    onClick: () -> Unit,
) {
    MenuEntry(text, close, modifier, enabled, colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error), onClick = onClick)
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

// an error, read out by talkback when it shows (a live region)
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

// the scan of the QR code of the web app, the first way to connect the app (SECURITY-3); the typed
// address or the sign-in below it stays
@Composable
private fun ScanQr(scan: () -> Unit) {
    SignInWay(stringResource(R.string.qr_hint), stringResource(R.string.qr_scan), scan, Modifier.testTag("scan-qr"), icon = R.drawable.ic_qrcode)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
}

// one way to sign in: what it is for, and its button; disabled with why below it (UX-8)
@Composable
private fun SignInWay(
    hint: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    disabledWhy: String? = null,
) {
    Text(hint)
    Button(onClick = onClick, enabled = disabledWhy == null, modifier = modifier) {
        icon?.let {
            Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(label)
    }
    disabledWhy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

// the deployment: the QR code of the web app scanned, or its SAK address typed in; title is the name
// of the app
@Composable
fun DeploymentScreen(
    title: String,
    screen: Screen.Deployment,
    connect: (String) -> Unit,
    scan: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(screen.input) }
    Page(title) { padding ->
        Form(padding) {
            ScanQr(scan)
            Text(stringResource(R.string.qr_or_address))
            Text(stringResource(R.string.deployment_hint))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.sak_address)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!screen.busy) connect(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Error(screen.error)
            OutlinedButton(onClick = { connect(input) }, enabled = input.isNotBlank() && !screen.busy, modifier = Modifier.testTag("connect")) {
                Text(stringResource(if (screen.busy) R.string.shared_connecting else R.string.shared_connect))
            }
        }
    }
}

// the sign-in: first the three ways, the QR code of the web app, the password of a single user
// deployment and a link by e-mail of a multi user one, each with what it is for; a way chosen shows its
// form, back leads to the three again (UX-7)
@Composable
fun SignInScreen(
    screen: Screen.SignIn,
    model: SakModel<*>,
) {
    var email by rememberSaveable { mutableStateOf("") }
    // not in the saved state of the activity, which android writes to the disk
    var password by remember { mutableStateOf("") }
    BackHandler(enabled = screen.method != null && screen.linkSentTo == null && !screen.busy) { model.chooseMethod(null) }
    Page(screen.address, actions = { TextButton(onClick = { model.changeDeployment() }) { Text(stringResource(R.string.other_deployment)) } }) { padding ->
        Form(padding) {
            Text(stringResource(R.string.login_sign_in), style = MaterialTheme.typography.headlineSmall)
            Error(screen.error)
            when {
                screen.linkSentTo != null -> {
                    Text(
                        stringResource(R.string.link_sent, screen.linkSentTo),
                        modifier = Modifier.testTag("link-sent"),
                    )
                    // the same address again, or another one
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.requestLink(screen.linkSentTo) }, enabled = !screen.busy, modifier = Modifier.testTag("send-again")) {
                            Text(stringResource(R.string.send_again))
                        }
                        TextButton(onClick = { model.otherAddress() }, enabled = !screen.busy, modifier = Modifier.testTag("other-address")) {
                            Text(stringResource(R.string.other_address))
                        }
                    }
                }
                // the deployment did not answer: the QR code still works, the rest is asked again
                screen.multiUser == null -> {
                    ScanQr(model.qr::open)
                    if (!screen.busy) {
                        Button(onClick = { model.retrySignIn() }, modifier = Modifier.testTag("retry")) { Text(stringResource(R.string.retry)) }
                    }
                }
                screen.method == null -> {
                    ScanQr(model.qr::open)
                    SignInWay(
                        stringResource(R.string.sign_in_password_hint),
                        stringResource(R.string.login_use_password),
                        { model.chooseMethod(Screen.SignInMethod.PASSWORD) },
                        Modifier.testTag("use-password"),
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SignInWay(
                        stringResource(R.string.sign_in_email_hint),
                        stringResource(R.string.sign_in_email),
                        { model.chooseMethod(Screen.SignInMethod.EMAIL) },
                        Modifier.testTag("use-email"),
                        disabledWhy = if (screen.multiUser) null else stringResource(R.string.sign_in_email_single),
                    )
                }
                screen.method == Screen.SignInMethod.EMAIL -> {
                    Text(stringResource(R.string.sign_in_email_hint))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.shared_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!screen.busy) model.requestLink(email) }),
                        modifier = Modifier.fillMaxWidth().testTag("email"),
                    )
                    Button(onClick = { model.requestLink(email) }, enabled = email.isNotBlank() && !screen.busy, modifier = Modifier.testTag("send-link")) {
                        Text(stringResource(R.string.login_send_link))
                    }
                    OtherWays(screen, model)
                }
                else -> {
                    Text(stringResource(R.string.sign_in_password_hint))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.login_admin_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!screen.busy) model.signInWithPassword(password) }),
                        modifier = Modifier.fillMaxWidth().testTag("password"),
                    )
                    Button(onClick = { model.signInWithPassword(password) }, enabled = password.isNotEmpty() && !screen.busy, modifier = Modifier.testTag("sign-in-password")) {
                        Text(stringResource(R.string.login_use_password))
                    }
                    OtherWays(screen, model)
                }
            }
            if (screen.busy) CircularProgressIndicator()
        }
    }
}

// back to the three ways to sign in
@Composable
private fun OtherWays(
    screen: Screen.SignIn,
    model: SakModel<*>,
) {
    TextButton(onClick = { model.chooseMethod(null) }, enabled = !screen.busy, modifier = Modifier.testTag("other-ways")) {
        Text(stringResource(R.string.sign_in_other_ways))
    }
}

// who is signed in and the sign-out, at the top right of every screen of an app; on its home also the
// check for updates of the app when it updates itself (UX-13)
@Composable
fun AccountMenu(
    screen: AppScreen,
    model: SakModel<*>,
    home: Boolean = false,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) {
        Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.shared_menu))
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.signed_in_as, screen.user)) }, onClick = {}, enabled = false)
        if (home && model.updates.enabled) {
            MenuEntry(
                stringResource(R.string.update_check),
                close = { menu = false },
                // an update offered already is in the banner at the top
                enabled = screen.update == null,
                modifier = Modifier.testTag("check-update"),
            ) { model.updates.check() }
        }
        MenuEntry(
            stringResource(R.string.shared_my_settings),
            close = { menu = false },
            modifier = Modifier.testTag("menu-user-settings"),
        ) { model.userSettings.open() }
        // another user or deployment by the QR code of the web app; it asks before the session goes
        MenuEntry(
            stringResource(R.string.qr_scan),
            close = { menu = false },
            modifier = Modifier.testTag("menu-scan-qr"),
        ) { model.qr.open() }
        MenuEntry(stringResource(R.string.login_sign_out), close = { menu = false }) { model.signOut() }
    }
}

// the newer version of the app the deployment runs, at the top of the home; the update downloads it
// and hands it to the installer of android, which asks the user. Later hides it until the next check
// (UX-13)
@Composable
fun UpdateBanner(
    update: AppUpdate,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("update")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_available, update.version))
            Error(update.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUpdate, enabled = !update.busy) {
                    Text(stringResource(if (update.busy) R.string.downloading else R.string.update))
                }
                TextButton(onClick = onLater, enabled = !update.busy, modifier = Modifier.testTag("update-later")) {
                    Text(stringResource(R.string.update_later))
                }
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
