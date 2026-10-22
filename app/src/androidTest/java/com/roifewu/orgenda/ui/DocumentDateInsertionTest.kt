package com.roifewu.orgenda.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.orgTimestamp
import com.roifewu.orgenda.data.FileEntity
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DocumentDateInsertionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restoredDateDialogReplacesItsOriginalUnicodeSelectionAndUndoesOnce() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = "* Heading\nBefore replace 🧪 after\n"
        val start = source.indexOf("replace")
        val end = source.indexOf(" after")
        val state =
            EditorState(
                FileEntity("ui-only", "ui-only", "date.org"),
                source,
                null,
                null,
                preview = false,
                dirty = true,
            )
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { DocumentScreen(model, state, {}, {}, {}) } }
            compose.runOnIdle {
                requireNotNull(findEditor(compose.activity.window.decorView))
                    .setSelection(start, end)
            }
            compose.onNodeWithContentDescription(context.label("date")).performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(context.label("insert")).performClick()
            val inserted = orgTimestamp(LocalDate.now().atStartOfDay(), includesTime = false)
            compose.runOnIdle {
                val editor = requireNotNull(findEditor(compose.activity.window.decorView))
                assertEquals(source.replaceRange(start, end, inserted), editor.text.toString())
                assertEquals(start + inserted.length, editor.selectionStart)
                editor.undo()
                assertEquals(source, editor.text.toString())
                assertEquals(start, editor.selectionStart)
                assertEquals(end, editor.selectionEnd)
                assertFalse(editor.canUndo)
            }
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    @Test
    fun dateDialogRejectsItsStaleSelectionAfterTheSourceChanges() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var state by
            mutableStateOf(
                EditorState(
                    FileEntity("ui-only", "ui-only", "date.org"),
                    "* Before\n",
                    null,
                    null,
                    preview = false,
                )
            )
        try {
            compose.setContent { MaterialTheme { DocumentScreen(model, state, {}, {}, {}) } }
            compose.onNodeWithContentDescription(context.label("date")).performClick()
            compose.runOnIdle { state = state.copy(text = "* Replaced externally\n") }
            compose.onNodeWithText(context.label("insert")).assertIsNotEnabled()
            compose.onNodeWithText(context.label("date_source_changed")).assertExists()
            compose.onNodeWithText(context.label("cancel")).performClick()
            compose.runOnIdle {
                assertEquals(
                    state.text,
                    requireNotNull(findEditor(compose.activity.window.decorView)).text.toString(),
                )
            }
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    private fun findEditor(view: View): NativeSourceEditor? {
        if (view is NativeSourceEditor) return view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) findEditor(view.getChildAt(index))?.let {
                return it
            }
        return null
    }
}
