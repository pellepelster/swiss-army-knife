package io.pelle.sak.grocery.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.pelle.sak.grocery.R
import io.pelle.sak.grocery.UNITS

// the fields of a quantity (GROCERY-15), of the dialog of the menu of an item and of the items of a
// photo (GROCERY-16)

// error shows once the field was left (onLeave) or the quantity was to be saved (UX-6)
@Composable
internal fun AmountField(
    amount: String,
    error: Boolean,
    onAmount: (String) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = amount,
        onValueChange = onAmount,
        label = { Text(stringResource(R.string.grocery_quantity_amount)) },
        singleLine = true,
        enabled = enabled,
        isError = error,
        supportingText = { if (error) Text(stringResource(R.string.grocery_quantity_invalid)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier =
            modifier.onFocusChanged {
                if (focused && !it.isFocused) onLeave()
                focused = it.isFocused
            },
    )
}

// a unit that is none of UNITS, one the item had or the AI read (e.g. Zehe), stays one to choose,
// also once another one was chosen
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UnitField(
    unit: String?,
    onUnit: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val labels = UNITS.map { stringResource(it) }
    val first = remember { unit }
    val choices = labels + listOfNotNull(first, unit).distinct().filter { it !in labels }
    val none = stringResource(R.string.grocery_quantity_no_unit)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it && enabled }, modifier = modifier) {
        OutlinedTextField(
            value = unit ?: none,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.grocery_quantity_unit)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            // the space of an error under the amount next to it, so both fields stay in line
            supportingText = {},
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(none) },
                onClick = {
                    expanded = false
                    onUnit(null)
                },
                modifier = Modifier.testTag("unit-none"),
            )
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        expanded = false
                        onUnit(choice)
                    },
                    modifier = Modifier.testTag("unit-$choice"),
                )
            }
        }
    }
}
