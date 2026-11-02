package io.pelle.sak.grocery.ui

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import io.pelle.sak.grocery.Renaming
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.material3.IconToggleButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.grocery.AppUpdate
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListSettings
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.Screen
import io.pelle.sak.grocery.UNDO_MS
import io.pelle.sak.grocery.UiText
import io.pelle.sak.grocery.Undo
import io.pelle.sak.grocery.api.GroceryCategoryResponse
import io.pelle.sak.grocery.api.GroceryItemResponse
import io.pelle.sak.grocery.api.GroceryListResponse
import io.pelle.sak.grocery.scaledSize
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// the screens of the app, one at a time, as the model has it
@Composable
fun GroceryApp(model: GroceryModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalPhotoPicker provides rememberPhotoPicker(model)) {
        when (val current = screen) {
            Screen.Starting -> Busy()
            is Screen.Deployment -> DeploymentScreen(current, model::connect)
            is Screen.SignIn -> SignInScreen(current, model)
            is Screen.Lists -> ListsScreen(current, model)
        }
    }
}

// takes a photo with the camera or chooses one of the phone for an item; whether the phone has a camera
class PhotoPicker(
    val hasCamera: Boolean,
    val take: (itemId: Int) -> Unit,
    val choose: (itemId: Int) -> Unit,
)

private val LocalPhotoPicker = staticCompositionLocalOf<PhotoPicker> { error("no photo picker") }

// the camera and the photo picker of the app, at its root: always there, also while the app starts
// anew after android ended it while the camera was open, so their answer is never lost. The item
// they are for is kept with the saved state; the photo is read and sent by the model, which waits for
// the lists
@Composable
private fun rememberPhotoPicker(model: GroceryModel): PhotoPicker {
    val context = LocalContext.current
    var pendingItem by rememberSaveable { mutableStateOf<Int?>(null) }
    // the camera writes into a file of the cache, shared with it by the file provider only
    val cameraFile = remember { File(context.cacheDir, "photos/camera.jpg") }
    val cameraUri =
        remember {
            cameraFile.parentFile?.mkdirs()
            FileProvider.getUriForFile(context, "${context.packageName}.photos", cameraFile)
        }
    fun send(
        uri: Uri,
        done: () -> Unit = {},
    ) {
        val itemId = pendingItem ?: return
        pendingItem = null
        model.attachPhoto(itemId) {
            withContext(Dispatchers.IO) {
                try {
                    PhotoScaling.jpeg(context, uri)
                } catch (e: Exception) {
                    Log.w("sak-grocery", "the photo $uri could not be read", e)
                    null
                } finally {
                    done()
                }
            }
        }
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (taken) send(cameraUri) { cameraFile.delete() } else pendingItem = null
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) send(uri) else pendingItem = null
        }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    return remember(camera, picker) {
        PhotoPicker(
            hasCamera,
            take = { itemId ->
                pendingItem = itemId
                camera.launch(cameraUri)
            },
            choose = { itemId ->
                pendingItem = itemId
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        )
    }
}

@Composable
private fun Busy() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        // the bar a shade apart from the lists below it
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

@Composable
private fun Form(
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

@Composable
private fun Error(text: UiText?) {
    text?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
}

@Composable
private fun DeploymentScreen(
    screen: Screen.Deployment,
    connect: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(screen.input) }
    Page(stringResource(R.string.deployment_title)) { padding ->
        Form(padding) {
            Text(stringResource(R.string.deployment_hint))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.base_domain)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Error(screen.error)
            Button(onClick = { connect(input) }, enabled = input.isNotBlank() && !screen.busy) {
                Text(stringResource(if (screen.busy) R.string.connecting else R.string.connect))
            }
        }
    }
}

