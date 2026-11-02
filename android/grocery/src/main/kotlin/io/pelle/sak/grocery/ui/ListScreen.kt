package io.pelle.sak.grocery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.AccountMenu
import io.pelle.sak.core.ui.ConfirmDelete
import io.pelle.sak.core.ui.Error
import io.pelle.sak.core.ui.Page
import io.pelle.sak.core.ui.resolve
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListsScreen
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.Renaming
import io.pelle.sak.grocery.Undo

// the items to buy of one list, the field to add one at the top; back leads to the home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListScreen(
    screen: ListsScreen,
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
            // the settings of the list, of a shared list of another user too (LIST-2)
            IconButton(
                onClick = { model.openSettings(open) },
                enabled = list != null,
                modifier = Modifier.testTag("list-settings"),
            ) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(CoreR.string.shared_settings))
            }
            AccountMenu(screen, model)
        },
        bottomBar = { screen.undo?.let { UndoBar(it, model.undoMs, model::undo) } },
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
internal fun CategoryScreen(
    screen: ListsScreen,
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

// the field at the top of a list, without a button: Enter on the keyboard adds the item and keeps the
// keyboard open for the next one (GROCERY-2). Under it the names the list suggests for what is typed
// (GROCERY-11), a tap takes one with its quantity and category
@Composable
private fun NewItem(
    screen: ListsScreen,
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
    if (screen.suggestions.isNotEmpty()) {
        val label = stringResource(R.string.grocery_suggestions)
        Surface(
            tonalElevation = 2.dp,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().semantics { contentDescription = label }.testTag("suggestions"),
        ) {
            Column {
                screen.suggestions.forEach { suggestion ->
                    val category = screen.categories.firstOrNull { it.id == suggestion.categoryId }
                    ListItem(
                        headlineContent = { Text(suggestion.name) },
                        supportingContent =
                            listOfNotNull(
                                quantity(suggestion.amount, suggestion.unit),
                                category?.let { listOfNotNull(it.icon, it.name).joinToString(" ") },
                            ).takeIf { it.isNotEmpty() }?.let { parts -> { Text(parts.joinToString(" · ")) } },
                        modifier = Modifier.fillMaxWidth().clickable { model.chooseSuggestion(suggestion) }.testTag("suggestion-${suggestion.id}"),
                    )
                }
            }
        }
    }
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
            quantity(item.amount, item.unit)?.let { StruckText(it, style.copy(color = muted), crossed) }
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

// the menu of an item, to buy or bought (GROCERY-14): its name in a dialog (RenameItem); its category,
// chosen on a screen of its own (CategoryScreen); a photo from the camera or the photos of the phone
// (PhotoPicker), scaled down on the phone before it is sent, or the photo removed; the camera only
// when the phone has one (UX-8); a photo while another one is sent waits for it. Its last entry
// deletes the item for good after a confirmation (UX-12, UX-4.1)
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
                MenuIcon(item.name)
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
                text = { Text(stringResource(CoreR.string.shared_delete)) },
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
                label = { Text(stringResource(CoreR.string.shared_name)) },
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
                Text(stringResource(CoreR.string.shared_save))
            }
        },
        dismissButton = { TextButton(onClick = model::dismissRename) { Text(stringResource(R.string.grocery_cancel)) } },
    )
}

// what was checked last and the undo, while the bar of the time left shrinks, anew for every check;
// a screen reader says it once it shows (AN-34). millis is the time of the model, longer when the phone
// asks for more time for a control
@Composable
private fun UndoBar(
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
                TextButton(onClick = onUndo) { Text(stringResource(CoreR.string.shared_undo)) }
            }
            LinearProgressIndicator(progress = { left.value }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
        }
    }
}
