package com.roifewu.orgenda.ui

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.groupParagraphs
import com.roifewu.orgenda.data.FileEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun habitHistoryFollowsTheHeadingAndPrecedesDrawers() = checkHabitLayout(1f)

    @Test fun wrappedHabitHeadingAndLargeTextDoNotOverlapHistory() = checkHabitLayout(2f)

    private fun checkHabitLayout(fontScale: Float) {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val source =
            "文件正文\n\n* TODO [#A] 中文😀 café 习惯任务标题换行验证 :work:test:\nSCHEDULED: <2026-10-23 Fri 09:00-10:00 +1d -2d> DEADLINE: <2026-10-24 Sat>\n:PROPERTIES:\n:STYLE: habit\n:END:\n:LOGBOOK:\n- State \"DONE\" from \"TODO\" [2026-10-21 Wed 10:00]\n:END:\n保留行尾空格和未知语法。\n\n- [ ] 原生交互复选框\n- [X] 保留 é 和 emoji 👩‍💻\n** 普通子标题\n子标题正文\n*** 重复事件\nSCHEDULED: <2026-10-25 Sun 09:00 +1w>\n重复事件正文\n*********** TODO 深层任务\n深层正文\n"
        val document = model.repository.engine.parse("preview-layout.org", source)
        val state =
            EditorState(FileEntity("ui-only", "ui-only", document.path), source, null, document)
        val today = LocalDate.of(2026, 10, 22)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var opened = false
        try {
            val habit = document.headings.first()
            assertTrue(habit.habit)
            compose.setContent {
                val configuration =
                    Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
                val scaledContext = context.createConfigurationContext(configuration)
                val density = LocalDensity.current.density
                MaterialTheme {
                    CompositionLocalProvider(
                        LocalContext provides scaledContext,
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(density, fontScale),
                        LocalToday provides TodayState(today, ZoneId.of("Asia/Taipei")),
                    ) {
                        OrgPreview(model, state, { opened = true }, {})
                    }
                }
            }
            compose.onNodeWithTag("preview.heading.title.${habit.start}").performScrollTo()
            val heading =
                compose
                    .onNode(
                        isHeading() and hasAnyAncestor(hasTestTag("preview.block.${habit.start}")),
                        useUnmergedTree = true,
                    )
                    .fetchSemanticsNode()
                    .boundsInRoot
            val title =
                compose
                    .onNodeWithTag("preview.heading.title.${habit.start}")
                    .fetchSemanticsNode()
                    .boundsInRoot
            val history =
                compose
                    .onNodeWithText(context.label("habit_history"))
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue(
                "Habit history overlaps its heading: heading=$heading, history=$history",
                history.top >= heading.bottom,
            )
            assertEquals(
                "Habit history does not align with the heading text",
                title.left,
                history.left,
                1f,
            )
            val firstDay =
                compose
                    .onNodeWithContentDescription("${today.minusDays(27)}: ", substring = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            val lastDay =
                compose
                    .onNodeWithContentDescription("$today: ", substring = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("History label overlaps its grid", firstDay.top >= history.bottom)
            compose.onNodeWithText("PROPERTIES").performScrollTo()
            val properties = compose.onNodeWithText("PROPERTIES").fetchSemanticsNode().boundsInRoot
            val visibleLastDay =
                compose
                    .onNodeWithContentDescription("$today: ", substring = true)
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("Properties overlap habit history", properties.top >= visibleLastDay.bottom)
            assertTrue("Habit grid has no height", lastDay.bottom > firstDay.top)
            compose.onNodeWithText("PROPERTIES").performClick()
            compose.onNodeWithText("STYLE").assertIsDisplayed()
            compose.onNodeWithText("PROPERTIES").performClick()
            compose.onNodeWithTag("preview.heading.title.${habit.start}").performScrollTo()
            compose.onNodeWithText("A").performClick()
            compose.runOnIdle { assertTrue("Heading cannot be opened", opened) }
            val syntax = document.syntax.groupParagraphs()
            val blocks =
                syntax.blocks
                    .filterNot { it.type == "blank_line" }
                    .flatMap {
                        if (it.type == "list")
                            syntax.children(it).filter { child -> child.type == "list_item" }
                        else listOf(it)
                    }
            document.headings.forEach { item ->
                scrollToBlock(item.start)
                val titleLeft =
                    compose
                        .onNodeWithTag("preview.heading.title.${item.start}")
                        .getUnclippedBoundsInRoot()
                        .left
                        .value
                blocks
                    .filter { it.start >= item.end && it.start < item.bodyEnd }
                    .forEach { block ->
                        val bounds = scrollToBlock(block.start).getUnclippedBoundsInRoot()
                        assertEquals(
                            "${block.type} does not align with ${item.title}",
                            titleLeft,
                            bounds.left.value,
                            1f,
                        )
                    }
            }
            val preamble = scrollToBlock(0).getUnclippedBoundsInRoot()
            val topHeading = scrollToBlock(habit.start).getUnclippedBoundsInRoot()
            assertEquals(
                "Content outside a heading acquired a heading inset",
                topHeading.left.value,
                preamble.left.value,
                1f,
            )
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    private fun scrollToBlock(start: Int): SemanticsNodeInteraction {
        val tag = "preview.block.$start"
        compose.onNodeWithTag("document.preview").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
}
