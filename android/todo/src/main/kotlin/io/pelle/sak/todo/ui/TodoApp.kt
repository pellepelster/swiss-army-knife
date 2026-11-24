package io.pelle.sak.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.core.ui.Busy
import io.pelle.sak.core.ui.ListSettingsScreen
import io.pelle.sak.core.ui.ListsHome
import io.pelle.sak.core.ui.UndoTime
import io.pelle.sak.todo.ListsScreen
import io.pelle.sak.todo.TODO_TEXTS
import io.pelle.sak.todo.R
import io.pelle.sak.todo.TodoModel
import io.pelle.sak.todo.UNDO_MS
import io.pelle.sak.todo.summary

@Composable
fun TodoApp(model: TodoModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    // a purge can be undone longer when the accessibility settings ask for more time
    UndoTime(UNDO_MS) { model.undoMs = it }
    when (val current = screen) {
        is ListsScreen -> TodoScreen(current, model)
        else -> Busy()
    }
}

// the home with all lists, or the list open, or the settings over them
@Composable
private fun TodoScreen(
    screen: ListsScreen,
    model: TodoModel,
) {
    val open = screen.open
    val settings = screen.settings
    when {
        settings != null -> ListSettingsScreen(settings, fromList = open != null, TODO_TEXTS, model)
        open == null -> ListsHome(screen, screen.lists.map { it.summary() }, screen.loading, screen.error, TODO_TEXTS, model) { openTasks(it) }
        else -> ListScreen(screen, open, model)
    }
}

// No open tasks, 1 open task, 3 open tasks
@Composable
private fun openTasks(items: Int) =
    if (items == 0) stringResource(R.string.todo_lists_open_none) else pluralStringResource(R.plurals.todo_open, items, items)
