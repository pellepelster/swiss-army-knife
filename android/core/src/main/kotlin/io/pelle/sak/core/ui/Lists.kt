package io.pelle.sak.core.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import io.pelle.sak.core.AppScreen
import io.pelle.sak.core.R
import io.pelle.sak.core.SakModel
import io.pelle.sak.core.UiText
import io.pelle.sak.core.lists.ListForm
import io.pelle.sak.core.lists.ListSummary
import io.pelle.sak.core.lists.ListsActions
import io.pelle.sak.core.Undo

// the screens of the list-based apps (the home, the settings of a list) and the undo of every app

// the texts of the app the screens of its lists show, by their resource (<app>.common)
data class ListTexts(
    // the name of the app, the title of the home
    @StringRes val appName: Int,
    // the plus of the home and the title of the settings of a new list
    @StringRes val create: Int,
    // the back arrow of a list, to the home
    @StringRes val allLists: Int,
    @StringRes val chooseIcon: Int,
    @StringRes val noIcon: Int,
    @StringRes val deleteList: Int,
)

@Composable
fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
}

// the ⋮ of the menu of a row, which tells a screen reader whose menu it is
@Composable
fun MenuIcon(name: String) {
    Icon(painterResource(R.drawable.ic_menu), contentDescription = stringResource(R.string.menu_of, name))
}

// the mark of a list: its emoji, or the first letter of its name in a circle
@Composable
fun ListIcon(
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

// the title of a list open: its icon, its name and whether it is shared
@Composable
fun listTitle(list: ListSummary?): String =
    list?.let { listOfNotNull(it.icon, it.name, stringResource(R.string.shared_badge).takeIf { _ -> it.shared }).joinToString(" · ") }.orEmpty()

// how long an undo is offered: millis, longer when the accessibility settings of the phone ask for more
// time for a control; apply hands it to the model
@Composable
fun UndoTime(
    millis: Long,
    apply: (Long) -> Unit,
) {
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(accessibility, millis) {
        apply(accessibility?.calculateRecommendedTimeoutMillis(millis, containsIcons = false, containsText = true, containsControls = true) ?: millis)
    }
}

// what was done last and its undo, while the bar of the time left shrinks, anew for every undo; a
// screen reader says it once it shows (UX-4.2). millis is the time of the model (UndoTime)
@Composable
fun UndoBar(
    undo: Undo,
    millis: Long,
    onUndo: () -> Unit,
) {
    val left = remember(undo.serial) { Animatable(1f) }
    LaunchedEffect(undo.serial) { left.animateTo(0f, tween(millis.toInt(), easing = LinearEasing)) }
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("undo"),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(undo.text.resolve(), modifier = Modifier.weight(1f))
                TextButton(onClick = onUndo, modifier = Modifier.testTag("undo-action")) { Text(stringResource(R.string.shared_undo)) }
            }
            LinearProgressIndicator(progress = { left.value }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
    }
}

// the emoji picker of the system in a sheet from the bottom, opened in full; at most 400 dp high and
// never higher than most of the screen, so it fits in landscape too (it scrolls itself)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiSheet(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val screen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AndroidView(
            factory = { context -> EmojiPickerView(context).apply { setOnEmojiPickedListener { onPick(it.emoji) } } },
            modifier = Modifier.fillMaxWidth().height(minOf(400.dp, screen * 0.6f)).testTag("emoji-picker"),
        )
    }
}

