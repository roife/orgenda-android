package com.roifewu.orgenda.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.core.SourceText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentInteractionParityTest {
    private fun editor() =
        NativeSourceEditor(
            ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(),
                androidx.appcompat.R.style.Theme_AppCompat,
            ),
            EditorHistory(),
        )

    @Test
    fun searchSelectsTheWholeUnicodeMatchWithoutChangingSourceOrHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                val source = "* 中文🧪\nCafe\u0301 and café\n"
                editor.display(source)
                assertTrue(editor.selectSearchMatch("CAFÉ"))
                assertEquals(
                    "Cafe\u0301",
                    editor.text!!
                        .subSequence(editor.selectionStart, editor.selectionEnd)
                        .toString(),
                )
                assertEquals(source, editor.text.toString())
                assertFalse(editor.canUndo)
                assertFalse(editor.selectSearchMatch("missing"))
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun readingPositionFollowsTheViewportInsteadOfAnOffscreenCaret() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                val source = (1..100).joinToString("\n") { "Line $it 中文" }
                editor.display(source)
                editor.measure(
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                )
                editor.layout(0, 0, 600, 240)
                editor.setSelection(0)
                var reading = -1
                editor.positionChanged = { reading = it }
                val offset = editor.layout.getLineStart(20)
                editor.scrollTo(0, editor.layout.getLineTop(20))
                editor.captureReadingPosition()
                assertEquals(0, editor.selectionStart)
                assertEquals(SourceText(source).byteOffset(offset), reading)
                assertTrue(reading > 0)
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun undoAndRedoAvailabilityTracksEditsAndProtectsComposingText() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                editor.display("* TODO ")
                editor.setSelection(editor.length())
                var history = false to false
                editor.historyChanged = { undo, redo -> history = undo to redo }
                editor.insert("中文")
                assertEquals(true to false, history)
                editor.undo()
                assertEquals(false to true, history)
                editor.redo()
                assertEquals("* TODO 中文", editor.text.toString())
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                connection.setComposingText("zhong", 1)
                val composing = editor.text.toString()
                assertFalse(editor.canUndo)
                editor.undo()
                assertEquals(composing, editor.text.toString())
                connection.finishComposingText()
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun readableTimestampKeepsTimeRangeRepeatLimitAndUnknownAnnotations() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = "SCHEDULED: <2026-10-22 Thu 09:00-10:30 ++1d/3d -2d custom>"
        val readable = readableTimestamp(context, source)
        assertTrue(readable.contains("10:30"))
        assertTrue(readable.contains(context.label("habit_maximum")))
        assertTrue(readable.contains(context.label("repeat_catch_up")))
        assertTrue(readable.contains("-2d custom"))
    }

    @Test
    fun consecutiveTypingUndoesAndRedoesAsOneEdit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                editor.display("* TODO ")
                editor.setSelection(editor.length())
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                listOf("h", "e", "l", "l", "o").forEach { connection.commitText(it, 1) }
                connection.setComposingText(" zhong", 1)
                connection.setComposingText(" 中文🧪", 1)
                connection.finishComposingText()
                assertEquals("* TODO hello 中文🧪", editor.text.toString())
                editor.undo()
                assertEquals("* TODO ", editor.text.toString())
                assertEquals(editor.length(), editor.selectionStart)
                assertFalse(editor.canUndo)
                editor.redo()
                assertEquals("* TODO hello 中文🧪", editor.text.toString())
                assertEquals(editor.length(), editor.selectionStart)
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun movingAwayAndBackEndsTheTypingGroupBeforeTheNextInput() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                editor.display("Body ")
                editor.setSelection(editor.length())
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                connection.commitText("a", 1)
                connection.commitText("b", 1)
                val caret = editor.selectionStart
                editor.setSelection(0)
                editor.setSelection(caret)
                connection.commitText("c", 1)
                connection.commitText("d", 1)
                editor.undo()
                assertEquals("Body ab", editor.text.toString())
                assertEquals(caret, editor.selectionStart)
                editor.undo()
                assertEquals("Body ", editor.text.toString())
                assertFalse(editor.canUndo)
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun movingSelectionAndToolbarCommandsEndTheTypingUndoGroup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                editor.display("Body ")
                editor.setSelection(editor.length())
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                connection.commitText("a", 1)
                connection.commitText("b", 1)
                editor.setSelection(0)
                connection.commitText("前", 1)
                editor.command(com.roifewu.orgenda.core.InputCommand.HEADING)
                assertEquals("* 前Body ab", editor.text.toString())
                editor.undo()
                assertEquals("前Body ab", editor.text.toString())
                editor.undo()
                assertEquals("Body ab", editor.text.toString())
                assertEquals(0, editor.selectionStart)
                editor.undo()
                assertEquals("Body ", editor.text.toString())
                assertFalse(editor.canUndo)
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun endingEditingSeparatesTypingAndCommittedCompositionIsImmediatelyUndoable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                editor.display("")
                val connection = editor.onCreateInputConnection(EditorInfo())!!
                connection.commitText("a", 1)
                connection.commitText("b", 1)
                editor.finishTyping()
                connection.setComposingText("zhong", 1)
                connection.setComposingText("中文🧪", 1)
                connection.finishComposingText()
                editor.undo()
                assertEquals("ab", editor.text.toString())
                editor.undo()
                assertEquals("", editor.text.toString())
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun externalSourceReplacementClearsOldUndoAndRedoWithoutOverwritingTheReplacement() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor = editor()
            try {
                editor.display("* Original\n")
                editor.setSelection(editor.length())
                editor.onCreateInputConnection(EditorInfo())!!.commitText("Local draft", 1)
                editor.command(com.roifewu.orgenda.core.InputCommand.HEADING)
                editor.undo()
                assertTrue(editor.canUndo)
                assertTrue(editor.canRedo)
                var availability = true to true
                editor.historyChanged = { undo, redo -> availability = undo to redo }
                val external = "* External revision 中文🧪\n"
                editor.display(external)
                assertEquals(false to false, availability)
                editor.undo()
                editor.redo()
                assertEquals(external, editor.text.toString())
            } finally {
                editor.dispose()
            }
        }
    }

    @Test
    fun recreatingTheEditorForTheSameSourceKeepsItsDocumentHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val history = EditorHistory()
            val context =
                ContextThemeWrapper(
                    ApplicationProvider.getApplicationContext(),
                    androidx.appcompat.R.style.Theme_AppCompat,
                )
            val original = "* Original\n"
            val first = NativeSourceEditor(context, history)
            first.display(original)
            first.setSelection(first.length())
            first.onCreateInputConnection(EditorInfo())!!.commitText("中文🧪", 1)
            val draft = first.text.toString()
            first.dispose()
            val recreated = NativeSourceEditor(context, history)
            try {
                recreated.display(draft)
                assertTrue(recreated.canUndo)
                recreated.undo()
                assertEquals(original, recreated.text.toString())
                recreated.redo()
                assertEquals(draft, recreated.text.toString())
            } finally {
                recreated.dispose()
            }
        }
    }
}