@Composable
private fun SignInScreen(
    screen: Screen.SignIn,
    model: GroceryModel,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Page(screen.deployment, actions = { TextButton(onClick = { model.changeDeployment() }) { Text(stringResource(R.string.other_deployment)) } }) { padding ->
        Form(padding) {
            Text(stringResource(R.string.login_sign_in), style = MaterialTheme.typography.headlineSmall)
            Error(screen.error)
            when {
                screen.linkSentTo != null ->
                    Text(
                        stringResource(R.string.link_sent, screen.linkSentTo),
                        modifier = Modifier.testTag("link-sent"),
                    )
                screen.multiUser == null -> Unit
                !screen.withPassword -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.shared_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { model.requestLink(email) }),
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
                        keyboardActions = KeyboardActions(onDone = { model.signInWithPassword(password) }),
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

// the home with all lists, or the list open
@Composable
private fun ListsScreen(
    screen: Screen.Lists,
    model: GroceryModel,
) {
    val open = screen.open
    val settings = screen.settings
    val choosing = screen.choosing
    when {
        settings != null -> ListSettingsScreen(settings, model)
        open == null -> HomeScreen(screen, model)
        choosing != null -> CategoryScreen(screen, choosing, model)
        else -> ListScreen(screen, open, model)
    }
}

// who is signed in and the sign-out, at the top right of every screen of the lists; on the home also
// the check for updates of the app
@Composable
private fun Menu(
    screen: Screen.Lists,
    model: GroceryModel,
    home: Boolean = false,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu")) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
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

// all lists of the user, the own and the shared ones of the others, each with how many items are to
// buy; a tap opens one, the plus at the top creates one
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    screen: Screen.Lists,
    model: GroceryModel,
) {
    Page(
        screen.deployment,
        actions = {
            IconButton(onClick = model::newList, modifier = Modifier.testTag("new-list")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.grocery_lists_add))
            }
            Menu(screen, model, home = true)
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = screen.loading,
            onRefresh = model::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("lists")) {
                screen.update?.let { item { UpdateBanner(it, model::update) } }
                screen.updateNotice?.let { item { UpdateNotice(it, model::dismissUpdateNotice) } }
                screen.error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                if (!screen.loading && screen.error == null && screen.lists.isEmpty()) {
                    item { Empty(stringResource(R.string.grocery_no_lists)) }
                }
                val last = screen.lists.count { it.mine } == 1
                itemsIndexed(screen.lists, key = { _, list -> list.id }) { index, list ->
                    ListRow(
                        list,
                        deletable = list.mine && !last,
                        open = { model.open(list.id) },
                        settings = { model.openSettings(list.id) },
                        delete = { model.deleteList(list.id) },
                    )
                    if (index < screen.lists.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

// the newer version of the app the deployment runs, at the top of the home; the update downloads it
// and hands it to the installer of android, which asks the user
@Composable
private fun UpdateBanner(
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
private fun UpdateNotice(
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

// a list on the home: its icon, its name, how many items are to buy and whether it is shared; the
// menu of its row leads to its settings and deletes it after a confirmation (UX-12, UX-4.1), both only
// of a list of the user's own and the delete not of their last one (UX-8, LIST-1)
@Composable
private fun ListRow(
    list: GroceryListResponse,
    deletable: Boolean,
    open: () -> Unit,
    settings: () -> Unit,
    delete: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    ListItem(
        leadingContent = { ListIcon(list.name, list.icon) },
        headlineContent = { Text(list.name) },
        supportingContent = {
            Column {
                Text(toBuy(list.items))
                if (list.shared) Text(stringResource(R.string.grocery_shared_badge), color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-${list.id}")) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_settings)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                        onClick = {
                            menu = false
                            settings()
                        },
                        enabled = list.mine,
                        modifier = Modifier.testTag("settings-${list.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_delete)) },
                        onClick = {
                            menu = false
                            confirming = true
                        },
                        enabled = deletable,
                        colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.testTag("delete-${list.id}"),
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = open).testTag("list-${list.id}"),
    )
    if (confirming) ConfirmDelete(list.name, onDelete = delete, onDismiss = { confirming = false })
}

// "Delete <name>?" before a delete (UX-4.1), Keep the safe default
@Composable
private fun ConfirmDelete(
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

// the mark of a list: its emoji, or the first letter of its name in a circle
@Composable
private fun ListIcon(
    name: String,
    icon: String?,
    size: Int = 40,
) {
    if (icon != null) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = (size * 0.7).sp)
        }
    } else {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// how large the photo of an item shows in its row, and the space around it
private val PHOTO_IN_ROW = 48.dp
private val PHOTO_SPACE = 8.dp

// No items to buy, 1 item to buy, 3 items to buy
@Composable
private fun toBuy(items: Int) =
    if (items == 0) stringResource(R.string.grocery_lists_to_buy_none) else pluralStringResource(R.plurals.grocery_to_buy, items, items)

// the settings of a new list or of one of the user's own: its name, whether all users see it and its
// icon, one emoji of the picker of the system; a list of the user's own is deleted after a
// confirmation (UX-4.1), not the last one (LIST-1). Back leads to the home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListSettingsScreen(
    settings: ListSettings,
    model: GroceryModel,
) {
    BackHandler(onBack = model::back)
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val canSave = settings.name.isNotBlank() && !settings.busy
    val edit = { name: String, shared: Boolean, icon: String? -> model.editSettings(name, shared, icon) }
    Page(
        stringResource(if (settings.id == null) R.string.grocery_new_list else R.string.shared_settings),
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.grocery_all_lists))
            }
        },
    ) { padding ->
        // an existing list loads its categories again when pulled down (ANDROID-3), a new one has none
        PullToRefreshBox(
            isRefreshing = settings.categoriesLoading,
            onRefresh = model::refreshCategories,
            modifier = Modifier.fillMaxSize(),
        ) { SettingsForm(padding, settings, model, edit, canSave, { picking = true }, { confirming = true }) }
    }
    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            AndroidView(
                factory = { context ->
                    EmojiPickerView(context).apply {
                        setOnEmojiPickedListener { emoji ->
                            edit(settings.name, settings.shared, emoji.emoji)
                            picking = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(400.dp),
            )
        }
    }
    if (confirming) ConfirmDelete(settings.name, onDelete = { model.deleteList() }, onDismiss = { confirming = false })
}

// the fields and buttons of the settings, the categories of an existing list below them
@Composable
private fun SettingsForm(
    padding: PaddingValues,
    settings: ListSettings,
    model: GroceryModel,
    edit: (String, Boolean, String?) -> Unit,
    canSave: Boolean,
    pick: () -> Unit,
    confirm: () -> Unit,
) {
    Form(padding) {
        OutlinedTextField(
            value = settings.name,
            onValueChange = { edit(it, settings.shared, settings.icon) },
            label = { Text(stringResource(R.string.shared_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) model.saveSettings() }),
            modifier = Modifier.fillMaxWidth().testTag("list-name"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ListIcon(settings.name, settings.icon, size = 48)
            TextButton(onClick = pick, modifier = Modifier.testTag("choose-icon")) { Text(stringResource(R.string.grocery_form_choose_icon)) }
            TextButton(onClick = { edit(settings.name, settings.shared, null) }, enabled = settings.icon != null) {
                Text(stringResource(R.string.grocery_form_no_icon))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = settings.shared, role = Role.Switch, onValueChange = { edit(settings.name, it, settings.icon) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.grocery_shared), modifier = Modifier.weight(1f))
            Switch(checked = settings.shared, onCheckedChange = null)
        }
        Error(settings.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.id != null) {
                // the button that leads to a delete (UX-3): filled like the other buttons, not
                // the color of the screen, the line and the text in the color of danger
                val enabled = !settings.last && !settings.busy
                OutlinedButton(
                    onClick = confirm,
                    enabled = enabled,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)),
                    modifier = Modifier.testTag("delete-list"),
                ) { Text(stringResource(R.string.grocery_form_delete_list)) }
            }
            Button(onClick = model::saveSettings, enabled = canSave, modifier = Modifier.testTag("save-list")) {
                Text(
                    stringResource(
                        when {
                            settings.busy -> R.string.grocery_saving
                            settings.id == null -> R.string.grocery_form_create
                            else -> R.string.shared_save
                        },
                    ),
                )
            }
        }
        if (settings.last) Text(stringResource(R.string.grocery_form_last_list), style = MaterialTheme.typography.bodySmall)
        if (settings.id != null) Categories(settings, model)
    }
}

// the categories of a list of the user's own in the order of the groups of its items (GROCERY-13),
// each change saved at once, one after the other: the icon, the name when its field is left or done,
// one place up or down (the ends disabled, UX-8), a delete from the menu of its row after a
// confirmation (UX-12); a name typed below adds one as the last. Their errors show here; categories
// that could not be loaded show no rows, but why and a way to load them again
@Composable
private fun Categories(
    settings: ListSettings,
    model: GroceryModel,
) {
    HorizontalDivider()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.grocery_categories), style = MaterialTheme.typography.titleMedium)
        if (settings.categoriesPending > 0) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
    Box(Modifier.testTag("categories-error")) { Error(settings.categoriesError) }
    if (!settings.categoriesLoaded) {
        if (settings.categoriesError != null && !settings.categoriesLoading) {
            OutlinedButton(onClick = model::refreshCategories, modifier = Modifier.testTag("categories-retry")) {
                Text(stringResource(R.string.grocery_categories_retry))
            }
        } else {
            CircularProgressIndicator()
        }
        return
    }
    if (settings.categories.isEmpty()) Text(stringResource(R.string.grocery_no_categories))
    settings.categories.forEachIndexed { index, category ->
        // the state of a row stays with its category when the rows move (GC-1)
        key(category.id) {
            CategoryRow(
                category,
                name = settings.drafts[category.id] ?: category.name,
                invalid = category.id in settings.invalidNames,
                first = index == 0,
                last = index == settings.categories.lastIndex,
                model = model,
            )
        }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = settings.newCategory,
            onValueChange = model::editNewCategory,
            label = { Text(stringResource(R.string.grocery_category_name)) },
            singleLine = true,
            isError = settings.newCategoryInvalid,
            supportingText = if (settings.newCategoryInvalid) ({ Text(stringResource(R.string.grocery_category_name_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { model.addCategory() }),
            modifier = Modifier.weight(1f).testTag("new-category"),
        )
        OutlinedButton(
            onClick = model::addCategory,
            enabled = settings.newCategory.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp).testTag("add-category"),
        ) { Text(stringResource(R.string.grocery_category_add)) }
    }
}

// a category in the settings: its icon, a tap chooses another; its name, kept by the model while it is
// typed, checked and saved when its field is left or done (UX-6); up, down and its menu with the
// delete. A change made while another one is sent waits for it
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryRow(
    category: GroceryCategoryResponse,
    name: String,
    invalid: Boolean,
    first: Boolean,
    last: Boolean,
    model: GroceryModel,
) {
    var focused by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("category-${category.id}")) {
        val iconLabel = stringResource(R.string.grocery_category_icon, category.name)
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = iconLabel) { picking = true }
                .semantics { contentDescription = iconLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(category.icon ?: "＋", fontSize = 28.sp, color = if (category.icon == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        }
        OutlinedTextField(
            value = name,
            onValueChange = { model.editCategoryName(category.id, it) },
            singleLine = true,
            isError = invalid,
            supportingText = if (invalid) ({ Text(stringResource(R.string.grocery_category_name_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { model.saveCategoryName(category.id) }),
            modifier =
                Modifier
                    .weight(1f)
                    .onFocusChanged {
                        if (focused && !it.isFocused) model.saveCategoryName(category.id)
                        focused = it.isFocused
                    }.testTag("category-name-${category.id}"),
        )
        val up = stringResource(R.string.grocery_category_up)
        val down = stringResource(R.string.grocery_category_down)
        IconButton(
            onClick = { model.moveCategory(category.id, up = true) },
            enabled = !first,
            modifier = Modifier.semantics { contentDescription = up }.testTag("category-up-${category.id}"),
        ) { Text("↑", style = MaterialTheme.typography.titleLarge) }
        IconButton(
            onClick = { model.moveCategory(category.id, up = false) },
            enabled = !last,
            modifier = Modifier.semantics { contentDescription = down }.testTag("category-down-${category.id}"),
        ) { Text("↓", style = MaterialTheme.typography.titleLarge) }
        Box {
            val menuLabel = stringResource(R.string.grocery_category_menu, category.name)
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.semantics { contentDescription = menuLabel }.testTag("menu-category-${category.id}"),
            ) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.grocery_form_choose_icon)) },
                    onClick = {
                        menu = false
                        picking = true
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.grocery_form_no_icon)) },
                    onClick = {
                        menu = false
                        model.setCategoryIcon(category.id, null)
                    },
                    enabled = category.icon != null,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.shared_delete)) },
                    onClick = {
                        menu = false
                        confirming = true
                    },
                    colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete-category-${category.id}"),
                )
            }
        }
    }
    if (picking) {
        ModalBottomSheet(onDismissRequest = { picking = false }) {
            AndroidView(
                factory = { context ->
                    EmojiPickerView(context).apply {
                        setOnEmojiPickedListener { emoji ->
                            model.setCategoryIcon(category.id, emoji.emoji)
                            picking = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(400.dp),
            )
        }
    }
    if (confirming) ConfirmDelete(category.name, onDelete = { model.deleteCategory(category.id) }, onDismiss = { confirming = false })
}

// the items to buy of one list, the field to add one at the top; back leads to the home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(
    screen: Screen.Lists,
    open: Int,
    model: GroceryModel,
) {
    BackHandler(onBack = model::back)
    screen.viewing?.let { PhotoDialog(it, model) }
    // the dialogs of the menu of an item live in the model, they stay when the row goes (GROCERY-14)
    screen.renaming?.let { RenameItem(it, model) }
    screen.deleting?.let { item -> ConfirmDelete(item.name, onDelete = { model.deleteItem(item) }, onDismiss = model::dismissDelete) }
    val list = screen.lists.firstOrNull { it.id == open }
    Page(
        list?.let { listOfNotNull(it.icon, it.name, stringResource(R.string.grocery_shared_badge).takeIf { _ -> it.shared }).joinToString(" · ") }.orEmpty(),
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.grocery_all_lists))
            }
        },
        actions = {
            // the bought items hidden (eye-slash) or shown (eye) too (GROCERY-14); a toggle, a screen
            // reader says whether it is on
            IconToggleButton(checked = screen.showBought, onCheckedChange = { model.toggleBought() }, modifier = Modifier.testTag("toggle-bought")) {
                Icon(
                    painterResource(if (screen.showBought) R.drawable.ic_eye else R.drawable.ic_eye_slash),
                    contentDescription = stringResource(R.string.grocery_show_bought),
                )
            }
            // the settings of the list, of a list of another user disabled in their place (UX-8)
            IconButton(
                onClick = { model.openSettings(open) },
                enabled = list?.mine == true,
                modifier = Modifier.testTag("list-settings"),
            ) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.shared_settings))
            }
            Menu(screen, model)
        },
        bottomBar = { screen.undo?.let { UndoBar(it, model::undo) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NewItem(screen, model)
            PullToRefreshBox(isRefreshing = screen.loading, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("items")) {
                    screen.error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                    // nothing to buy, also while the bought items show
                    val toBuy = screen.groups.any { group -> group.items.any { it.checkedAt == null } }
                    if (!screen.loading && screen.error == null && !toBuy) item { Empty(stringResource(R.string.grocery_nothing_to_buy)) }
                    screen.groups.forEach { group ->
                        item(key = "group-${group.categoryId ?: ""}") {
                            Text(
                                listOfNotNull(group.icon, group.label.resolve()).joinToString(" "),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(group.items, key = { _, item -> item.id }) { index, item ->
                            ItemRow(item, item.id in screen.struck, screen.photoBusy == item.id, model)
                            if (index < group.items.lastIndex) HorizontalDivider()
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// the categories of the list to choose the one of an item from, and no category; a tap sets it and
// leads straight back to the list, back too without a change (ANDROID-2)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryScreen(
    screen: Screen.Lists,
    item: GroceryItemResponse,
    model: GroceryModel,
) {
    BackHandler(onBack = model::back)
    Page(
        stringResource(R.string.grocery_category_of, item.name),
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                // back leads to the list
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.grocery_back_to_list))
            }
        },
    ) { padding ->
        // pulled down, the list loads again with its categories and the item (ANDROID-3)
        PullToRefreshBox(isRefreshing = screen.loading, onRefresh = model::refresh, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag("categories")) {
                items(screen.categories, key = { it.id }) { category ->
                    CategoryChoice(category.icon, category.name, item.categoryId == category.id, "choose-category-${category.id}") {
                        model.setCategory(category.id)
                    }
                    HorizontalDivider()
                }
                item(key = "none") {
                    CategoryChoice(null, stringResource(R.string.grocery_list_no_category), item.categoryId == null, "choose-no-category") {
                        model.setCategory(null)
                    }
                }
            }
        }
    }
}

// one category to choose, the one of the item marked
@Composable
private fun CategoryChoice(
    icon: String?,
    name: String,
    current: Boolean,
    tag: String,
    onChoose: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)) },
        leadingContent = { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon?.let { Text(it, fontSize = 28.sp) } } },
        trailingContent = if (current) ({ Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onChoose).testTag(tag),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
}

