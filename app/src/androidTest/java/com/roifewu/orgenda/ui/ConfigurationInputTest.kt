@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import android.text.style.ForegroundColorSpan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConfigurationInputTest {
    @get:Rule val compose = createComposeRule()
    @Test fun separatorsRemainEditableAndInvalidNumbersReachValidation() {
        var values by mutableStateOf(listOf("TODO"))
        var number by mutableIntStateOf(10)
        compose.setContent { MaterialTheme { Column { ValuesField(values, "process_states", false) { values = it }; NumberField(number, "advance") { number = it } } } }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInputSelection(androidx.compose.ui.text.TextRange(4))
        fields[0].performTextInput(" ")
        compose.waitForIdle()
        fields[0].performTextInput("NEXT")
        compose.runOnIdle { assertEquals(listOf("TODO", "NEXT"), values) }
        fields[1].performTextReplacement("invalid")
        compose.runOnIdle { assertEquals(Int.MIN_VALUE, number) }
        fields[1].performTextReplacement("15")
        compose.runOnIdle { assertEquals(15, number) }
    }
    @Test fun nativeCodeHighlightingPreservesUnicodeSourceAndProducesSpans() {
        val sources = mapOf("kotlin" to "val 中文 = \"🧪\" // comment", "python" to "def run():\n    return '中文'", "elisp" to "(defun test () \"中文\")", "bash" to "if true; then echo '\$x'; fi", "json" to "{\"中文\": true}")
        sources.forEach { (language, source) ->
            val styled = CodeHighlight.text(source, language, 1, 2, 3, 4)
            assertEquals(source, styled.toString())
            assertTrue(language, styled.getSpans(0, styled.length, ForegroundColorSpan::class.java).isNotEmpty())
        }
    }
}
