@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.roifewu.orgenda.LocalPreferences

private val labelKey = Regex("[a-z][a-z0-9_]*")

fun Context.label(key: String): String {
    if (!labelKey.matches(key)) return key
    val id = resources.getIdentifier(key, "string", packageName)
    return if (id != 0) getString(id) else key
}

@Composable fun tr(key: String): String = LocalContext.current.label(key)

@Composable internal fun mutedControlColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
fun OrgendaTheme(preferences: LocalPreferences, content: @Composable () -> Unit) {
    val dark = preferences.theme == "dark" || preferences.theme == "system" && isSystemInDarkTheme()
    val colors =
        if (dark) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    val view = LocalView.current
    if (!view.isInEditMode)
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    MaterialTheme(colorScheme = colors, content = content)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private val FormControlShape = RoundedCornerShape(12.dp)

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    supporting: String? = null,
    invalid: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor =
        when {
            !enabled -> Color.Transparent
            invalid -> colors.error
            focused -> colors.primary
            else -> colors.outlineVariant.copy(alpha = 0.5f)
        }
    Column(Modifier.fillMaxWidth()) {
        TextField(
            value,
            onChange,
            modifier
                .fillMaxWidth()
                .border(if (focused && enabled) 1.5.dp else 1.dp, borderColor, FormControlShape),
            label = { Text(tr(label)) },
            singleLine = singleLine,
            isError = invalid,
            enabled = enabled,
            shape = FormControlShape,
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.65f),
                    unfocusedContainerColor = colors.surfaceContainerHigh.copy(alpha = 0.65f),
                    disabledContainerColor = colors.onSurface.copy(alpha = 0.04f),
                    errorContainerColor = colors.errorContainer.copy(alpha = 0.25f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
        )
        supporting?.let {
            Text(
                it,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color =
                    when {
                        !enabled -> colors.onSurface.copy(alpha = 0.38f)
                        invalid -> colors.error
                        else -> colors.onSurfaceVariant
                    },
            )
        }
    }
}

enum class ChoicePresentation {
    Row,
    Segmented,
    Chips,
}

