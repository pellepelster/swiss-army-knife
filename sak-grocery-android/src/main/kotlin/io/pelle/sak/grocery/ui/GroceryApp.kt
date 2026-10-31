package io.pelle.sak.grocery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.Screen
import io.pelle.sak.grocery.api.GroceryItemResponse
import java.math.BigDecimal

// the screens of the app, one at a time, as the model has it
@Composable
fun GroceryApp(model: GroceryModel) {
    val screen by model.screen.collectAsStateWithLifecycle()
    when (val current = screen) {
        Screen.Starting -> Busy()
        is Screen.Deployment -> DeploymentScreen(current, model::connect)
        is Screen.SignIn -> SignInScreen(current, model)
        is Screen.Lists -> ListsScreen(current, model)
    }
}

@Composable
private fun Busy() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(
    title: String,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, actions = { actions() }) }, content = content)
}

@Composable
private fun Form(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .padding(padding)
            .padding(16.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Error(text: String?) {
    text?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("error")) }
}

@Composable
private fun DeploymentScreen(
    screen: Screen.Deployment,
    connect: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(screen.input) }
    Page("SAK Grocery") { padding ->
        Form(padding) {
            Text("Enter the base domain of your SAK deployment, the one the web app is at as sak.<base domain>.")
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Base domain") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Error(screen.error)
            Button(onClick = { connect(input) }, enabled = input.isNotBlank() && !screen.busy) {
                Text(if (screen.busy) "Connecting…" else "Connect")
            }
        }
    }
}

@Composable
private fun SignInScreen(
    screen: Screen.SignIn,
    model: GroceryModel,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Page(screen.deployment, actions = { TextButton(onClick = { model.changeDeployment() }) { Text("Other deployment") } }) { padding ->
        Form(padding) {
            Text("Sign in", style = MaterialTheme.typography.headlineSmall)
            Error(screen.error)
            when {
                screen.linkSentTo != null ->
                    Text(
                        "A login link is on its way to ${screen.linkSentTo} if the address may sign in. Open it on this phone " +
                            "and tap \"Open in the app\".",
                        modifier = Modifier.testTag("link-sent"),
                    )
                screen.email -> {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("E-mail address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { model.requestLink(email) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { model.requestLink(email) }, enabled = email.isNotBlank() && !screen.busy) {
                        Text("Send login link")
                    }
                }
            }
            if (screen.password) {
                if (screen.email) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Admin password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { model.signInWithPassword(password) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { model.signInWithPassword(password) }, enabled = password.isNotEmpty() && !screen.busy) {
                    Text("Sign in with password")
                }
            }
            if (screen.busy) CircularProgressIndicator()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListsScreen(
    screen: Screen.Lists,
    model: GroceryModel,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Page(
        screen.deployment,
        actions = {
            IconButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Signed in as ${screen.user}") }, onClick = {}, enabled = false)
                DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                    menu = false
                    model.signOut()
                })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (screen.lists.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = screen.selected) {
                    screen.lists.forEachIndexed { index, list ->
                        Tab(
                            selected = index == screen.selected,
                            onClick = { model.select(index) },
                            text = { Text(if (list.shared) "${list.name} · shared" else list.name) },
                        )
                    }
                }
            } else {
                screen.lists.firstOrNull()?.let {
                    Text(it.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                }
            }
            PullToRefreshBox(isRefreshing = screen.loading, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("items")) {
                    screen.error?.let { item { Box(Modifier.padding(16.dp)) { Error(it) } } }
                    if (!screen.loading && screen.error == null && screen.groups.isEmpty()) {
                        item {
                            Text(
                                if (screen.lists.isEmpty()) "You have no list yet, add one in the web app." else "Nothing to buy.",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    screen.groups.forEach { group ->
                        item(key = "group-${group.label}") {
                            Text(
                                group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(group.items, key = { it.id }) { item -> ItemRow(item) }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: GroceryItemResponse) {
    ListItem(
        headlineContent = { Text(item.name) },
        trailingContent = quantity(item)?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        modifier = Modifier.testTag("item-${item.id}"),
    )
}

// 1.5 l, 2; no trailing zeros
internal fun quantity(item: GroceryItemResponse): String? =
    item.amount?.let { amount ->
        val number = BigDecimal(amount.toString()).stripTrailingZeros().toPlainString()
        listOfNotNull(number, item.unit).joinToString(" ")
    }
