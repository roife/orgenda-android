package com.roifewu.orgenda.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal data class SwipeAction(
    val tag: String,
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val invoke: () -> Unit,
)

private enum class SwipePosition {
    CLOSED,
    LEADING,
    TRAILING,
    FULL,
}

internal class SwipeActionsCoordinator {
    var active by mutableStateOf<Any?>(null)
}

internal val LocalSwipeActionsCoordinator = compositionLocalOf<SwipeActionsCoordinator?> { null }

// Action anchors keep the row in place. Only an explicitly enabled leading action
// gets a full-swipe anchor; file deletion and trailing actions always require a tap.
@Composable
internal fun SwipeActionsRow(
    leading: List<SwipeAction>,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    fullSwipeLeading: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state = remember { AnchoredDraggableState(SwipePosition.CLOSED) }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    var released by remember { mutableStateOf(false) }
    val coordinator = LocalSwipeActionsCoordinator.current
    val token = remember { Any() }
    val scope = rememberCoroutineScope()
    val latestLeading by rememberUpdatedState(leading)
    val actionPixels = with(LocalDensity.current) { (88.dp * fontScale.coerceAtLeast(1f)).toPx() }
    val actionHeight =
        48.dp +
            with(LocalDensity.current) { MaterialTheme.typography.labelMedium.lineHeight.toDp() }
    var width by remember { mutableIntStateOf(0) }
    val leadingWidth = (leading.size * actionPixels).coerceAtMost(width * 0.8f)
    val trailingWidth = (trailing.size * actionPixels).coerceAtMost(width * 0.8f)
    val anchors =
        remember(width, leading.size, trailing.size, fullSwipeLeading, actionPixels) {
            DraggableAnchors {
                SwipePosition.CLOSED at 0f
                if (width > 0 && leading.isNotEmpty()) {
                    SwipePosition.LEADING at leadingWidth
                    if (fullSwipeLeading && leading.size == 1) SwipePosition.FULL at width.toFloat()
                }
                if (width > 0 && trailing.isNotEmpty()) SwipePosition.TRAILING at -trailingWidth
            }
        }
    SideEffect { state.updateAnchors(anchors) }
    val fling =
        remember(state) {
            object : FlingBehavior {
                override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                    // A quick, short flick may reveal a button, but cannot complete a task.
                    val destination =
                        state.anchors.closestAnchor(state.requireOffset()) ?: return 0f
                    val distance = state.anchors.positionOf(destination) - state.requireOffset()
                    var previous = 0f
                    animate(0f, distance, animationSpec = tween(180)) { value, _ ->
                        scrollBy(value - previous)
                        previous = value
                    }
                    return 0f
                }
            }
        }
    fun close() {
        scope.launch { state.animateTo(SwipePosition.CLOSED, tween(180)) }
    }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            when (event) {
                is DragInteraction.Start -> released = false
                is DragInteraction.Stop -> released = true
                is DragInteraction.Cancel -> {
                    released = false
                    state.snapTo(SwipePosition.CLOSED)
                }
            }
        }
    }
    LaunchedEffect(dragging) { if (dragging) coordinator?.active = token }
    LaunchedEffect(coordinator?.active) {
        if (coordinator != null && coordinator.active !== token)
            state.animateTo(SwipePosition.CLOSED, tween(180))
    }
    LaunchedEffect(state.settledValue, released) {
        if (state.settledValue == SwipePosition.FULL && released) {
            released = false
            state.snapTo(SwipePosition.CLOSED)
            latestLeading.singleOrNull()?.invoke?.invoke()
        }
    }
    DisposableEffect(coordinator) {
        onDispose { if (coordinator?.active === token) coordinator.active = null }
    }
    val open = state.settledValue != SwipePosition.CLOSED
    val closeLabel = tr("close")
    BackHandler(open && LocalPageActive.current) { close() }
    val dismissOnScroll =
        if (open)
            Modifier.pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val distance = change.position - down.position
                        if (
                            abs(distance.y) > viewConfiguration.touchSlop &&
                                abs(distance.y) > abs(distance.x)
                        ) {
                            close()
                            break
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
        else Modifier
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .clipToBounds()
            .then(dismissOnScroll)
            .anchoredDraggable(
                state,
                Orientation.Horizontal,
                interactionSource = interaction,
                flingBehavior = fling,
            )
    ) {
        val offset = state.offset.takeUnless { it.isNaN() } ?: 0f
        val actions = if (offset > 0f) leading else trailing
        if (offset != 0f && actions.isNotEmpty()) {
            Row(
                Modifier.matchParentSize(),
                horizontalArrangement = if (offset > 0f) Arrangement.Start else Arrangement.End,
            ) {
                actions.forEach { action ->
                    Surface(
                        color =
                            if (action.destructive) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                        modifier =
                            Modifier.width(
                                    with(LocalDensity.current) {
                                        ((if (offset > 0f) maxOf(leadingWidth, offset)
                                            else trailingWidth) / actions.size)
                                            .toDp()
                                    }
                                )
                                .fillMaxHeight(),
                    ) {
                        Column(
                            Modifier.testTag(action.tag)
                                .clickable(role = Role.Button) {
                                    scope.launch {
                                        state.snapTo(SwipePosition.CLOSED)
                                        action.invoke()
                                    }
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(action.icon, null)
                            Text(
                                action.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.heightIn(min = actionHeight).offset { IntOffset(offset.roundToInt(), 0) },
            propagateMinConstraints = true,
        ) {
            Box(
                if (open) Modifier.clearAndSetSemantics {} else Modifier,
                propagateMinConstraints = true,
            ) {
                content()
            }
            if (open)
                Box(
                    Modifier.matchParentSize()
                        .semantics { contentDescription = closeLabel }
                        .clickable(role = Role.Button, onClickLabel = closeLabel) { close() }
                )
        }
    }
}
