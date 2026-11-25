package io.pelle.sak.todo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.lists.Renaming
import io.pelle.sak.core.ui.ConfirmDelete
import io.pelle.sak.core.ui.DeleteMenuEntry
import io.pelle.sak.core.ui.MenuEntry
import io.pelle.sak.core.ui.MenuIcon
import io.pelle.sak.core.ui.ListPage
import io.pelle.sak.core.ui.NewEntryField
import io.pelle.sak.core.ui.RenameDialog
import io.pelle.sak.core.ui.StruckText
import io.pelle.sak.todo.ListsScreen
import io.pelle.sak.todo.Priority
import io.pelle.sak.todo.R
import io.pelle.sak.todo.TodoModel
import io.pelle.sak.todo.due
import io.pelle.sak.todo.summary
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// the day as the phone writes it, e.g. 24 Dec 2026
internal fun dayText(day: LocalDate): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

// the tasks of one list (TODO-3, TODO-5), the field to add one at the top (TODO-4)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListScreen(
    screen: ListsScreen,
    open: Int,
    model: TodoModel,
) {
    // the dialogs of the menu of a task live in the model, they stay when the row goes
    screen.renaming?.let { RenameItem(it, model) }
    screen.deleting?.let { item -> ConfirmDelete(item.title, onDelete = { model.deleteItem(item) }, onDismiss = model::dismissDelete) }
    val today = rememberToday()
    ListPage(
        screen,
        open,
        screen.lists.firstOrNull { it.id == open }?.summary(),
        stringResource(CoreR.string.lists_all_lists),
        screen.loading,
        screen.error,
        stringResource(R.string.todo_nothing_to_do).takeIf { screen.items.isEmpty() },
        screen.undo,
        model.undoMs,
        model,
        onUndo = model::undo,
        actions = {
            // in the color of an error, it deletes (UX-3); disabled while nothing is done (UX-8); every
            // user who sees the list may purge it (TODO-6, LIST-2)
            val hasDone = screen.items.any { it.doneAt != null }
            IconButton(onClick = model::purge, enabled = hasDone, modifier = Modifier.testTag("purge")) {
                Icon(
                    painterResource(R.drawable.ic_broom),
                    contentDescription = stringResource(R.string.todo_list_purge),
                    tint = if (hasDone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
        },
        top = { NewItem(screen, model) },
    ) {
        itemsIndexed(screen.items, key = { _, item -> item.id }) { index, item ->
            ItemRow(item, item.doneAt != null, today, model)
            if (index < screen.items.lastIndex) HorizontalDivider()
        }
    }
}

// no add button: Enter adds the task and keeps the keyboard open for the next one. The due date and a
// priority chosen show as chips under the field, a tap on a chip takes it away (TODO-4, TODO-5)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewItem(
    screen: ListsScreen,
    model: TodoModel,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    NewEntryField(
        screen.itemTitle,
        model::typed,
        stringResource(R.string.todo_list_task),
        screen.adding,
        model::addItem,
        tag = "item-title",
        end = 4.dp,
    ) {
        IconButton(onClick = { picking = true }, modifier = Modifier.testTag("item-due")) {
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = stringResource(R.string.todo_list_due_date))
        }
        PriorityButton(screen.itemPriority, model::choosePriority)
    }
    screen.itemPriority.takeIf { it != Priority.NORMAL }?.let { priority ->
        AssistChip(
            onClick = { model.choosePriority(Priority.NORMAL) },
            label = { Text(priorityText(priority)) },
            leadingIcon = { PriorityIcon(priority) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_xmark), contentDescription = stringResource(R.string.todo_priority_normal), Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp).testTag("item-priority-chosen"),
        )
    }
    screen.itemDue?.let { day ->
        AssistChip(
            onClick = { model.chooseDue(null) },
            label = { Text(stringResource(R.string.todo_list_due, dayText(day))) },
            trailingIcon = { Icon(painterResource(R.drawable.ic_xmark), contentDescription = stringResource(R.string.todo_no_due_date), Modifier.size(18.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp).testTag("item-due-chosen"),
        )
    }
    if (picking) {
        DuePicker(screen.itemDue, onPick = model::chooseDue, onDismiss = { picking = false })
    }
}

// OK takes the day chosen, null when none is, Cancel nothing (TODO-4). On a screen too low for the
// calendar, e.g. a phone in landscape, the day is typed in
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuePicker(
    initial: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val low = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < CALENDAR_HEIGHT
    // the picker counts days in utc milliseconds
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialDisplayMode = if (low) DisplayMode.Input else DisplayMode.Picker,
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onPick(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
            }, modifier = Modifier.testTag("due-picker-ok")) { Text(stringResource(R.string.todo_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.shared_cancel)) } },
    ) { DatePicker(state, showModeToggle = !low) }
}

// the height the calendar of the date picker needs with its buttons
private val CALENDAR_HEIGHT = 600.dp

// updated on resume and at midnight, so a due date that passed shows as overdue (TODO-4)
@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    LaunchedEffect(today) {
        val midnight = today.plusDays(1).atStartOfDay(ZoneId.systemDefault())
        delay(Duration.between(ZonedDateTime.now(), midnight).toMillis().coerceAtLeast(1_000))
        today = LocalDate.now()
    }
    return today
}

