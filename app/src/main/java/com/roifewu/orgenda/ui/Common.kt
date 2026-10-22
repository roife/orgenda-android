@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.roifewu.orgenda.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.roifewu.orgenda.LocalPreferences
import com.roifewu.orgenda.R

fun Context.label(key: String): String {
    if (!key.matches(Regex("[a-z][a-z0-9_]*"))) return key
    val id = resources.getIdentifier(key, "string", packageName)
    return if (id != 0) getString(id) else key
}
@Composable fun tr(key: String): String = LocalContext.current.label(key)

@Composable fun OrgendaTheme(preferences: LocalPreferences, content: @Composable () -> Unit) {
    val dark = preferences.theme == "dark" || preferences.theme == "system" && isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, supporting: String? = null, invalid: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(tr(label)) }, singleLine = singleLine, isError = invalid, enabled = enabled,
        supportingText = supporting?.let { { Text(it) } })
}
@Composable fun Choice(value: String, choices: List<String>, label: String, onChange: (String) -> Unit, enabled: Boolean = true, labels: ((String) -> String)? = null) {
    val context = LocalContext.current
    val display = labels ?: { choice: String -> context.label(if (choice == "default") "choice_default" else choice) }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it && enabled }) {
        OutlinedTextField(value = display(value), onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(tr(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { choice -> DropdownMenuItem(text = { Text(display(choice)) }, enabled = enabled, onClick = { expanded = false; onChange(choice) }) }
        }
    }
}
@Composable fun TextPrompt(title: String, initial: String = "", label: String = "path", multiline: Boolean = false, confirmLabel: String = "save", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    ModalPresence()
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr(title)) }, text = { androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { Field(value, { value = it }, label, singleLine = !multiline) } },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(tr(confirmLabel)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } })
}
@Composable fun SectionTitle(key: String) { Text(tr(key), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
@Composable fun EmptyState(key: String) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(tr(key), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun workflowColor(name: String): Color {
    val base = when (name) {
    "gray" -> Color.Gray; "red" -> Color(0xffc34b45); "orange" -> Color(0xffb56417); "amber" -> Color(0xff977200)
    "green" -> Color(0xff348253); "cyan" -> Color(0xff087f8c); "blue" -> Color(0xff3975c2); "purple" -> Color(0xff8851b5); "pink" -> Color(0xffb8548b)
    else -> MaterialTheme.colorScheme.primary
    }
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val target = if (androidx.core.graphics.ColorUtils.calculateLuminance(background) < 0.5) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    var value = base.toArgb()
    for (step in 0..20) {
        value = androidx.core.graphics.ColorUtils.blendARGB(base.toArgb(), target, step / 20f)
        if (androidx.core.graphics.ColorUtils.calculateContrast(value, background) >= 4.5) break
    }
    return Color(value)
}
@Composable fun WorkflowIcon(name: String, color: Color) {
    val icon = when (name) {
        "circle" -> Icons.Default.RadioButtonUnchecked; "arrow" -> Icons.Default.ArrowForward
        "hourglass" -> Icons.Default.HourglassEmpty; "pause" -> Icons.Default.Pause; "moon" -> Icons.Default.DarkMode
        "exclamation" -> Icons.Default.PriorityHigh; "flag" -> Icons.Default.Flag; "star" -> Icons.Default.Star
        "check" -> Icons.Default.Check; "cross" -> Icons.Default.Close; else -> null
    }
    if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = color)
}

@Composable fun DiscardDialog(keepEditing: () -> Unit, discard: () -> Unit) {
    ModalPresence()
    AlertDialog(onDismissRequest = keepEditing, title = { Text(tr("discard_changes")) },
        confirmButton = { TextButton(onClick = discard) { Text(tr("discard")) } }, dismissButton = { TextButton(onClick = keepEditing) { Text(tr("continue_editing")) } })
}

@Composable fun workflowColor(item: com.roifewu.orgenda.core.OrgHeading): Color {
    val explicit = item.workflow.style(item.state).color
    if (explicit != "default") return workflowColor(explicit)
    return workflowColor(when (item.state) { "NEXT" -> "blue"; "WAIT" -> "orange"; "SOMEDAY" -> "purple"; "URGENT" -> "red"; "CANCELED" -> "pink"; else -> "gray" })
}
@Composable fun WorkflowIcon(item: com.roifewu.orgenda.core.OrgHeading) {
    val explicit = item.workflow.style(item.state).icon
    WorkflowIcon(if (explicit != "default") explicit else when (item.state) { "NEXT" -> "arrow"; "WAIT" -> "hourglass"; "SOMEDAY" -> "moon"; "URGENT" -> "exclamation"; "CANCELED" -> "cross"; else -> if (item.done) "check" else "circle" }, workflowColor(item))
}

// Root shortcuts wait for every visible editor/dialog, including sheets owned by child screens.
internal val LocalModalCount = compositionLocalOf<MutableState<Int>?> { null }

@Composable fun ModalPresence() {
    val count = LocalModalCount.current
    DisposableEffect(count) {
        count?.let { it.value += 1 }
        onDispose { count?.let { it.value -= 1 } }
    }
}

@Composable fun rememberHeadingSelection(model: com.roifewu.orgenda.OrgendaViewModel): MutableState<com.roifewu.orgenda.core.OrgHeading?> {
    val saver = remember(model) {
        Saver<com.roifewu.orgenda.core.OrgHeading?, List<String>>(
            save = { item -> item?.let { listOf(model.workspace.value.workspace?.id.orEmpty(), it.path, it.start.toString(), it.sourceHash) } },
            restore = { saved ->
                model.workspace.value.takeIf { it.workspace?.id == saved[0] }?.documents?.get(saved[1])?.headings
                    ?.firstOrNull { it.start.toString() == saved[2] && it.sourceHash == saved[3] }
            }
        )
    }
    return rememberSaveable(stateSaver = saver) { mutableStateOf(null) }
}
