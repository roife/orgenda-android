package com.roifewu.orgenda

import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.ui.EditorHistory
import com.roifewu.orgenda.ui.NativeSourceEditor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeInputSafetyTest {
    @Test
    fun listContinuationAndToolbarUndoUseNativeSelection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val editor =
                NativeSourceEditor(
                    ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(),
                        androidx.appcompat.R.style.Theme_AppCompat,
                    ),
                    EditorHistory(),
                )
            editor.display("9. [X] 中文🧪")
            editor.setSelection(editor.length())
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            connection.commitText("\n", 1)
            assertEquals("9. [X] 中文🧪\n10. [ ] ", editor.text.toString())
            editor.undo()
            assertEquals("9. [X] 中文🧪", editor.text.toString())
            editor.setSelection(0, editor.length())
            editor.command(com.roifewu.orgenda.core.InputCommand.INDENT)
            assertTrue(editor.text.toString().startsWith("  9."))
            editor.undo()
            assertEquals("9. [X] 中文🧪", editor.text.toString())
            editor.dispose()
        }
    }

    @Test
    fun composingChineseAndEmojiPreservesUtf16SelectionAndUndo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var editor: NativeSourceEditor
        instrumentation.runOnMainSync {
            editor =
                NativeSourceEditor(
                    ContextThemeWrapper(
                        ApplicationProvider.getApplicationContext(),
                        androidx.appcompat.R.style.Theme_AppCompat,
                    ),
                    EditorHistory(),
                )
            editor.display("* TODO 😀\n", 12)
            editor.setSelection(editor.length())
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("zhong", 1)
            connection.setComposingText("中文", 1)
            connection.commitText("中文🧪", 1)
            connection.finishComposingText()
            assertEquals("* TODO 😀\n中文🧪", editor.text.toString())
            assertEquals(editor.length(), editor.selectionStart)
        }
        Thread.sleep(400)
        instrumentation.runOnMainSync {
            editor.undo()
            assertEquals("* TODO 😀\n", editor.text.toString())
            editor.redo()
            assertEquals("* TODO 😀\n中文🧪", editor.text.toString())
            editor.dispose()
        }
    }
}
