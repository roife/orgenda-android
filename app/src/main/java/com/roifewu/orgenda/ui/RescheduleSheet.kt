@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.roifewu.orgenda.core.OrgHeading
import java.time.*

@Composable
fun RescheduleSheet(item: OrgHeading, close: () -> Unit, select: (LocalDate) -> Unit) {
    ModalPresence()
    val date =
        rememberDatePickerState(
            initialSelectedDateMillis =
                (item.scheduled?.date?.toLocalDate() ?: LocalDate.now())
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
        )
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(UiSpacing.edge)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { select(LocalDate.now()) }) { Text(tr("today")) }
                TextButton(shape = MaterialTheme.shapes.medium, onClick = { select(LocalDate.now().plusDays(1)) }) {
                    Text(tr("tomorrow"))
                }
            }
            DatePicker(date, showModeToggle = true)
            Text(
                tr("reschedule_hint"),
                Modifier.padding(vertical = UiSpacing.supportingTextExtraVertical),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(shape = MaterialTheme.shapes.medium, onClick = close) { Text(tr("cancel")) }
                Button(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        date.selectedDateMillis?.let {
                            select(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                    },
                    enabled = date.selectedDateMillis != null,
                ) {
                    Text(tr("save"))
                }
            }
        }
    }
}