// the field at the top of a list, without a button: Enter on the keyboard adds the item and keeps the
// keyboard open for the next one (GROCERY-2)
@Composable
private fun NewItem(
    screen: Screen.Lists,
    model: GroceryModel,
) {
    OutlinedTextField(
        value = screen.itemName,
        onValueChange = model::typed,
        label = { Text(stringResource(R.string.grocery_list_item)) },
        singleLine = true,
        trailingIcon =
            if (screen.adding) {
                { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { model.addItem() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("item-name"),
    )
}

// the photo of the item, a tap shows it in full; the rest of the row checks the item, struck through
// while the check can be undone, no checkbox, the row tells a screen reader it is one (GROCERY-3);
// its menu attaches a photo, from the camera or the photos of the phone. A bought item (GROCERY-14)
// is struck through and a tap does nothing; its menu changes it as one to buy. The photo has the
// same space to its left, above and below it
@Composable
private fun ItemRow(
    item: GroceryItemResponse,
    struck: Boolean,
    busy: Boolean,
    model: GroceryModel,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bought = item.checkedAt != null
    val crossed = struck || bought
    // the items are the main content, larger than the text around them
    val text = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
    val style = if (crossed) text.copy(color = muted) else text
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (item.photoId != null) {
            val description = stringResource(R.string.grocery_photo_of, item.name)
            Photo(
                item,
                model,
                PHOTO_IN_ROW,
                ContentScale.Crop,
                Modifier
                    .padding(start = PHOTO_SPACE, top = PHOTO_SPACE, bottom = PHOTO_SPACE)
                    .size(PHOTO_IN_ROW)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = description) { model.viewPhoto(item) }
                    .semantics { contentDescription = description }
                    .testTag("photo-${item.id}"),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .toggleable(value = crossed, enabled = !bought, role = Role.Checkbox, onValueChange = { model.check(item) })
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("item-${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { StruckText(item.name, style, crossed) }
            quantity(item)?.let { StruckText(it, style.copy(color = muted), crossed) }
        }
        ItemMenu(item, busy, model)
    }
}

// a text, struck through with a red line through the middle of each of its lines, more visible than
// the line of the font (GROCERY-3); a name that wraps gets one line for each, right to left too
@Composable
private fun StruckText(
    text: String,
    style: TextStyle,
    struck: Boolean,
) {
    val color = MaterialTheme.colorScheme.error
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val line =
        if (!struck) {
            Modifier
        } else {
            Modifier.drawWithContent {
                drawContent()
                val lines = layout ?: return@drawWithContent
                for (index in 0 until lines.lineCount) {
                    val middle = (lines.getLineTop(index) + lines.getLineBottom(index)) / 2
                    drawLine(
                        color,
                        Offset(lines.getLineLeft(index), middle),
                        Offset(lines.getLineRight(index), middle),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
    Text(text, style = style, modifier = line, onTextLayout = { layout = it })
}

// the menu of an item, to buy or bought (GROCERY-14): its name in a dialog (RenameItem); its category, chosen on a screen of
// its own (CategoryScreen); a photo from the camera or the photos of the phone (PhotoPicker), scaled down on the phone before it
// is sent, or the photo removed; the camera only when the phone has one (UX-8). Its last entry deletes the item for good after
// a confirmation (UX-12, UX-4.1)
@Composable
private fun ItemMenu(
    item: GroceryItemResponse,
    busy: Boolean,
    model: GroceryModel,
) {
    val photos = LocalPhotoPicker.current
    var menu by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-item-${item.id}")) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.grocery_item_rename)) },
                onClick = {
                    menu = false
                    model.askRename(item)
                },
                modifier = Modifier.testTag("rename-item-${item.id}"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.grocery_item_category)) },
                onClick = {
                    menu = false
                    model.chooseCategory(item)
                },
                modifier = Modifier.testTag("category-item-${item.id}"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.grocery_photo_take)) },
                onClick = {
                    menu = false
                    photos.take(item.id)
                },
                enabled = photos.hasCamera && !busy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (item.photoId == null) R.string.grocery_photo_choose else R.string.grocery_photo_replace)) },
                onClick = {
                    menu = false
                    photos.choose(item.id)
                },
                enabled = !busy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.grocery_photo_remove)) },
                onClick = {
                    menu = false
                    model.removePhoto(item)
                },
                enabled = item.photoId != null && !busy,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shared_delete)) },
                onClick = {
                    menu = false
                    model.askDelete(item)
                },
                enabled = !busy,
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("delete-item-${item.id}"),
            )
        }
    }
}

