package com.tote.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A date, picked rather than typed.
 *
 * The two date inputs in this app — a loan's "back by" and a person's birthdate — were plain text
 * fields expecting `2026-09-30`, on a QWERTY keyboard, with no validation. Anything else reached
 * the server and was rejected; in the lend dialog that rejection was swallowed whole, so a
 * malformed date closed the dialog looking exactly like a successful loan.
 *
 * The field stays read-only and opens the picker on focus: the value it holds is always an ISO
 * date the server accepts, so there is no format to get wrong and no error state to design.
 * Clearing is still possible, because both dates are genuinely optional — a loan with no agreed
 * date is a real loan, and inventing one manufactures an overdue nudge nobody agreed to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        modifier = modifier.onFocusChanged { if (it.isFocused) showPicker = true },
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text("Any date") },
        trailingIcon = {
            if (value.isNotBlank()) {
                TextButton(onClick = { onValueChange("") }) { Text("Clear") }
            } else {
                TextButton(onClick = { showPicker = true }) { Text("Pick") }
            }
        },
    )

    if (showPicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            // The picker speaks UTC millis; the server wants a plain ISO date.
                            // Converted at UTC deliberately — the value is a calendar day, not an
                            // instant, and shifting it into the phone's zone is how a date lands
                            // one day off for anyone west of Greenwich.
                            onValueChange(
                                DateTimeFormatter.ISO_LOCAL_DATE.format(
                                    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                                )
                            )
                        }
                        showPicker = false
                    },
                ) { Text("Use this date") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = state) }
    }
}
