package com.roifewu.orgenda.ui

import android.content.res.Configuration
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.data.FileEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewCustomBlockLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun propositionAndProofKeepRenderedContentInsideTheirCards() = checkLayout(1f)

    @Test fun largeTextAndInlineFormulasResizeTheirCards() = checkLayout(2f)

    private fun checkLayout(fontScale: Float) {
        val models = ViewModelStore()
        val model =
            ViewModelProvider(
                models,
                ViewModelProvider.AndroidViewModelFactory.getInstance(
                    ApplicationProvider.getApplicationContext()
                ),
            )[OrgendaViewModel::class.java]
        val source =
            """
            subtyping 关系。

            #+begin_proposition
            *(Covariance of encoded products)*

            If ${'$'}\Gamma \vdash S_1 <: T_1${'$'} and ${'$'}\Gamma \vdash S_2 <: T_2${'$'}, then

            ${'$'}\Gamma \vdash \texttt{Pair}\ S_1\ S_2 <: \texttt{Pair}\ T_1\ T_2${'$'}
            #+end_proposition
            #+begin_proof
            展开定义。对于新变量 ${'$'}R <: \texttt{Top}${'$'}，连续使用 =S-Arrow= 得到：
            \[
            \frac{\Gamma \vdash S_1 <: T_1 \qquad \Gamma \vdash S_2 <: T_2}{\Gamma \vdash \texttt{Pair}\ S_1\ S_2 <: \texttt{Pair}\ T_1\ T_2}
            \]
            证明结束。
            #+end_proof
            """
                .trimIndent() +
                "\n\n" +
                (1..35).joinToString("\n\n") { "滚动段落 $it。" } +
                "\n\n* End\n"
        val document = model.repository.engine.parse("custom-layout.org", source)
        val editor =
            EditorState(FileEntity("ui-only", "ui-only", document.path), source, null, document)
        val currentEditor = mutableStateOf(editor)
        val configuration =
            Configuration(compose.activity.resources.configuration).apply {
                this.fontScale = fontScale
            }
        val context = compose.activity.createConfigurationContext(configuration)
        try {
            compose.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, fontScale),
                ) {
                    MaterialTheme { OrgPreview(model, currentEditor.value, {}, {}) }
                }
            }
            fun checkCards(state: EditorState) {
                compose.waitUntil(5000) {
                    var ready = false
                    compose.runOnIdle {
                        ready =
                            nativeTexts()
                                .filter {
                                    it.text.toString().contains("If ") ||
                                        it.text.toString().contains("Pair") ||
                                        it.text.toString().contains("展开定义")
                                }
                                .count { view ->
                                    val text = view.text as? Spanned
                                    text != null &&
                                        text
                                            .getSpans(0, text.length, ReplacementSpan::class.java)
                                            .isNotEmpty()
                                } == 3
                    }
                    ready
                }
                val cards = state.document!!.syntax.blocks.filter { it.type == "custom_block" }
                assertEquals(2, cards.size)
                val first =
                    compose
                        .onNodeWithTag("preview.block.${cards[0].start}")
                        .fetchSemanticsNode()
                        .boundsInRoot
                val second =
                    compose
                        .onNodeWithTag("preview.block.${cards[1].start}")
                        .fetchSemanticsNode()
                        .boundsInRoot
                assertTrue("Proposition overlaps proof", second.top >= first.bottom)
                compose.runOnIdle {
                    nativeTexts()
                        .filter {
                            it.text.toString().contains("If ") ||
                                it.text.toString().contains("Pair") ||
                                it.text.toString().contains("展开定义")
                        }
                        .forEach { view ->
                            val layout = requireNotNull(view.layout)
                            assertTrue(
                                "Rich text is clipped: ${view.text}, layout=${layout.height}, view=${view.height}",
                                view.height + 1 >=
                                    layout.height + view.totalPaddingTop + view.totalPaddingBottom,
                            )
                            assertEquals(
                                "Rich text retains height from its previous content: ${view.text}",
                                (layout.height + view.totalPaddingTop + view.totalPaddingBottom)
                                    .toFloat(),
                                view.height.toFloat(),
                                1f,
                            )
                            var root: View = view
                            while (
                                root.parent is View &&
                                    root.javaClass.simpleName != "AndroidComposeView"
                            ) root = root.parent as View
                            val origin = IntArray(2).also(root::getLocationInWindow)
                            val position = IntArray(2).also(view::getLocationInWindow)
                            val card = if (view.text.toString().contains("展开定义")) second else first
                            val bottom =
                                position[1] - origin[1] + layout.height + view.totalPaddingTop
                            assertTrue(
                                "Rich text escapes its card: ${view.text}, bottom=$bottom, card=$card",
                                bottom <= card.bottom + 1,
                            )
                        }
                }
            }
            checkCards(editor)
            val updatedSource =
                source.replace(
                    "\\Gamma \\vdash S_1 <: T_1",
                    "\\frac{\\Gamma \\vdash S_1 <: T_1}{S_1 <: T_1}",
                )
            val updatedDocument = model.repository.engine.parse(document.path, updatedSource)
            val updatedEditor = editor.copy(text = updatedSource, document = updatedDocument)
            compose.runOnIdle { currentEditor.value = updatedEditor }
            checkCards(updatedEditor)
            compose.runOnIdle { currentEditor.value = editor }
            checkCards(editor)
            compose
                .onNodeWithTag("document.preview")
                .performScrollToNode(hasTestTag("preview.block.${document.headings.last().start}"))
            compose.onNodeWithTag("document.preview").performScrollToIndex(0)
            checkCards(editor)
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    private fun nativeTexts(): List<TextView> {
        fun collect(view: View): List<TextView> =
            when (view) {
                is TextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
        return collect(compose.activity.window.decorView)
    }
}
