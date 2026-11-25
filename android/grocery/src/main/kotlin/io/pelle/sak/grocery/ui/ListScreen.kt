package io.pelle.sak.grocery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.ConfirmDelete
import io.pelle.sak.core.ui.DeleteMenuEntry
import io.pelle.sak.core.ui.MenuEntry
import io.pelle.sak.core.ui.Page
import io.pelle.sak.core.ui.MenuIcon
import io.pelle.sak.core.ui.ListPage
import io.pelle.sak.core.ui.LocalPhotoCapture
import io.pelle.sak.core.ui.NewEntryField
import io.pelle.sak.core.ui.RenameDialog
import io.pelle.sak.core.ui.StruckText
import io.pelle.sak.core.ui.resolve
import io.pelle.sak.grocery.summary
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.Amount
import io.pelle.sak.grocery.ListsScreen
import io.pelle.sak.grocery.Quantity
import io.pelle.sak.grocery.R
import io.pelle.sak.core.lists.Renaming
import io.pelle.sak.grocery.amountOf

// the items to buy of one list, the field to add one at the top; back leads to the home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListScreen(
    screen: ListsScreen,
    open: Int,
    model: GroceryModel,
) {
    screen.viewing?.let { PhotoDialog(it, model) }
    // the dialogs of the menu of an item live in the model, they stay when the row goes (GROCERY-14)
    screen.renaming?.let { RenameItem(it, model) }
    screen.quantity?.let { QuantityItem(it, model) }
    screen.discovery?.let { DiscoveryDialog(it, model) }
    screen.deleting?.let { item -> ConfirmDelete(item.name, onDelete = { model.deleteItem(item) }, onDismiss = model::dismissDelete) }
    // nothing to buy, also while the bought items show
    val toBuy = screen.groups.any { group -> group.items.any { it.checkedAt == null } }
    ListPage(
        screen,
        open,
        screen.lists.firstOrNull { it.id == open }?.summary(),
        stringResource(CoreR.string.lists_all_lists),
        screen.loading,
        screen.error,
        stringResource(R.string.grocery_nothing_to_buy).takeIf { !toBuy },
        screen.undo,
        model.undoMs,
        model,
        onUndo = model::undo,
        actions = {
            // the bought items hidden (eye-slash) or shown (eye) too (GROCERY-14); a toggle, a screen
            // reader says whether it is on
            IconToggleButton(checked = screen.showBought, onCheckedChange = { model.toggleBought() }, modifier = Modifier.testTag("toggle-bought")) {
                Icon(
                    painterResource(if (screen.showBought) R.drawable.ic_eye else R.drawable.ic_eye_slash),
                    contentDescription = stringResource(R.string.grocery_list_show_bought),
                )
            }
        },
        top = { NewItem(screen, model) },
    ) {
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
    }
}

// a tap sets the category and leads back to the list, back too without a change (ANDROID-2)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryScreen(
    screen: ListsScreen,
    item: GroceryItemResponse,
    model: GroceryModel,
) {
    Page(
        stringResource(R.string.grocery_category_of, item.name),
        onBack = model::back,
        back = stringResource(CoreR.string.back_to_list),
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

// the category of the item marked
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

// no button: Enter adds the item and keeps the keyboard open for the next one (GROCERY-2); under it
// the suggestions (GROCERY-11), a tap adds one with its quantity and category
@Composable
private fun NewItem(
    screen: ListsScreen,
    model: GroceryModel,
) {
    NewEntryField(
        screen.itemName,
        model::typed,
        stringResource(R.string.grocery_list_item),
        screen.adding,
        model::addItem,
        tag = "item-name",
        end = if (screen.discoverItems || screen.aiConfigured) 4.dp else 16.dp,
    ) {
        when {
            screen.discoverItems -> DiscoverButton(busy = screen.discovery != null)
            // set up but not turned on by the user, the robot leads to their settings (PRIVACY-2)
            screen.aiConfigured ->
                IconButton(onClick = model.userSettings::open, modifier = Modifier.testTag("turn-on-ai")) {
                    Icon(
                        painterResource(R.drawable.ic_robot),
                        contentDescription = stringResource(CoreR.string.shared_turn_on_ai),
                        tint = LocalContentColor.current.copy(alpha = 0.38f),
                    )
                }
        }
    }
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

// adds the items of a photo (GROCERY-16); the camera only when the phone has one (UX-8). Its label
// names OpenRouter, where the photo goes (PRIVACY-2)
@Composable
private fun DiscoverButton(busy: Boolean) {
    val photos = LocalPhotoCapture.current
    var menu by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, enabled = !busy, modifier = Modifier.testTag("discover")) {
            Icon(painterResource(R.drawable.ic_robot), contentDescription = stringResource(R.string.grocery_discover_label))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuEntry(
                stringResource(CoreR.string.photo_take),
                close = { menu = false },
                enabled = photos.hasCamera,
            ) { photos.take(DISCOVER) }
            MenuEntry(
                stringResource(CoreR.string.photo_choose),
                close = { menu = false },
            ) { photos.choose(DISCOVER) }
        }
    }
}

// a tap on the photo shows it in full, on the rest of the row checks the item: no checkbox, the row
// tells a screen reader it is one (GROCERY-3). A bought item (GROCERY-14) is struck through and a tap
// does nothing. The photo has the same space to its left, above and below it
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
            // struck through, the line more visible than the one of the font (GROCERY-3)
            Box(Modifier.weight(1f)) { StruckText(item.name, style, crossed) }
            quantity(item.amount, item.unit)?.let { StruckText(it, style.copy(color = muted), crossed) }
        }
        ItemMenu(item, busy, model)
    }
}

