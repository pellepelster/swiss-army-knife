package io.pelle.sak.todo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.pelle.sak.api.model.TodoItemResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.AccountMenu
import io.pelle.sak.core.ui.Empty
import io.pelle.sak.core.ui.Error
import io.pelle.sak.core.ui.MenuIcon
import io.pelle.sak.core.ui.Page
import io.pelle.sak.core.ui.UndoBar
import io.pelle.sak.core.ui.listTitle
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

// the tasks of one list by priority, then in the order they were added (TODO-5), a done one struck
// through in its place (TODO-3), the field to add one at the top with the day it is to be done by
// (TODO-4); a tap marks a task done or open again. Back leads to the home
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListScreen(
    screen: ListsScreen,
    open: Int,
    model: TodoModel,
) {
    BackHandler(onBack = model::back)
    val list = screen.lists.firstOrNull { it.id == open }
    Page(
        listTitle(list?.summary()),
        navigationIcon = {
            IconButton(onClick = model::back, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.todo_all_lists))
            }
        },
        actions = {
            // the purge of the done tasks, in the color of an error, it deletes them (UX-3); disabled while
            // there are none (UX-8); every user who sees the list purges it (TODO-6, LIST-2)
            val hasDone = screen.items.any { it.doneAt != null }
            IconButton(onClick = model::purge, enabled = hasDone, modifier = Modifier.testTag("purge")) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.todo_list_purge),
                    tint = if (hasDone) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
            }
            // the settings of the list, of a shared list of another user too (LIST-2)
            IconButton(onClick = { model.openSettings(open) }, enabled = list != null, modifier = Modifier.testTag("list-settings")) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(CoreR.string.shared_settings))
            }
            AccountMenu(screen, model)
        },
        bottomBar = { screen.undo?.let { UndoBar(it, model.undoMs, model::undo) } },
    ) { padding ->
        val today = rememberToday()
        Column(Modifier.padding(padding).fillMaxSize()) {
            NewItem(screen, model)
            PullToRefreshBox(isRefreshing = screen.loading, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("items")) {
                    screen.error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                    if (!screen.loading && screen.error == null && screen.items.isEmpty()) item { Empty(stringResource(R.string.todo_nothing_to_do)) }
                    itemsIndexed(screen.items, key = { _, item -> item.id }) { index, item ->
                        ItemRow(item, item.doneAt != null, today, model)
                        if (index < screen.items.lastIndex) HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

// the field at the top of a list, without a button: Enter on the keyboard adds the task and keeps the
// keyboard open for the next one. The calendar next to it chooses the day it is to be done by, shown
// under the field until it is added; a tap on it takes the day away again (TODO-4)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewItem(
    screen: ListsScreen,
    model: TodoModel,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = screen.itemTitle,
            onValueChange = model::typed,
            label = { Text(stringResource(R.string.todo_list_task)) },
            singleLine = true,
            trailingIcon =
                if (screen.adding) {
                    { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { model.addItem() }),
            modifier = Modifier.weight(1f).padding(vertical = 8.dp).testTag("item-title"),
        )
        IconButton(onClick = { picking = true }, modifier = Modifier.testTag("item-due")) {
            Icon(painterResource(R.drawable.ic_calendar), contentDescription = stringResource(R.string.todo_list_due_date))
        }
        PriorityButton(screen.itemPriority, model::choosePriority)
    }
    // the priority chosen that is not the normal one, a tap makes it normal again (TODO-5)
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

// the date picker of a due date (TODO-4): OK takes the day chosen, none when none is, Cancel nothing.
// On a screen too low for the calendar, e.g. a phone in landscape, the day is typed in
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.todo_cancel)) } },
    ) { DatePicker(state, showModeToggle = !low) }
}

// the height the calendar of the date picker needs with its buttons
private val CALENDAR_HEIGHT = 600.dp

// the day today, again when the app comes to the front and at midnight, so a due date that passed
// shows as overdue (TODO-4)
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

// a task: a tap marks it done, struck through, a tap on a done one opens it again; no checkbox, the
// row tells a screen reader it is one (TODO-3); its due date after it, in the color of an error once
// it passed (TODO-4); its menu changes its priority (TODO-5)
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

// the menu of a task changes its priority, the current one checked (TODO-5), adds a due date to a task
// without one, in the date picker, and takes it away again, each disabled when it does not apply
// (TODO-4, UX-8)
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
            PriorityMenuItems(item.priority, tag = { "set-priority-${item.id}-${it.value}" }) {
                menu = false
                model.setPriority(item, it)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.todo_list_add_due_date)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
                onClick = {
                    menu = false
                    picking = true
                },
                enabled = item.dueDate == null,
                modifier = Modifier.testTag("add-due-date-${item.id}"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.todo_list_remove_due_date)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
                onClick = {
                    menu = false
                    model.setDueDate(item, null)
                },
                enabled = item.dueDate != null,
                modifier = Modifier.testTag("remove-due-date-${item.id}"),
            )
        }
    }
    if (picking) DuePicker(null, onPick = { day -> day?.let { model.setDueDate(item, it) } }, onDismiss = { picking = false })
}

// the flag next to the field chooses the priority of the next task from a menu (TODO-5)
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

// the arrow of a priority, up for a high one, down for a low one, none for a normal one; a screen
// reader says the priority
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

// a text, struck through with a red line through the middle of each of its lines, more visible than
// the line of the font, as the grocery lists strike an item through
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
                    drawLine(color, Offset(lines.getLineLeft(index), middle), Offset(lines.getLineRight(index), middle), strokeWidth = 2.dp.toPx())
                }
            }
        }
    Text(text, style = style, modifier = line, onTextLayout = { layout = it })
}
