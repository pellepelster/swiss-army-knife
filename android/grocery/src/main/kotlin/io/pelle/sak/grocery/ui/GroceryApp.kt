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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.api.model.GroceryItemResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.Screen
import io.pelle.sak.core.ui.Busy
import io.pelle.sak.core.ui.DeploymentScreen
import io.pelle.sak.core.ui.SignInScreen
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListsScreen
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.UNDO_MS
import io.pelle.sak.grocery.amountText

// the screens of the app, one at a time, as the model has it
@Composable
fun GroceryApp(model: GroceryModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    // a check can be undone longer when the phone asks for more time for a control (AN-34)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(accessibility) {
        model.undoMs = accessibility?.calculateRecommendedTimeoutMillis(UNDO_MS, containsIcons = false, containsText = true, containsControls = true)
            ?: UNDO_MS
    }
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
        open == null -> HomeScreen(screen, model)
        choosing != null -> CategoryScreen(screen, choosing, model)
        else -> ListScreen(screen, open, model)
    }
}

@Composable
internal fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
}

// the ⋮ of the menu of a row, which tells a screen reader whose menu it is (AN-34)
@Composable
internal fun MenuIcon(name: String) {
    Icon(painterResource(CoreR.drawable.ic_menu), contentDescription = stringResource(R.string.grocery_menu_of, name))
}

// 1.5 l, 2; no trailing zeros
internal fun quantity(item: GroceryItemResponse): String? = quantity(item.amount, item.unit)

internal fun quantity(
    amount: Double?,
    unit: String?,
): String? =
    amount?.let { listOfNotNull(amountText(it), unit).joinToString(" ") }