@Composable
fun Choice(
    value: String,
    choices: List<String>,
    label: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    presentation: ChoicePresentation = ChoicePresentation.Row,
    leadingIcon: (@Composable (String) -> Unit)? = null,
    labels: ((String) -> String)? = null,
) {
    val context = LocalContext.current
    val display =
        labels
            ?: { choice: String ->
                context.label(if (choice == "default") "choice_default" else choice)
            }
    val options = choices.distinct()
    val colors = MaterialTheme.colorScheme
    if (presentation != ChoicePresentation.Row) {
        Column(
            Modifier.fillMaxWidth().testTag("choice.$label"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                tr(label),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val measurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.labelLarge
                val longest =
                    options.maxOfOrNull {
                        measurer
                            .measure(display(it), style = textStyle, softWrap = false, maxLines = 1)
                            .size
                            .width
                    } ?: 0
                val itemWidth =
                    with(LocalDensity.current) { longest.toDp() } +
                        if (leadingIcon == null) 32.dp else 56.dp
                val fits =
                    options.isNotEmpty() &&
                        options.size <= 4 &&
                        maxWidth >= itemWidth.coerceAtLeast(64.dp) * options.size
                if (presentation == ChoicePresentation.Segmented && fits) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = value == option,
                                onClick = { onChange(option) },
                                enabled = enabled,
                                modifier = Modifier.testTag("choice.$label.$option"),
                                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                icon = { leadingIcon?.invoke(option) },
                                colors =
                                    SegmentedButtonDefaults.colors(
                                        inactiveContainerColor = colors.surfaceContainerLow,
                                        inactiveBorderColor =
                                            colors.outlineVariant.copy(alpha = 0.6f),
                                    ),
                                label = { Text(display(option)) },
                            )
                        }
                    }
                } else {
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = value == option,
                                onClick = { onChange(option) },
                                enabled = enabled,
                                modifier = Modifier.testTag("choice.$label.$option"),
                                label = { Text(display(option)) },
                                leadingIcon = leadingIcon?.let { { it(option) } },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        containerColor = colors.surfaceContainerLow
                                    ),
                                border =
                                    FilterChipDefaults.filterChipBorder(
                                        enabled = enabled,
                                        selected = value == option,
                                        borderColor = colors.outlineVariant.copy(alpha = 0.6f),
                                    ),
                            )
                        }
                    }
                }
            }
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Surface(
        onClick = { expanded = true },
        enabled = enabled && options.isNotEmpty(),
        shape = FormControlShape,
        color = if (enabled) colors.surfaceContainerLow else colors.onSurface.copy(alpha = 0.04f),
        border =
            BorderStroke(1.dp, colors.outlineVariant.copy(alpha = if (enabled) 0.5f else 0.2f)),
        modifier = Modifier.fillMaxWidth().testTag("choice.$label"),
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val contentColor = colors.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
            Text(
                tr(label),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke(value)
                Text(
                    display(value),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
            Icon(
                Icons.Default.ExpandMore,
                null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (expanded && enabled && options.isNotEmpty()) {
        ChoiceSheet(value, options, label, display, leadingIcon, { expanded = false }) { option ->
            expanded = false
            onChange(option)
        }
    }
}

@Composable
private fun ChoiceSheet(
    value: String,
    choices: List<String>,
    label: String,
    display: (String) -> String,
    leadingIcon: (@Composable (String) -> Unit)?,
    close: () -> Unit,
    onChange: (String) -> Unit,
) {
    ModalPresence()
    var query by rememberSaveable { mutableStateOf("") }
    val matches = choices.filter {
        query.isBlank() ||
            display(it).contains(query.trim(), ignoreCase = true) ||
            it.contains(query.trim(), ignoreCase = true)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query) {
        listState.scrollToItem(if (query.isBlank()) choices.indexOf(value).coerceAtLeast(0) else 0)
    }
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("choice.sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.75f).dp)
                .imePadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr(label),
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = close) { Icon(Icons.Default.Close, tr("close")) }
            }
            if (choices.size > 8)
                Field(
                    query,
                    { query = it },
                    "search",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("choice.search"),
                )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                items(matches, key = { it }) { option ->
                    Surface(
                        shape = FormControlShape,
                        color =
                            if (value == option)
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            else Color.Transparent,
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = value == option,
                                    role = Role.RadioButton,
                                    onClick = { onChange(option) },
                                )
                                .testTag("choice.option.$option")
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            leadingIcon?.invoke(option)
                            Text(
                                display(option),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            RadioButton(selected = value == option, onClick = null)
                        }
                    }
                }
                if (matches.isEmpty())
                    item {
                        Text(
                            tr("no_results"),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
}

@Composable
fun TextPrompt(
    title: String,
    initial: String = "",
    label: String = "path",
    multiline: Boolean = false,
    confirmLabel: String = "save",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    ModalPresence()
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(title)) },
        text = {
            androidx.compose.foundation.layout.Column(
                Modifier.verticalScroll(rememberScrollState())
            ) {
                Field(value, { value = it }, label, singleLine = !multiline)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(tr(confirmLabel))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("cancel")) } },
    )
}

@Composable
fun SectionTitle(key: String) {
    Text(
        tr(key),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun ListSectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics(
                mergeDescendants = true
            ) {
                heading()
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (count != null)
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        count.toString(),
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
        }
    }
}

@Composable
fun EmptyState(key: String) {
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    if (key == "no_tasks") Icons.Default.Checklist else Icons.Default.Description,
                    null,
                    Modifier.padding(20.dp).size(32.dp),
                )
            }
            Text(
                tr(key),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun workflowColor(name: String): Color {
    val base =
        when (name) {
            "gray" -> Color.Gray
            "red" -> Color(0xffc34b45)
            "orange" -> Color(0xffb56417)
            "amber" -> Color(0xff977200)
            "green" -> Color(0xff348253)
            "cyan" -> Color(0xff087f8c)
            "blue" -> Color(0xff3975c2)
            "purple" -> Color(0xff8851b5)
            "pink" -> Color(0xffb8548b)
            else -> MaterialTheme.colorScheme.primary
        }
    val background = MaterialTheme.colorScheme.surface.toArgb()
    val target =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(background) < 0.5)
            android.graphics.Color.WHITE
        else android.graphics.Color.BLACK
    var value = base.toArgb()
    for (step in 0..20) {
        value = androidx.core.graphics.ColorUtils.blendARGB(base.toArgb(), target, step / 20f)
        if (androidx.core.graphics.ColorUtils.calculateContrast(value, background) >= 4.5) break
    }
    return Color(value)
}

