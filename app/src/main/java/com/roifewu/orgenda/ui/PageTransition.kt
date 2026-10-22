package com.roifewu.orgenda.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal val LocalPageActive = compositionLocalOf { true }

@Composable
internal fun <T> PageTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    direction: (T, T) -> Int = { _, _ -> 0 },
    content: @Composable (T) -> Unit,
) {
    val distance = with(LocalDensity.current) { 32.dp.roundToPx() }
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val parentActive = LocalPageActive.current
    AnimatedContent(
        targetState = targetState,
        modifier = modifier.clipToBounds(),
        contentKey = contentKey,
        transitionSpec = {
            val sign = direction(initialState, targetState) * layoutSign
            if (sign == 0) {
                    (fadeIn(tween(150, delayMillis = 90)) +
                        scaleIn(
                            tween(240, easing = FastOutSlowInEasing),
                            initialScale = 0.98f,
                        )) togetherWith fadeOut(tween(90))
                } else {
                    (fadeIn(tween(180, delayMillis = 60)) +
                        slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) {
                            sign * distance
                        }) togetherWith
                        (fadeOut(tween(120)) +
                            slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) {
                                -sign * distance
                            })
                }
                .using(sizeTransform = null)
        },
        label = "page",
    ) { state ->
        val active = parentActive && contentKey(state) == contentKey(targetState)
        CompositionLocalProvider(LocalPageActive provides active) {
            // Keep the outgoing page visible, but let only the current page handle input.
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (active) Modifier
                        else
                            Modifier.clearAndSetSemantics {}
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent(PointerEventPass.Initial)
                                            .changes
                                            .forEach { it.consume() }
                                    }
                                }
                    )
            ) {
                content(state)
            }
        }
    }
}