// the name of an item in a dialog, e.g. to fix a spelling error; an empty or too long name shows its
// error under the field once the field was left or Done was pressed, and can not be saved (UX-6).
// Cancel is the safe default
@Composable
private fun RenameItem(
    renaming: Renaming,
    model: GroceryModel,
) {
    val valid = GroceryModel.validItemName(renaming.name)
    val error = renaming.left && !valid
    var focused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = model::dismissRename,
        title = { Text(stringResource(R.string.grocery_item_rename)) },
        text = {
            OutlinedTextField(
                value = renaming.name,
                onValueChange = model::editRename,
                label = { Text(stringResource(R.string.shared_name)) },
                singleLine = true,
                isError = error,
                supportingText = { if (error) Text(stringResource(R.string.grocery_item_name_invalid)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.renameItem() }),
                modifier =
                    Modifier
                        .onFocusChanged {
                            if (focused && !it.isFocused) model.leaveRename()
                            focused = it.isFocused
                        }.testTag("rename-item-name"),
            )
        },
        confirmButton = {
            TextButton(onClick = model::renameItem, enabled = valid, modifier = Modifier.testTag("rename-item-save")) {
                Text(stringResource(R.string.shared_save))
            }
        },
        dismissButton = { TextButton(onClick = model::dismissRename) { Text(stringResource(R.string.grocery_cancel)) } },
    )
}

