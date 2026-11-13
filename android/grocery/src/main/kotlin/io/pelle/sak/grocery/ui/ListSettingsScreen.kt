package io.pelle.sak.grocery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.pelle.sak.api.model.GroceryCategoryResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.ConfirmDelete
import io.pelle.sak.core.ui.EmojiSheet
import io.pelle.sak.core.ui.ListIcon
import io.pelle.sak.core.ui.ListSettingsScreen
import io.pelle.sak.core.ui.MenuIcon
import io.pelle.sak.core.ui.Error
import io.pelle.sak.core.ui.Form
import io.pelle.sak.core.ui.Page
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListSettings
import io.pelle.sak.grocery.R

// the settings of a new list or of one the user sees (ListSettingsScreen of core), of a list that
// exists with its categories below, loaded again when pulled down (ANDROID-3)
@Composable
internal fun ListSettingsScreen(
    settings: ListSettings,
    fromList: Boolean,
    model: GroceryModel,
) {
    ListSettingsScreen(
        settings,
        fromList,
        GROCERY_TEXTS,
        model,
        refreshing = settings.categoriesLoading,
        onRefresh = model::refreshCategories.takeIf { settings.id != null },
    ) { Categories(settings, model) }
}

// the categories of a list in the order of the groups of its items (GROCERY-13), each change saved at
// once, one after the other; up and down in the menu are disabled at the ends (UX-8), a delete asks
// for a confirmation (UX-12). Categories that could not be loaded show why and a way to load them
// again
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
    val drag = remember { CategoryDrag() }
    val categories = settings.categories
    val target = drag.target(categories)
    val from = categories.indexOfFirst { it.id == drag.id }
    categories.forEachIndexed { index, category ->
        // the rows the dragged one passed make room for it, the dragged one follows the finger
        val shift =
            when {
                category.id == drag.id -> drag.offset
                from < 0 -> 0f
                index in (from + 1)..target -> -(drag.heights[drag.id] ?: 0).toFloat()
                index in target until from -> (drag.heights[drag.id] ?: 0).toFloat()
                else -> 0f
            }
        // the state of a row stays with its category when the rows move
        key(category.id) {
            CategoryRow(
                category,
                name = settings.drafts[category.id] ?: category.name,
                invalid = category.id in settings.invalidNames,
                first = index == 0,
                last = index == categories.lastIndex,
                dragged = category.id == drag.id,
                shift = shift,
                onDragStart = { drag.start(category.id) },
                onDrag = { drag.offset += it },
                // the order of the categories when the drag ends, not when the row was first shown: an
                // earlier move changed it
                onDragEnd = { drag.end(categories)?.let { to -> model.moveCategory(category.id, to) } },
                onDragCancel = drag::cancel,
                onSize = { drag.heights[category.id] = it },
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

// the drag of a category by its handle: which one, how far from its place, and the heights of the
// rows, which tell where it passed the middle of another one
private class CategoryDrag {
    var id by mutableStateOf<Int?>(null)
    var offset by mutableFloatStateOf(0f)
    val heights = mutableStateMapOf<Int, Int>()

    fun start(categoryId: Int) {
        id = categoryId
        offset = 0f
    }

    // the place the dragged category goes to: past every row whose middle it passed
    fun target(categories: List<GroceryCategoryResponse>): Int {
        val from = categories.indexOfFirst { it.id == id }
        if (from < 0) return -1
        var to = from
        var passed = 0f
        if (offset > 0) {
            while (to < categories.lastIndex) {
                val next = (heights[categories[to + 1].id] ?: 0).toFloat()
                if (offset < passed + next / 2) break
                passed += next
                to++
            }
        } else {
            while (to > 0) {
                val previous = (heights[categories[to - 1].id] ?: 0).toFloat()
                if (-offset < passed + previous / 2) break
                passed += previous
                to--
            }
        }
        return to
    }

    // the place it was dropped at, null when it stays
    fun end(categories: List<GroceryCategoryResponse>): Int? {
        val from = categories.indexOfFirst { it.id == id }
        val to = target(categories)
        cancel()
        return to.takeIf { from >= 0 && it != from }
    }

    fun cancel() {
        id = null
        offset = 0f
    }
}

// a category in the settings: the handle drags it after a long press, so a scroll does not start a
// drag, and a screen reader moves it with its actions; the name is saved when its field is left or
// done (UX-6). A change made while another one is sent waits for it
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryRow(
    category: GroceryCategoryResponse,
    name: String,
    invalid: Boolean,
    first: Boolean,
    last: Boolean,
    dragged: Boolean,
    shift: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onSize: (Int) -> Unit,
    model: GroceryModel,
) {
    var focused by remember { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val up = stringResource(R.string.grocery_category_up)
    val down = stringResource(R.string.grocery_category_down)
    val haptics = LocalHapticFeedback.current
    // the gesture starts once for the row and outlives recompositions: it calls the callbacks of now
    val dragStart by rememberUpdatedState(onDragStart)
    val dragMove by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .onSizeChanged { onSize(it.height) }
                .zIndex(if (dragged) 1f else 0f)
                .graphicsLayer {
                    translationY = shift
                    shadowElevation = if (dragged) 8.dp.toPx() else 0f
                }.background(if (dragged) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .testTag("category-${category.id}"),
    ) {
        Icon(
            painterResource(R.drawable.ic_grip_vertical),
            contentDescription = stringResource(R.string.grocery_category_drag, category.name),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .size(48.dp)
                    .padding(12.dp)
                    // the handle has a description, a screen reader focuses it and moves it with these
                    .semantics {
                        customActions =
                            listOfNotNull(
                                CustomAccessibilityAction(up) { model.moveCategory(category.id, up = true).let { true } }.takeIf { !first },
                                CustomAccessibilityAction(down) { model.moveCategory(category.id, up = false).let { true } }.takeIf { !last },
                            )
                    }.pointerInput(category.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragStart()
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragMove(amount.y)
                            },
                            onDragEnd = { dragEnd() },
                            onDragCancel = { dragCancel() },
                        )
                    }.testTag("category-handle-${category.id}"),
        )
        val iconLabel = stringResource(R.string.grocery_category_icon, category.name)
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = iconLabel) { picking = true }
                .semantics { contentDescription = iconLabel },
            contentAlignment = Alignment.Center,
        ) {
            val icon = category.icon
            if (icon != null) {
                Text(icon, fontSize = 28.sp)
            } else {
                Icon(painterResource(R.drawable.ic_no_icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { model.editCategoryName(category.id, it) },
            label = { Text(stringResource(CoreR.string.shared_name)) },
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
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-category-${category.id}")) {
                MenuIcon(category.name)
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
                    text = { Text(up) },
                    onClick = {
                        menu = false
                        model.moveCategory(category.id, up = true)
                    },
                    enabled = !first,
                    modifier = Modifier.testTag("category-up-${category.id}"),
                )
                DropdownMenuItem(
                    text = { Text(down) },
                    onClick = {
                        menu = false
                        model.moveCategory(category.id, up = false)
                    },
                    enabled = !last,
                    modifier = Modifier.testTag("category-down-${category.id}"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(CoreR.string.shared_delete)) },
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
        EmojiSheet(onPick = { emoji ->
            model.setCategoryIcon(category.id, emoji)
            picking = false
        }, onDismiss = { picking = false })
    }
    if (confirming) ConfirmDelete(category.name, onDelete = { model.deleteCategory(category.id) }, onDismiss = { confirming = false })
}