// the menu of an item, to buy or bought (GROCERY-14, GROCERY-15); the camera only when the phone has
// one (UX-8), a photo waits while another one is sent. Its last entry deletes the item after a
// confirmation (UX-12, UX-4.1)
@Composable
private fun ItemMenu(
    item: GroceryItemResponse,
    busy: Boolean,
    model: GroceryModel,
) {
    val photos = LocalPhotoCapture.current
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
            MenuEntry(
                stringResource(R.string.grocery_list_rename),
                close = { menu = false },
                modifier = Modifier.testTag("rename-item-${item.id}"),
            ) { model.askRename(item) }
            MenuEntry(
                stringResource(R.string.grocery_quantity_label),
                close = { menu = false },
                modifier = Modifier.testTag("quantity-item-${item.id}"),
            ) { model.askQuantity(item) }
            MenuEntry(
                stringResource(R.string.grocery_list_category),
                close = { menu = false },
                modifier = Modifier.testTag("category-item-${item.id}"),
            ) { model.chooseCategory(item) }
            MenuEntry(
                stringResource(CoreR.string.photo_take),
                close = { menu = false },
                enabled = photos.hasCamera && !busy,
            ) { photos.take(item.id) }
            MenuEntry(
                stringResource(if (item.photoId == null) CoreR.string.photo_choose else R.string.grocery_photo_replace),
                close = { menu = false },
                enabled = !busy,
            ) { photos.choose(item.id) }
            MenuEntry(
                stringResource(R.string.grocery_photo_remove),
                close = { menu = false },
                enabled = item.photoId != null && !busy,
            ) { model.removePhoto(item) }
            DeleteMenuEntry(
                close = { menu = false },
                enabled = !busy,
                modifier = Modifier.testTag("delete-item-${item.id}"),
            ) { model.askDelete(item) }
        }
    }
}

// an empty or too long name can not be saved (UX-6)
@Composable
private fun RenameItem(
    renaming: Renaming<GroceryItemResponse>,
    model: GroceryModel,
) = RenameDialog(
    renaming,
    GroceryModel.validItemName(renaming.name),
    stringResource(R.string.grocery_list_rename),
    stringResource(CoreR.string.shared_name),
    stringResource(R.string.grocery_item_name_invalid),
    onEdit = model::editRename,
    onLeave = model::leaveRename,
    onSave = model::renameItem,
    onDismiss = model::dismissRename,
)

// GROCERY-15: an invalid amount shows its error once the field was left or Save was pressed, and can
// not be saved (UX-6); no amount drops the unit. Cancel is the safe default
@Composable
private fun QuantityItem(
    quantity: Quantity,
    model: GroceryModel,
) {
    val valid = amountOf(quantity.amount) != Amount.Invalid
    AlertDialog(
        onDismissRequest = model::dismissQuantity,
        title = { Text(stringResource(R.string.grocery_quantity_label)) },
        text = {
            Column {
                AmountField(
                    quantity.amount,
                    quantity.left && !valid,
                    onAmount = model::editQuantityAmount,
                    onLeave = model::leaveQuantity,
                    imeAction = ImeAction.Done,
                    onDone = model::saveQuantity,
                    modifier = Modifier.fillMaxWidth().testTag("quantity-amount"),
                )
                UnitField(quantity.unit, onUnit = model::chooseQuantityUnit, modifier = Modifier.fillMaxWidth().testTag("quantity-unit"))
            }
        },
        confirmButton = {
            TextButton(onClick = model::saveQuantity, enabled = valid, modifier = Modifier.testTag("quantity-save")) {
                Text(stringResource(CoreR.string.shared_save))
            }
        },
        dismissButton = { TextButton(onClick = model::dismissQuantity) { Text(stringResource(CoreR.string.shared_cancel)) } },
    )
}