@Composable
fun WorkflowIcon(name: String, color: Color, modifier: Modifier = Modifier.size(16.dp)) {
    val icon =
        when (name) {
            "circle" -> Icons.Default.RadioButtonUnchecked
            "arrow" -> Icons.Default.ArrowForward
            "hourglass" -> Icons.Default.HourglassEmpty
            "pause" -> Icons.Default.Pause
            "moon" -> Icons.Default.DarkMode
            "exclamation" -> Icons.Default.PriorityHigh
            "flag" -> Icons.Default.Flag
            "star" -> Icons.Default.Star
            "check" -> Icons.Default.Check
            "cross" -> Icons.Default.Close
            else -> null
        }
    if (icon != null) Icon(icon, null, modifier, tint = color)
}

@Composable
fun DiscardDialog(keepEditing: () -> Unit, discard: () -> Unit) {
    ModalPresence()
    AlertDialog(
        onDismissRequest = keepEditing,
        title = { Text(tr("discard_changes")) },
        confirmButton = { TextButton(onClick = discard) { Text(tr("discard")) } },
        dismissButton = { TextButton(onClick = keepEditing) { Text(tr("continue_editing")) } },
    )
}

@Composable
fun workflowColor(item: com.roifewu.orgenda.core.OrgHeading): Color {
    return workflowColor(item.workflow, item.state)
}

@Composable
fun workflowColor(workflow: com.roifewu.orgenda.core.Workflow, token: String): Color {
    val explicit = workflow.style(token).color
    if (explicit != "default") return workflowColor(explicit)
    return workflowColor(
        when (token) {
            "NEXT" -> "blue"
            "WAIT" -> "orange"
            "SOMEDAY" -> "purple"
            "URGENT" -> "red"
            "CANCELED" -> "pink"
            else -> "gray"
        }
    )
}

@Composable
fun WorkflowIcon(
    item: com.roifewu.orgenda.core.OrgHeading,
    modifier: Modifier = Modifier.size(16.dp),
) {
    WorkflowIcon(item.workflow, item.state, modifier)
}

@Composable
fun WorkflowIcon(
    workflow: com.roifewu.orgenda.core.Workflow,
    token: String,
    modifier: Modifier = Modifier.size(16.dp),
) {
    val style = workflow.style(token)
    WorkflowIcon(
        if (style.icon == "default")
            when (token) {
                "NEXT" -> "arrow"
                "WAIT" -> "hourglass"
                "SOMEDAY" -> "moon"
                "URGENT" -> "exclamation"
                "CANCELED" -> "cross"
                else -> if (workflow.terminal(token)) "check" else "circle"
            }
        else style.icon,
        workflowColor(workflow, token),
        modifier,
    )
}

// Root shortcuts wait for every visible editor/dialog, including sheets owned by child screens.
internal val LocalModalCount = compositionLocalOf<MutableState<Int>?> { null }

@Composable
fun ModalPresence() {
    val count = LocalModalCount.current
    DisposableEffect(count) {
        count?.let { it.value += 1 }
        onDispose { count?.let { it.value -= 1 } }
    }
}

@Composable
fun rememberHeadingSelection(
    model: com.roifewu.orgenda.OrgendaViewModel
): MutableState<com.roifewu.orgenda.core.OrgHeading?> {
    val saver =
        remember(model) {
            Saver<com.roifewu.orgenda.core.OrgHeading?, List<String>>(
                save = { item ->
                    item?.let {
                        listOf(
                            model.workspace.value.workspace?.id.orEmpty(),
                            it.path,
                            it.start.toString(),
                            it.sourceHash,
                        )
                    }
                },
                restore = { saved ->
                    model.workspace.value
                        .takeIf { it.workspace?.id == saved[0] }
                        ?.documents
                        ?.get(saved[1])
                        ?.headings
                        ?.firstOrNull {
                            it.start.toString() == saved[2] && it.sourceHash == saved[3]
                        }
                },
            )
        }
    return rememberSaveable(stateSaver = saver) { mutableStateOf(null) }
}
