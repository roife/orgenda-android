@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.roifewu.orgenda.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LocalSettingsLayout = staticCompositionLocalOf { false }

// Adjacent preference rows supply their own touch area and separator. Only form fields need gaps.
@Composable
internal fun SettingsGroup(
    title: String? = null,
    list: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiSpacing.xs)) {
            if (title != null) SectionTitle(title, Modifier)
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (list) 0.dp else UiSpacing.sm),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsSheetContent(title: String, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalSettingsLayout provides true) {
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = UiSpacing.edge, top = UiSpacing.xs, end = UiSpacing.edge, bottom = UiSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.md),
        ) {
            Text(tr(title), style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun SettingsActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
        content = content,
    )
}

@Composable
internal fun SettingsValueRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (enabled) colors.onSurface else uiColors().disabledContent
    Column(
        Modifier.fillMaxWidth().testTag("choice.$label")
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    ) {
        SettingsLabeledContent(label) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = colors.surface,
                border = BorderStroke(UiSizes.border, if (enabled) colors.outlineVariant else uiColors().disabledBorder),
            ) {
                Row(
                    Modifier.widthIn(min = 96.dp).padding(horizontal = UiSpacing.md, vertical = UiSpacing.controlGap),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.invoke()
                    Text(
                        value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = contentColor, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(UiSizes.iconMedium), tint = contentColor)
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun SettingsLabeledContent(label: String, content: @Composable () -> Unit) {
    // Large fonts get a full-width control below the label, never a squeezed value column.
    if (LocalDensity.current.fontScale > 1.3f) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = UiSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.xs),
        ) {
            Text(tr(label), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.fillMaxWidth()) { content() }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = UiSizes.touchTarget).padding(vertical = UiSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.width(112.dp)) { content() }
        }
    }
}

@Composable
internal fun SettingsStateRow(
    label: String?,
    value: String,
    terminal: Boolean,
    onClick: () -> Unit,
    add: (() -> Unit)?,
    icon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    val token: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier.heightIn(min = UiSizes.touchTarget).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                modifier = if (largeText) Modifier.fillMaxWidth() else Modifier,
                shape = MaterialTheme.shapes.medium,
                color = if (terminal) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(UiSizes.border, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    Modifier.padding(horizontal = UiSpacing.sm, vertical = UiSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(UiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon()
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = UiSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) Text(tr(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            else Spacer(Modifier.weight(1f))
            if (!largeText) token(Modifier.weight(1.5f))
            if (add != null) {
                IconButton(onClick = add) { Icon(Icons.Default.Add, tr("add_state"), tint = MaterialTheme.colorScheme.primary) }
            } else Spacer(Modifier.width(UiSizes.touchTarget))
            actions()
        }
        if (largeText) Box(Modifier.padding(bottom = UiSpacing.xs)) { token(Modifier.fillMaxWidth()) }
        HorizontalDivider()
    }
}
