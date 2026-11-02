package io.pelle.sak.grocery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pelle.sak.api.model.GroceryListResponse
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.AccountMenu
import io.pelle.sak.core.ui.ConfirmDelete
import io.pelle.sak.core.ui.Error
import io.pelle.sak.core.ui.Page
import io.pelle.sak.core.ui.UpdateBanner
import io.pelle.sak.core.ui.UpdateNotice
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.ListsScreen
import io.pelle.sak.grocery.R

// all lists of the user, the own and the shared ones of the others, each with how many items are to
// buy; a tap opens one, the plus at the top creates one
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    screen: ListsScreen,
    model: GroceryModel,
) {
    Page(
        screen.deployment,
        actions = {
            IconButton(onClick = model::newList, modifier = Modifier.testTag("new-list")) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.grocery_form_create))
            }
            AccountMenu(screen, model, home = true)
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = screen.loading,
            onRefresh = model::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("lists")) {
                screen.update?.let { item { UpdateBanner(it, model::update) } }
                screen.updateNotice?.let { item { UpdateNotice(it, model::dismissUpdateNotice) } }
                screen.error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                if (!screen.loading && screen.error == null && screen.lists.isEmpty()) {
                    item { Empty(stringResource(R.string.grocery_no_lists)) }
                }
                val last = screen.lists.count { it.mine } == 1
                itemsIndexed(screen.lists, key = { _, list -> list.id }) { index, list ->
                    ListRow(
                        list,
                        deletable = list.mine && !last,
                        open = { model.open(list.id) },
                        settings = { model.openSettings(list.id) },
                        delete = { model.deleteList(list.id) },
                    )
                    if (index < screen.lists.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

// a list on the home: its icon, its name, how many items are to buy and whether it is shared; the
// menu of its row leads to its settings and deletes it after a confirmation (UX-12, UX-4.1), both only
// of a list of the user's own and the delete not of their last one (UX-8, LIST-1)
@Composable
private fun ListRow(
    list: GroceryListResponse,
    deletable: Boolean,
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
                Text(toBuy(list.items))
                if (list.shared) Text(stringResource(R.string.grocery_shared_badge), color = MaterialTheme.colorScheme.primary)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-${list.id}")) {
                    MenuIcon(list.name)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.shared_settings)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                        onClick = {
                            menu = false
                            settings()
                        },
                        modifier = Modifier.testTag("settings-${list.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.shared_delete)) },
                        onClick = {
                            menu = false
                            confirming = true
                        },
                        enabled = deletable,
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

// the mark of a list: its emoji, or the first letter of its name in a circle
@Composable
internal fun ListIcon(
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

// No items to buy, 1 item to buy, 3 items to buy
@Composable
private fun toBuy(items: Int) =
    if (items == 0) stringResource(R.string.grocery_lists_to_buy_none) else pluralStringResource(R.plurals.grocery_to_buy, items, items)
