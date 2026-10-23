@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.roifewu.orgenda.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
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

@Composable
internal fun subtleFabElevation() =
    FloatingActionButtonDefaults.elevation(
        defaultElevation = 2.dp,
        pressedElevation = 3.dp,
        focusedElevation = 2.dp,
        hoveredElevation = 3.dp,
    )

private val OrgendaShapes =
    RoundedCornerShape(UiSizes.cornerRadius).let { shape ->
        Shapes(
            extraSmall = shape,
            small = shape,
            medium = shape,
            large = shape,
            extraLarge = shape,
        )
    }

@Composable
fun OrgendaTheme(preferences: LocalPreferences, content: @Composable () -> Unit) {
    val dark = preferences.theme == "dark" || preferences.theme == "system" && isSystemInDarkTheme()
    val colors = orgendaColorScheme(LocalContext.current, dark)
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
    MaterialTheme(colorScheme = colors, shapes = OrgendaShapes, content = content)
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

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
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val appColors = uiColors()
    OutlinedTextField(
        value,
        onChange,
        modifier
            .fillMaxWidth()
            .semantics {
                if (invalid && enabled && !supporting.isNullOrBlank()) error(supporting)
            },
        label = { Text(tr(label)) },
        singleLine = singleLine,
        isError = invalid && enabled,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailing ?:
            if (invalid && enabled) {
                { Icon(Icons.Default.ErrorOutline, null) }
            } else null,
        supportingText = supporting?.let { message ->
            { Text(message, Modifier.padding(vertical = UiSpacing.supportingTextExtraVertical)) }
        },
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = appColors.fieldContainer,
                unfocusedContainerColor = appColors.fieldContainer,
                disabledContainerColor = appColors.disabledContainer,
                errorContainerColor = appColors.fieldErrorContainer,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = appColors.fieldBorder,
                disabledBorderColor = Color.Transparent,
                errorBorderColor = colors.error,
                disabledSupportingTextColor = appColors.disabledContent,
            ),
    )
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
    val appColors = uiColors()
    if (presentation != ChoicePresentation.Row) {
        Column(
            Modifier.fillMaxWidth().testTag("choice.$label"),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.controlGap),
        ) {
            Text(
                tr(label),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) colors.onSurfaceVariant else appColors.disabledMutedContent,
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
                        UiSpacing.edge * 2 + UiSizes.icon + UiSpacing.sm
                val fits =
                    options.isNotEmpty() &&
                        options.size <= 4 &&
                        maxWidth >= itemWidth.coerceAtLeast(UiSizes.segmentedOptionMinWidth) * options.size
                if (presentation == ChoicePresentation.Segmented && fits) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = value == option,
                                onClick = { onChange(option) },
                                enabled = enabled,
                                modifier = Modifier.testTag("choice.$label.$option"),
                                shape =
                                    SegmentedButtonDefaults.itemShape(
                                        index,
                                        options.size,
                                        baseShape = MaterialTheme.shapes.medium,
                                    ),
                                icon = {
                                    Box(
                                        Modifier.size(FilterChipDefaults.IconSize),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (value == option)
                                            Icon(Icons.Default.Check, null, Modifier.fillMaxSize())
                                        else leadingIcon?.invoke(option)
                                    }
                                },
                                colors =
                                    SegmentedButtonDefaults.colors(
                                        inactiveContainerColor = colors.surfaceContainerLow,
                                        inactiveBorderColor =
                                            appColors.choiceBorder,
                                    ),
                                label = { Text(display(option)) },
                            )
                        }
                    }
                } else {
                    FlowRow(
                        Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
                    ) {
                        options.forEach { option ->
                            FilterChip(
                                selected = value == option,
                                onClick = { onChange(option) },
                                enabled = enabled,
                                modifier = Modifier.testTag("choice.$label.$option")
                                    .semantics { role = Role.RadioButton },
                                label = { Text(display(option)) },
                                leadingIcon =
                                    if (value == option) {
                                        {
                                            Icon(
                                                Icons.Default.Check,
                                                null,
                                                Modifier.size(FilterChipDefaults.IconSize),
                                            )
                                        }
                                    } else leadingIcon?.let { { it(option) } },
                                colors =
                                    FilterChipDefaults.filterChipColors(
                                        containerColor = colors.surfaceContainerLow
                                    ),
                                border =
                                    FilterChipDefaults.filterChipBorder(
                                        enabled = enabled,
                                        selected = value == option,
                                        borderColor = appColors.choiceBorder,
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
    if (LocalSettingsLayout.current) {
        SettingsValueRow(
            label, display(value), enabled && options.isNotEmpty(), { expanded = true },
            leadingIcon = leadingIcon?.let { { it(value) } },
        )
    } else {
        Surface(
            onClick = { expanded = true },
            enabled = enabled && options.isNotEmpty(),
            shape = MaterialTheme.shapes.medium,
            color = if (enabled) colors.surfaceContainerLow else appColors.disabledContainer,
            border =
                BorderStroke(
                    UiSizes.border,
                    if (enabled) appColors.fieldBorder else appColors.disabledBorder,
                ),
            modifier = Modifier.fillMaxWidth().testTag("choice.$label"),
        ) {
            Row(
                Modifier.heightIn(min = UiSizes.choiceRowMinHeight)
                    .padding(horizontal = UiSpacing.edge, vertical = UiSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(UiSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val contentColor = if (enabled) colors.onSurface else appColors.disabledContent
                Text(
                    tr(label),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm, Alignment.End),
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
                        color = if (enabled) colors.onSurfaceVariant else appColors.disabledMutedContent,
                    )
                }
                Icon(
                    Icons.Default.ExpandMore,
                    null,
                    tint = contentColor,
                    modifier = Modifier.size(UiSizes.iconMedium),
                )
            }
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
                Modifier.fillMaxWidth().padding(horizontal = UiSpacing.edge),
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
                    Modifier.padding(horizontal = UiSpacing.edge, vertical = UiSpacing.sm)
                        .testTag("choice.search"),
                )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(),
                state = listState,
                contentPadding = PaddingValues(bottom = UiSpacing.xl),
            ) {
                items(matches, key = { it }) { option ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color =
                            if (value == option)
                                uiColors().pickerSelection
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
                                .padding(horizontal = UiSpacing.edge, vertical = UiSpacing.choiceItemVertical),
                            horizontalArrangement = Arrangement.spacedBy(UiSpacing.md),
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
                            Modifier.padding(UiSpacing.edge),
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
            TextButton(shape = MaterialTheme.shapes.medium, onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(tr(confirmLabel))
            }
        },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = onDismiss) { Text(tr("cancel")) } },
    )
}

@Composable
fun SectionTitle(
    key: String,
    modifier: Modifier = Modifier.padding(top = UiSpacing.md, bottom = UiSpacing.xs),
) {
    Text(
        tr(key),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
internal fun ListSectionHeader(title: String, count: Int? = null, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = UiSpacing.edge, vertical = UiSpacing.md).semantics(
                mergeDescendants = true
            ) {
                heading()
            },
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.md),
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
                        Modifier.padding(horizontal = UiSpacing.sm, vertical = UiSpacing.xxs),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
        }
    }
}

@Composable
fun EmptyState(key: String) {
    Box(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UiSpacing.edge, vertical = UiSpacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiSpacing.lg),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(
                    if (key == "no_tasks") Icons.Default.Checklist else Icons.Default.Description,
                    null,
                    Modifier.padding(UiSpacing.sectionInset).size(32.dp),
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
fun WorkflowIcon(name: String, color: Color, modifier: Modifier = Modifier.size(UiSizes.iconSmall)) {
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
        confirmButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = discard) { Text(tr("discard")) } },
        dismissButton = { TextButton(shape = MaterialTheme.shapes.medium, onClick = keepEditing) { Text(tr("continue_editing")) } },
    )
}

@Composable
fun WorkflowIcon(
    item: com.roifewu.orgenda.core.OrgHeading,
    modifier: Modifier = Modifier.size(UiSizes.iconSmall),
) {
    WorkflowIcon(item.workflow, item.state, modifier)
}

@Composable
fun WorkflowIcon(
    workflow: com.roifewu.orgenda.core.Workflow,
    token: String,
    modifier: Modifier = Modifier.size(UiSizes.iconSmall),
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