// a tap toggles done; no checkbox, the row tells a screen reader it is one (TODO-3); the due date in
// the color of an error once it passed (TODO-4)
@Composable
private fun ItemRow(
    item: TodoItemResponse,
    struck: Boolean,
    today: LocalDate,
    model: TodoModel,
) {
    val text = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .toggleable(value = struck, role = Role.Checkbox, onValueChange = { model.done(item) })
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                .testTag("item-${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // a high priority in the color of an error, a low one muted, a normal one none (TODO-5)
            Box(Modifier.size(24.dp)) { PriorityIcon(item.priority, Modifier.testTag("priority-${item.id}")) }
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f)) {
                StruckText(item.title, if (struck) text.copy(color = MaterialTheme.colorScheme.onSurfaceVariant) else text, struck)
            }
            val due = remember(item.dueDate) { item.due }
            due?.let { day ->
                val overdue = day.isBefore(today)
                Text(
                    stringResource(if (overdue) R.string.todo_list_overdue else R.string.todo_list_due, dayText(day)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).testTag("due-${item.id}"),
                )
            }
        }
        ItemMenu(item, model)
    }
}

// rename, the priority (TODO-5), add or remove the due date, each disabled when it does not apply
// (TODO-4, UX-8). Its last entry deletes the task after a confirmation (UX-12, UX-4.1)
@Composable
private fun ItemMenu(
    item: TodoItemResponse,
    model: TodoModel,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-item-${item.id}")) { MenuIcon(item.title) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuEntry(
                stringResource(R.string.todo_list_rename),
                close = { menu = false },
                modifier = Modifier.testTag("rename-item-${item.id}"),
            ) { model.askRename(item) }
            HorizontalDivider()
            PriorityMenuItems(item.priority, tag = { "set-priority-${item.id}-${it.value}" }) {
                menu = false
                model.setPriority(item, it)
            }
            HorizontalDivider()
            MenuEntry(
                stringResource(R.string.todo_list_add_due_date),
                close = { menu = false },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
                enabled = item.dueDate == null,
                modifier = Modifier.testTag("add-due-date-${item.id}"),
            ) { picking = true }
            MenuEntry(
                stringResource(R.string.todo_list_remove_due_date),
                close = { menu = false },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
                enabled = item.dueDate != null,
                modifier = Modifier.testTag("remove-due-date-${item.id}"),
            ) { model.setDueDate(item, null) }
            HorizontalDivider()
            DeleteMenuEntry(
                close = { menu = false },
                modifier = Modifier.testTag("delete-item-${item.id}"),
            ) { model.askDelete(item) }
        }
    }
    if (picking) DuePicker(null, onPick = { day -> day?.let { model.setDueDate(item, it) } }, onDismiss = { picking = false })
}

// an empty or too long title can not be saved (UX-6)
@Composable
private fun RenameItem(
    renaming: Renaming<TodoItemResponse>,
    model: TodoModel,
) = RenameDialog(
    renaming,
    TodoModel.validTitle(renaming.name),
    stringResource(R.string.todo_list_rename),
    stringResource(R.string.todo_list_task),
    stringResource(R.string.todo_item_title_invalid),
    onEdit = model::editRename,
    onLeave = model::leaveRename,
    onSave = model::renameItem,
    onDismiss = model::dismissRename,
)

// the priority of the next task (TODO-5)
@Composable
private fun PriorityButton(
    current: Priority,
    choose: (Priority) -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("item-priority")) {
            Icon(painterResource(R.drawable.ic_flag), contentDescription = stringResource(R.string.todo_priority_label))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            PriorityMenuItems(current, tag = { "priority-${it.value}" }) {
                menu = false
                choose(it)
            }
        }
    }
}

// the priorities in a menu, the high one first, the current one checked (TODO-5)
@Composable
private fun PriorityMenuItems(
    current: Priority,
    tag: (Priority) -> String,
    choose: (Priority) -> Unit,
) {
    Priority.entries.forEach { priority ->
        DropdownMenuItem(
            text = { Text(priorityText(priority)) },
            leadingIcon = { Box(Modifier.size(24.dp)) { PriorityIcon(priority) } },
            trailingIcon = if (priority == current) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
            onClick = { choose(priority) },
            modifier = Modifier.testTag(tag(priority)),
        )
    }
}

// none for normal; a screen reader says the priority
@Composable
private fun PriorityIcon(
    priority: Priority,
    modifier: Modifier = Modifier,
) {
    when (priority) {
        Priority.HIGH -> Icon(painterResource(R.drawable.ic_arrow_up), priorityText(priority), modifier, tint = MaterialTheme.colorScheme.error)
        Priority.LOW -> Icon(painterResource(R.drawable.ic_arrow_down), priorityText(priority), modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Priority.NORMAL -> {}
    }
}

@Composable
private fun priorityText(priority: Priority) =
    stringResource(
        when (priority) {
            Priority.HIGH -> R.string.todo_priority_high
            Priority.NORMAL -> R.string.todo_priority_normal
            Priority.LOW -> R.string.todo_priority_low
        },
    )