// the photo of an item as large as it shows: fetched in the scaled size that fills that many pixels
// of the screen (scaledSize), the photo itself when none is that large
@Composable
private fun Photo(
    item: GroceryItemResponse,
    model: GroceryModel,
    showsAt: Dp,
    scale: ContentScale,
    modifier: Modifier = Modifier,
    full: Boolean = false,
) {
    val pixels = with(LocalDensity.current) { showsAt.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, item.photoId, pixels) {
        value =
            model.photo(item, scaledSize(pixels), full)?.let { bytes ->
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize()) }
    }
}

// the photo of an item in full over the list, as large as the screen; a tap or back closes it
@Composable
private fun PhotoDialog(
    item: GroceryItemResponse,
    model: GroceryModel,
) {
    val configuration = LocalConfiguration.current
    val screen = maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    Dialog(onDismissRequest = { model.viewPhoto(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(onClickLabel = stringResource(R.string.grocery_photo_close)) { model.viewPhoto(null) }
                .testTag("photo-viewer"),
            contentAlignment = Alignment.Center,
        ) {
            Photo(item, model, screen, ContentScale.Fit, Modifier.fillMaxSize().padding(16.dp), full = true)
        }
    }
}

// what was checked last and the undo, while the bar of the time left shrinks; anew for every check
@Composable
private fun UndoBar(
    undo: Undo,
    onUndo: () -> Unit,
) {
    val left = remember(undo.serial) { Animatable(1f) }
    LaunchedEffect(undo.serial) { left.animateTo(0f, tween(UNDO_MS.toInt(), easing = LinearEasing)) }
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp, modifier = Modifier.testTag("undo")) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(undo.text.resolve(), modifier = Modifier.weight(1f))
                TextButton(onClick = onUndo) { Text(stringResource(R.string.shared_undo)) }
            }
            LinearProgressIndicator(progress = { left.value }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
    }
}

// 1.5 l, 2; no trailing zeros
internal fun quantity(item: GroceryItemResponse): String? =
    item.amount?.let { amount ->
        val number = BigDecimal(amount.toString()).stripTrailingZeros().toPlainString()
        listOfNotNull(number, item.unit).joinToString(" ")
    }

// the text in the language of the phone
@Composable
private fun UiText.resolve(): String =
    when (this) {
        is UiText.Res -> stringResource(id, *args.toTypedArray())
        is UiText.Plain -> text
    }
