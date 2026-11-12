package io.pelle.sak.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.core.ui.Busy
import io.pelle.sak.core.ui.ListSettingsScreen
import io.pelle.sak.core.ui.ListTexts
import io.pelle.sak.core.ui.ListsHome
import io.pelle.sak.core.ui.UndoTime
import io.pelle.sak.todo.ListsScreen
import io.pelle.sak.todo.R
import io.pelle.sak.todo.TodoModel
import io.pelle.sak.todo.UNDO_MS
import io.pelle.sak.todo.summary

// the texts of the app on the screens of the lists of core
internal val TODO_TEXTS =
    ListTexts(
        appName = R.string.todo_app_name,
        create = R.string.todo_form_create,
        allLists = R.string.todo_all_lists,
        chooseIcon = R.string.todo_form_choose_icon,
        noIcon = R.string.todo_form_no_icon,
        deleteList = R.string.todo_form_delete_list,
    )

// the screens of the app, one at a time, as the model has it
@Composable
fun TodoApp(model: TodoModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    // a purge can be undone longer when the phone asks for more time for a control (AN-34)
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
