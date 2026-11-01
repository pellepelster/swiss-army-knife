package io.pelle.sak.grocery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import io.pelle.sak.grocery.UNDO_MS
import io.pelle.sak.grocery.Undo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.grocery.AppUpdate
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListSettings
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.Screen
import io.pelle.sak.grocery.api.GroceryItemResponse
import io.pelle.sak.grocery.api.GroceryListResponse
import java.math.BigDecimal

// the screens of the app, one at a time, as the model has it
@Composable
fun GroceryApp(model: GroceryModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    when (val current = screen) {
        Screen.Starting -> Busy()
        is Screen.Deployment -> DeploymentScreen(current, model::connect)
        is Screen.SignIn -> SignInScreen(current, model)
        is Screen.Lists -> ListsScreen(current, model)
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
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Error(text: String?) {
    text?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
}

@Composable
private fun DeploymentScreen(
    screen: Screen.Deployment,
    connect: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(screen.input) }
    Page("SAK Grocery List") { padding ->
        Form(padding) {
            Text("Enter the base domain of your SAK deployment, the one the web app is at as sak.<base domain>.")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Base domain") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Error(screen.error)
            Button(onClick = { connect(input) }, enabled = input.isNotBlank() && !screen.busy) {
                Text(if (screen.busy) "Connecting…" else "Connect")
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
    Page(screen.deployment, actions = { TextButton(onClick = { model.changeDeployment() }) { Text("Other deployment") } }) { padding ->
        Form(padding) {
            Text("Sign in", style = MaterialTheme.typography.headlineSmall)
            Error(screen.error)
            when {
                screen.linkSentTo != null ->
                    Text(
                        "A login link is on its way to ${screen.linkSentTo} if the address may sign in. Open it on this phone " +
                            "and tap \"Open in the app\".",
                        modifier = Modifier.testTag("link-sent"),
                    )
                screen.multiUser == null -> Unit
                !screen.withPassword -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-mail address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { model.requestLink(email) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { model.requestLink(email) }, enabled = email.isNotBlank() && !screen.busy) {
                        Text("Send login link")
                    }
                    TextButton(onClick = { model.usePassword(true) }, modifier = Modifier.testTag("use-password")) {
                        Text("Sign in with password")
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Admin password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { model.signInWithPassword(password) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { model.signInWithPassword(password) }, enabled = password.isNotEmpty() && !screen.busy) {
                        Text("Sign in with password")
                    }
                    if (screen.multiUser) {
                        TextButton(onClick = { model.usePassword(false) }, modifier = Modifier.testTag("use-email")) {
                            Text("Sign in with an e-mail link")
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
    when {
        settings != null -> ListSettingsScreen(settings, model)
        open == null -> HomeScreen(screen, model)
        else -> ListScreen(screen, open, model)
    }
}

// who is signed in and the sign-out, at the top right of every screen of the lists
@Composable
private fun Menu(
    screen: Screen.Lists,
    model: GroceryModel,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Signed in as ${screen.user}") }, onClick = {}, enabled = false)
        DropdownMenuItem(text = { Text("Sign out") }, onClick = {
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
                Icon(painterResource(R.drawable.ic_add), contentDescription = "Add list")
            }
            Menu(screen, model)
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = screen.loading,
            onRefresh = model::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("lists")) {
                screen.update?.let { item { UpdateBanner(it, model::update) } }
                screen.error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                if (!screen.loading && screen.error == null && screen.lists.isEmpty()) {
                    item { Empty("You have no list yet, add one with the plus at the top.") }
                }
                itemsIndexed(screen.lists, key = { _, list -> list.id }) { index, list ->
                    ListRow(list, open = { model.open(list.id) }, settings = { model.openSettings(list.id) })
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
            Text("Version ${update.version} of the app is available, the version of your deployment.")
            Error(update.error)
            Button(onClick = onUpdate, enabled = !update.busy) {
                Text(if (update.busy) "Downloading…" else "Update")
            }
        }
    }
}

// a list on the home: its icon, its name, how many items are to buy and whether it is shared; its
// gear leads to the settings, as on the list itself, only of a list of the user's own (UX-8)
@Composable
private fun ListRow(
    list: GroceryListResponse,
    open: () -> Unit,
    settings: () -> Unit,
) {
    ListItem(
        leadingContent = { ListIcon(list.name, list.icon) },
        headlineContent = { Text(list.name) },
        supportingContent = {
            Column {
                Text(toBuy(list.items))
                if (list.shared) Text("Shared with all users", color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            IconButton(onClick = settings, enabled = list.mine, modifier = Modifier.testTag("settings-${list.id}")) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings of ${list.name}")
            }
        },
        modifier = Modifier.clickable(onClick = open).testTag("list-${list.id}"),
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

// No items to buy, 1 item to buy, 3 items to buy
internal fun toBuy(items: Int) =
    when (items) {
        0 -> "No items to buy"
        1 -> "1 item to buy"
        else -> "$items items to buy"
    }

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
        if (settings.id == null) "New list" else "Settings",
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "All lists")
            }
        },
    ) { padding ->
        Form(padding) {
            OutlinedTextField(
                value = settings.name,
                onValueChange = { edit(it, settings.shared, settings.icon) },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) model.saveSettings() }),
                modifier = Modifier.fillMaxWidth().testTag("list-name"),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListIcon(settings.name, settings.icon, size = 48)
                TextButton(onClick = { picking = true }, modifier = Modifier.testTag("choose-icon")) { Text("Choose icon") }
                TextButton(onClick = { edit(settings.name, settings.shared, null) }, enabled = settings.icon != null) {
                    Text("No icon")
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = settings.shared, role = Role.Switch, onValueChange = { edit(settings.name, it, settings.icon) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shared with all users", modifier = Modifier.weight(1f))
                Switch(checked = settings.shared, onCheckedChange = null)
            }
            Error(settings.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settings.id != null) {
                    // the button that leads to a delete (UX-3): filled like the other buttons, not
                    // the color of the screen, the line and the text in the color of danger
                    val enabled = !settings.last && !settings.busy
                    OutlinedButton(
                        onClick = { confirming = true },
                        enabled = enabled,
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f)),
                        modifier = Modifier.testTag("delete-list"),
                    ) { Text("Delete list") }
                }
                Button(onClick = model::saveSettings, enabled = canSave, modifier = Modifier.testTag("save-list")) {
                    Text(
                        when {
                            settings.busy -> "Saving…"
                            settings.id == null -> "Create list"
                            else -> "Save"
                        },
                    )
                }
            }
            if (settings.last) Text("Your only list can not be deleted.", style = MaterialTheme.typography.bodySmall)
        }
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
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete the list?") },
            text = { Text("${settings.name} and its items go.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        model.deleteList()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            // the safe default
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } },
        )
    }
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
    val list = screen.lists.firstOrNull { it.id == open }
    Page(
        list?.let { listOfNotNull(it.icon, it.name).joinToString(" ") + if (it.shared) " · shared" else "" }.orEmpty(),
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "All lists")
            }
        },
        actions = {
            // the settings of the list, of a list of another user disabled in their place (UX-8)
            IconButton(
                onClick = { model.openSettings(open) },
                enabled = list?.mine == true,
                modifier = Modifier.testTag("list-settings"),
            ) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
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
                    if (!screen.loading && screen.error == null && screen.groups.isEmpty()) item { Empty("Nothing to buy.") }
                    screen.groups.forEach { group ->
                        item(key = "group-${group.label}") {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(group.items, key = { _, item -> item.id }) { index, item ->
                            ItemRow(item, item.id in screen.struck, model::check)
                            if (index < group.items.lastIndex) HorizontalDivider()
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
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
        label = { Text("Item") },
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

// a tap on the row or its box checks the item, struck through while the check can be undone
@Composable
private fun ItemRow(
    item: GroceryItemResponse,
    struck: Boolean,
    check: (GroceryItemResponse) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = if (struck) TextStyle(textDecoration = TextDecoration.LineThrough, color = muted) else TextStyle.Default
    ListItem(
        leadingContent = { Checkbox(checked = struck, onCheckedChange = null) },
        headlineContent = { Text(item.name, style = style) },
        trailingContent = quantity(item)?.let { { Text(it, style = style, color = muted) } },
        modifier =
            Modifier
                .toggleable(value = struck, role = Role.Checkbox, onValueChange = { check(item) })
                .testTag("item-${item.id}"),
    )
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
                Text(undo.text, modifier = Modifier.weight(1f))
                TextButton(onClick = onUndo) { Text("Undo") }
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