// all lists of the user, the own and the shared ones of the others, each with how many of its entries
// are open (count); a tap opens one, the plus at the top creates one. The update of the app and the
// answer of its check at the top (UX-13)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <M> ListsHome(
    screen: AppScreen,
    lists: List<ListSummary>,
    loading: Boolean,
    error: UiText?,
    texts: ListTexts,
    model: M,
    count: @Composable (Int) -> String,
) where M : SakModel<*>, M : ListsActions {
    Page(
        stringResource(texts.appName),
        actions = {
            IconButton(onClick = model::newList, modifier = Modifier.testTag("new-list")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(texts.create))
            }
            AccountMenu(screen, model, home = true)
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = model::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("lists")) {
                screen.update?.let { item { UpdateBanner(it, model::update) } }
                screen.updateNotice?.let { item { UpdateNotice(it, model::dismissUpdateNotice) } }
                error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                if (!loading && error == null && lists.isEmpty()) {
                    item { Empty(stringResource(R.string.no_lists)) }
                }
                itemsIndexed(lists, key = { _, list -> list.id }) { index, list ->
                    ListRow(
                        list,
                        count(list.count),
                        open = { model.open(list.id) },
                        settings = { model.openSettings(list.id) },
                        delete = { model.deleteList(list.id) },
                    )
                    if (index < lists.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

// a list on the home: its icon, its name, how many of its entries are open and whether it is shared;
// the menu of its row leads to its settings and deletes it after a confirmation (UX-12, UX-4.1), the
// delete only of a list of the user's own, the last one too (UX-8, LIST-1)
@Composable
private fun ListRow(
    list: ListSummary,
    count: String,
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
                Text(count)
                if (list.shared) Text(stringResource(R.string.shared_badge), color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-${list.id}")) {
                    MenuIcon(list.name)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_settings)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                        onClick = {
                            menu = false
                            settings()
                        },
                        modifier = Modifier.testTag("settings-${list.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.shared_delete)) },
                        onClick = {
                            menu = false
                            confirming = true
                        },
                        enabled = list.mine,
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

// the settings of a new list or of one the user sees; a list of the user's own is deleted after a
// confirmation (UX-4.1), the last one too (LIST-1). Back leads to where they were opened, the list or
// the home, and the back arrow says which. below is what the app adds under the fields of a list that
// exists, e.g. the categories of a grocery list; onRefresh, when not null, loads it again when the form
// is pulled down (ANDROID-3)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <M : ListsActions> ListSettingsScreen(
    settings: ListForm,
    fromList: Boolean,
    texts: ListTexts,
    model: M,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    below: @Composable () -> Unit = {},
) {
    BackHandler(onBack = model::back)
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    Page(
        stringResource(if (settings.id == null) texts.create else R.string.shared_settings),
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(if (fromList) R.string.back_to_list else texts.allLists),
                )
            }
        },
    ) { padding ->
        val form = @Composable { ListSettingsForm(padding, settings, texts, model, { picking = true }, { confirming = true }, below) }
        if (onRefresh == null) {
            form()
        } else {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) { form() }
        }
    }
    if (picking) {
        EmojiSheet(onPick = { emoji ->
            model.editSettings(settings.name, settings.shared, emoji)
            picking = false
        }, onDismiss = { picking = false })
    }
    if (confirming) ConfirmDelete(settings.name, onDelete = { model.deleteList() }, onDismiss = { confirming = false })
}

// the fields and buttons of the settings, then what the app adds below them
@Composable
private fun ListSettingsForm(
    padding: PaddingValues,
    settings: ListForm,
    texts: ListTexts,
    model: ListsActions,
    pick: () -> Unit,
    confirm: () -> Unit,
    below: @Composable () -> Unit,
) {
    val canSave = settings.name.isNotBlank() && !settings.busy
    val edit = { name: String, shared: Boolean, icon: String? -> model.editSettings(name, shared, icon) }
    Form(padding) {
        OutlinedTextField(
            value = settings.name,
            onValueChange = { edit(it, settings.shared, settings.icon) },
            label = { Text(stringResource(R.string.shared_name)) },
            // only the owner renames a list (LIST-2)
            enabled = settings.mine,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSave) model.saveSettings() }),
            modifier = Modifier.fillMaxWidth().testTag("list-name"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ListIcon(settings.name, settings.icon, size = 48)
            TextButton(onClick = pick, modifier = Modifier.testTag("choose-icon")) { Text(stringResource(texts.chooseIcon)) }
            TextButton(onClick = { edit(settings.name, settings.shared, null) }, enabled = settings.icon != null) {
                Text(stringResource(texts.noIcon))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = settings.shared,
                    enabled = settings.mine,
                    role = Role.Switch,
                    onValueChange = { edit(settings.name, it, settings.icon) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.shared_with_all), modifier = Modifier.weight(1f))
            Switch(checked = settings.shared, onCheckedChange = null, enabled = settings.mine, modifier = Modifier.testTag("list-shared"))
        }
        // a shared list of another user: the switch and the delete stay in their place, disabled (UX-8)
        if (!settings.mine) Text(stringResource(R.string.only_owner_share_delete), style = MaterialTheme.typography.bodySmall)
        Error(settings.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings.id != null) {
                // the button that leads to a delete (UX-3): filled like the other buttons, not the
                // color of the screen, the line and the text in the color of danger
                val enabled = settings.mine && !settings.busy
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
                ) { Text(stringResource(texts.deleteList)) }
            }
            Button(onClick = model::saveSettings, enabled = canSave, modifier = Modifier.testTag("save-list")) {
                Text(
                    stringResource(
                        when {
                            settings.busy -> R.string.saving
                            settings.id == null -> texts.create
                            else -> R.string.shared_save
                        },
                    ),
                )
            }
        }
        if (settings.id != null) below()
    }
}
