package io.pelle.sak.grocery.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.Screen
import io.pelle.sak.core.ui.Busy
import io.pelle.sak.core.ui.DeploymentScreen
import io.pelle.sak.core.ui.ListTexts
import io.pelle.sak.core.ui.ListsHome
import io.pelle.sak.core.ui.SignInScreen
import io.pelle.sak.core.ui.UndoTime
import io.pelle.sak.grocery.summary
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListsScreen
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.UNDO_MS
import io.pelle.sak.grocery.amountText

// the texts of the app on the screens of the lists of core
internal val GROCERY_TEXTS =
    ListTexts(
        appName = R.string.grocery_app_name,
        create = R.string.grocery_form_create,
        allLists = R.string.grocery_all_lists,
        chooseIcon = R.string.grocery_form_choose_icon,
        noIcon = R.string.grocery_form_no_icon,
        deleteList = R.string.grocery_form_delete_list,
    )

// No items to buy, 1 item to buy, 3 items to buy
@Composable
private fun toBuy(items: Int) =
    if (items == 0) stringResource(R.string.grocery_lists_to_buy_none) else pluralStringResource(R.plurals.grocery_to_buy, items, items)

// the screens of the app, one at a time, as the model has it
@Composable
fun GroceryApp(model: GroceryModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    // a check can be undone longer when the phone asks for more time for a control (AN-34)
    UndoTime(UNDO_MS) { model.undoMs = it }
    CompositionLocalProvider(LocalPhotoPicker provides rememberPhotoPicker(model)) {
        when (val current = screen) {
            is Screen.Deployment -> DeploymentScreen(stringResource(R.string.grocery_app_name), current, model::connect)
            is Screen.SignIn -> SignInScreen(current, model)
            is ListsScreen -> GroceryScreen(current, model)
            else -> Busy()
        }
    }
}

// the home with all lists, or the list open, or the settings or the category of an item over them
@Composable
private fun GroceryScreen(
    screen: ListsScreen,
    model: GroceryModel,
) {
    val open = screen.open
    val settings = screen.settings
    val choosing = screen.choosing
    when {
        settings != null -> ListSettingsScreen(settings, fromList = open != null, model)
        open == null -> ListsHome(screen, screen.lists.map { it.summary() }, screen.loading, screen.error, GROCERY_TEXTS, model) { toBuy(it) }
        choosing != null -> CategoryScreen(screen, choosing, model)
        else -> ListScreen(screen, open, model)
    }
}

// 1.5 l, 2; no trailing zeros
internal fun quantity(item: GroceryItemResponse): String? = quantity(item.amount, item.unit)

internal fun quantity(
    amount: Double?,
    unit: String?,
): String? =
    amount?.let { listOfNotNull(amountText(it), unit).joinToString(" ") }
