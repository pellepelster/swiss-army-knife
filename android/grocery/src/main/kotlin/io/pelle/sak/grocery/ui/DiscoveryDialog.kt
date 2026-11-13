package io.pelle.sak.grocery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.pelle.sak.core.R as CoreR
import io.pelle.sak.core.ui.Error
import io.pelle.sak.grocery.Amount
import io.pelle.sak.grocery.DiscoveredRow
import io.pelle.sak.grocery.Discovery
import io.pelle.sak.grocery.GroceryModel
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.amountOf

// the items the AI read in a photo (GROCERY-16): a dialog while it is sent and read (Cancel drops
// it), then the items to correct on the whole screen; Add adds the ones checked, Cancel and back none
@Composable
internal fun DiscoveryDialog(
    discovery: Discovery,
    model: GroceryModel,
) {
    when {
        discovery.reading ->
            AlertDialog(
                onDismissRequest = model::dismissDiscovery,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("discover-reading")) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.grocery_discover_reading))
                    }
                },
                confirmButton = { TextButton(onClick = model::dismissDiscovery) { Text(stringResource(R.string.grocery_cancel)) } },
            )
        discovery.rows.isEmpty() ->
            AlertDialog(
                onDismissRequest = model::dismissDiscovery,
                title = { Text(stringResource(R.string.grocery_discover_title)) },
                text = { Text(stringResource(R.string.grocery_discover_none), modifier = Modifier.testTag("discover-none")) },
                confirmButton = { TextButton(onClick = model::dismissDiscovery) { Text(stringResource(CoreR.string.ok)) } },
            )
        else -> DiscoveredItems(discovery, model)
    }
}

// the items of the photo on the whole screen, a row for each; Add is disabled while no row checked is
// valid, a row checked with an error shows it once Add was pressed
@Composable
private fun DiscoveredItems(
    discovery: Discovery,
    model: GroceryModel,
) {
    Dialog(onDismissRequest = model::dismissDiscovery, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("discovery")) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Text(
                    stringResource(R.string.grocery_discover_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                )
                Text(
                    stringResource(R.string.grocery_discover_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                discovery.error?.let { Box(Modifier.padding(horizontal = 24.dp)) { Error(it) } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("discovered-items")) {
                    itemsIndexed(discovery.rows, key = { _, row -> row.id }) { index, row ->
                        DiscoveredItem(index, row, discovery.submitted, !discovery.adding, model)
                        if (index < discovery.rows.lastIndex) HorizontalDivider()
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (discovery.adding) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    TextButton(onClick = model::dismissDiscovery, enabled = !discovery.adding) { Text(stringResource(R.string.grocery_cancel)) }
                    TextButton(
                        onClick = model::addDiscovered,
                        enabled = !discovery.adding && discovery.rows.any { it.included && it.valid },
                        modifier = Modifier.testTag("discover-add"),
                    ) {
                        Text(stringResource(R.string.grocery_discover_add))
                    }
                }
            }
        }
    }
}

// one item of the photo (GROCERY-15 for its quantity); the errors of a row left out do not show
@Composable
private fun DiscoveredItem(
    index: Int,
    row: DiscoveredRow,
    submitted: Boolean,
    enabled: Boolean,
    model: GroceryModel,
) {
    val include = stringResource(R.string.grocery_discover_include, row.name.trim())
    val nameError = row.included && (row.nameLeft || submitted) && !GroceryModel.validItemName(row.name)
    val amountError = row.included && (row.amountLeft || submitted) && amountOf(row.amount) == Amount.Invalid
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = row.included,
                onCheckedChange = { model.includeDiscovered(index, it) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = include }.testTag("discover-include-$index"),
            )
            OutlinedTextField(
                value = row.name,
                onValueChange = { model.editDiscoveredName(index, it) },
                label = { Text(stringResource(R.string.grocery_discover_name_of, index + 1)) },
                singleLine = true,
                enabled = enabled,
                isError = nameError,
                supportingText = { if (nameError) Text(stringResource(R.string.grocery_item_name_invalid)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier =
                    Modifier
                        .weight(1f)
                        .onFocusChanged {
                            if (focused && !it.isFocused) model.leaveDiscoveredName(index)
                            focused = it.isFocused
                        }.testTag("discover-name-$index"),
            )
        }
        // under the name, in line with it
        Row(Modifier.padding(start = 48.dp)) {
            AmountField(
                row.amount,
                amountError,
                onAmount = { model.editDiscoveredAmount(index, it) },
                onLeave = { model.leaveDiscoveredAmount(index) },
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag("discover-amount-$index"),
            )
            Spacer(Modifier.width(8.dp))
            UnitField(row.unit, onUnit = { model.chooseDiscoveredUnit(index, it) }, enabled = enabled, modifier = Modifier.weight(1f))
        }
    }
}
