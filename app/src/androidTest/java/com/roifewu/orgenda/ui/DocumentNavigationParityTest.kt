package com.roifewu.orgenda.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.SourceText
import com.roifewu.orgenda.data.FileEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DocumentNavigationParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectingTheSameHeadingAgainReturnsToItAfterScrollingAway() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val text = (1..200).joinToString("\n") { "* Heading $it 中文" }
        var state by
            mutableStateOf(
                EditorState(
                    FileEntity("ui-only", "ui-only", "navigation.org"),
                    text,
                    null,
                    null,
                    preview = false,
                )
            )
        try {
            compose.setContent { MaterialTheme { DocumentScreen(model, state, {}, {}, {}) } }
            compose.waitForIdle()
            lateinit var editor: NativeSourceEditor
            compose.runOnIdle {
                editor = requireNotNull(findEditor(compose.activity.window.decorView))
            }
            compose.runOnIdle {
                editor.scrollTo(0, editor.layout.getLineTop(80))
                assertTrue(editor.scrollY > 0)
            }
            compose.runOnIdle {
                state = state.copy(navigationRevision = state.navigationRevision + 1)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(0, editor.scrollY)
                assertEquals(text, editor.text.toString())
            }
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    @Test
    fun sourceReadingPositionSurvivesStateRestorationAfterScrolling() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val text = (1..200).joinToString("\n") { "* Heading $it 中文" }
        var state by
            mutableStateOf(
                EditorState(
                    FileEntity("ui-only", "ui-only", "navigation.org"),
                    text,
                    null,
                    null,
                    preview = false,
                )
            )
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { DocumentScreen(model, state, {}, {}, {}) } }
            compose.waitForIdle()
            var expectedByte = 0
            compose.runOnIdle {
                val editor = requireNotNull(findEditor(compose.activity.window.decorView))
                editor.scrollTo(0, editor.layout.getLineTop(80))
                expectedByte = SourceText(text).byteOffset(editor.layout.getLineStart(80))
                state = state.copy(readingByte = expectedByte)
            }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitForIdle()
            compose.runOnIdle {
                val editor = requireNotNull(findEditor(compose.activity.window.decorView))
                assertTrue(editor.scrollY > 0)
                val visibleByte =
                    SourceText(text)
                        .byteOffset(
                            editor.layout.getLineStart(
                                editor.layout.getLineForVertical(editor.scrollY)
                            )
                        )
                assertEquals(
                    "Restored scrollY=${editor.scrollY}, caret=${editor.selectionStart}, topLine=${editor.layout.getLineForVertical(editor.scrollY)}",
                    expectedByte,
                    visibleByte,
                )
            }
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    @Test
    fun previewKeepsItsRestoredScrollPositionInsteadOfReplayingTheInitialJump() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val text = (1..200).joinToString("\n") { "* Heading $it 中文" }
        val document = model.repository.engine.parse("navigation.org", text)
        val state =
            EditorState(FileEntity("ui-only", "ui-only", "navigation.org"), text, null, document)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { OrgPreview(model, state, {}, {}) } }
            compose.onNodeWithTag("document.preview").performScrollToIndex(80)
            val before =
                compose
                    .onNodeWithTag("document.preview")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value()
            assertTrue(before > 70f)
            restoration.emulateSavedInstanceStateRestore()
            val after =
                compose
                    .onNodeWithTag("document.preview")
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange]
                    .value()
            assertEquals(before, after, 0.01f)
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    @Test
    fun jumpToEndOfFileKeepsTheLastLineVisibleWithoutScrollingPastContent() {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val text = (1..200).joinToString("\n") { "* Heading $it 中文" }
        val state =
            EditorState(
                FileEntity("ui-only", "ui-only", "navigation.org"),
                text,
                null,
                null,
                preview = false,
                jumpByte = SourceText(text).bytes.size,
            )
        try {
            compose.setContent { MaterialTheme { DocumentScreen(model, state, {}, {}, {}) } }
            compose.waitForIdle()
            compose.runOnIdle {
                val editor = requireNotNull(findEditor(compose.activity.window.decorView))
                val layout = editor.layout
                val lastLine = layout.getLineForOffset(editor.length())
                val viewportBottom =
                    editor.scrollY + editor.height -
                        editor.totalPaddingTop -
                        editor.totalPaddingBottom
                assertTrue(editor.scrollY > 0)
                assertTrue(
                    "Last line starts above the visible text area",
                    layout.getLineTop(lastLine) >= editor.scrollY,
                )
                assertTrue(
                    "Last line ends below the visible text area",
                    layout.getLineBottom(lastLine) <= viewportBottom,
                )
                assertEquals(
                    "Viewport must stop at the content bottom",
                    layout.height,
                    viewportBottom,
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
