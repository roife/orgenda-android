package com.roifewu.orgenda.ui

import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.data.FileEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewDrawerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactDrawersRenderPropertiesAndReadOnlyHistoryAfterRestoration() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val source =
            "* TODO Metadata\n:PROPERTIES:\n:ID: stable-id\n:NOTE: *中文 café* [2026-10-21 Wed 10:00]\n:END:\n:LOGBOOK:\n- State \"DONE\" from \"TODO\" [2026-10-21 Wed 10:00]\n  历史备注 café\n- State \"WAIT\" [2026-10-20 Tue 09:00]\n- [X] 历史复选框\n- 普通备注\nCLOCK: [2026-10-21 Wed 09:00]--[2026-10-21 Wed 10:30] =>  1:30\nCLOCK: [2026-10-22 Thu 09:00]\n:END:\n"
        val document = model.repository.engine.parse("drawers.org", source)
        val editor =
            EditorState(FileEntity("ui-only", "ui-only", document.path), source, null, document)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { OrgPreview(model, editor, {}, {}) } }
            for (drawer in
                document.syntax.blocks.filter { it.type in listOf("property_drawer", "drawer") }) {
                val bounds =
                    compose
                        .onNodeWithTag("document.drawer.${drawer.start}")
                        .fetchSemanticsNode()
                        .boundsInRoot
                val height = bounds.height / compose.activity.resources.displayMetrics.density
                assertTrue("Collapsed drawer is too tall: ${height}dp", height in 24f..32f)
            }
            compose.onNodeWithText("PROPERTIES").performClick()
            compose.onNodeWithText("ID").assertIsDisplayed()
            compose.onNodeWithText("NOTE").assertIsDisplayed()
            compose.waitUntil(5000) { nativeTexts().any { it.toString().startsWith("中文 café ") } }
            val property = nativeTexts().single { it.toString().startsWith("中文 café ") } as Spanned
            assertFalse(property.toString().contains("[2026"))
            assertTrue(property.getSpans(0, property.length, StyleSpan::class.java).isNotEmpty())
            assertTrue(
                property.getSpans(0, property.length, ForegroundColorSpan::class.java).isNotEmpty()
            )
            assertTrue(property.getSpans(0, property.length, ClickableSpan::class.java).isEmpty())
            assertTrue(nativeTexts().any { it.toString() == "stable-id" })

            compose.onNodeWithText("LOGBOOK").performScrollTo().performClick()
            compose.onNodeWithContentDescription("TODO → DONE").assertExists()
            compose.onNodeWithContentDescription("WAIT").assertExists()
            compose.onNodeWithText("[X]").assertExists()
            compose.onNodeWithText("•").assertExists()
            compose.onAllNodes(isToggleable()).assertCountEquals(0)
            compose.onNodeWithText("1h 30m").assertExists()
            compose.onNodeWithText(compose.activity.label("running")).assertExists()
            compose.waitUntil(5000) {
                nativeTexts().any {
                    it.toString().contains("历史备注 café") && !it.toString().contains("State")
                }
            }
            assertFalse(nativeTexts().any { it.toString().startsWith("State \"") })
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("ID").assertExists()
            compose.onNodeWithContentDescription("TODO → DONE").assertExists()
            compose.onNodeWithText("1h 30m").assertExists()
            compose.onNodeWithText("LOGBOOK").performScrollTo().performClick()
            compose.onNodeWithText("1h 30m").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(source, editor.text)
                assertNull(model.editor.value)
            }
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    @Test
    fun clocksShowCompactDurationsAndPreserveBothDatesAcrossMidnight() {
        val context = compose.activity
        val sameDay = "CLOCK: [2026-10-21 Wed 09:00]--[2026-10-21 Wed 10:30] =>  1:30"
        val overnight = "CLOCK: [2026-10-21 Wed 23:00]--[2026-10-22 Thu 01:00] =>  2:00"
        assertEquals("1h 30m", readableClockDuration(sameDay))
        assertEquals("2h", readableClockDuration(overnight))
        assertEquals("5m", readableClockDuration("=> 0:05"))
        assertEquals("0m", readableClockDuration("=> 0:00"))
        assertEquals("invalid", readableClockDuration("=> invalid"))
        assertNull(readableClockDuration("CLOCK: [2026-10-22 Thu 09:00]"))
        assertEquals(1, Regex("21").findAll(readableClockInterval(context, sameDay)).count())
        assertEquals(
            readableTimestamp(
                context,
                "[2026-10-21 Wed 23:00]--[2026-10-22 Thu 01:00]",
                compact = true,
            ),
            readableClockInterval(context, overnight),
        )
    }

    private fun nativeTexts(): List<CharSequence> {
        var result = emptyList<CharSequence>()
        fun collect(view: View): List<CharSequence> =
            when (view) {
                is TextView -> listOf(view.text)
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
        compose.runOnIdle { result = collect(compose.activity.window.decorView) }
        return result
    }
}
