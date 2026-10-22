package com.roifewu.orgenda.ui

import android.content.res.Configuration
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
import com.roifewu.orgenda.core.groupParagraphs
import com.roifewu.orgenda.data.FileEntity
import io.ratex.RaTeXEngine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewMathLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun consecutiveDisplayFormulasDoNotOverlapBody() = checkLayout(1f)

    @Test fun largeDisplayFormulasDoNotOverlapBody() = checkLayout(2f)

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
            * Typing and Evaluation
            对于 type abstraction，需要在检查 body 时记录 bound；对于 type application，则需要检查实际传入的类型满足 bound:
            \[
            \frac{\Gamma, X <: T_1 \vdash t_2 : T_2}{\Gamma \vdash (\lambda X <: T_1.t_2) : \forall X <: T_1.T_2}(T-TAbs)
            \]
            \[
            \frac{\Gamma \vdash t_1 : \forall X <: T_{11}.T_{12} \qquad \Gamma \vdash T_2 <: T_{11}}{\Gamma \vdash t_1[T_2] : [X \mapsto T_2]T_{12}}(T-TApp)
            \]
            普通函数的 typing rules 和 T-Sub 保持原来的形式，只是其中的 subtyping judgment 也带上了 ${'$'}\Gamma${'$'}。
            \[
            (\lambda X <: T_{11}.t_{12})[T_2] \to [X \mapsto T_2]t_{12}(E-TAppTAbs)
            \]
            公式之后的正文。
            """
                .trimIndent() +
                "\n\n" +
                (1..35).joinToString("\n\n") { "滚动测试段落 $it。" } +
                "\n\n* End\n"
        val document = model.repository.engine.parse("math-layout.org", source)
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
            fun checkVisibleFormulas(state: EditorState) {
                val syntax = state.document!!.syntax.groupParagraphs()
                val formulas = syntax.blocks.filter { it.type == "latex_block" }
                assertEquals(3, formulas.size)
                for (formula in formulas.take(2)) {
                    val expression = formulaText(syntax.text(formula))
                    val layout = RaTeXEngine.parseBlocking(expression)
                    val fontSizePx =
                        DocumentTypography.scaledDp(context, DocumentTypography.Body) *
                            1.08f *
                            context.resources.displayMetrics.density
                    val expectedHeight = (layout.height + layout.depth) * fontSizePx
                    compose.waitUntil(5000) {
                        val node =
                            compose.onNodeWithContentDescription(expression).fetchSemanticsNode()
                        node.boundsInRoot.height >= expectedHeight - 1
                    }
                    val bounds =
                        compose.onNodeWithContentDescription(expression).getUnclippedBoundsInRoot()
                    val row =
                        compose
                            .onNodeWithTag("preview.block.${formula.start}")
                            .getUnclippedBoundsInRoot()
                    assertTrue(
                        "Formula extends below its preview row: formula=$bounds, row=$row",
                        row.bottom.value + 0.5f >= bounds.bottom.value,
                    )
                    val following =
                        syntax.blocks.first { it.start >= formula.end && it.type != "blank_line" }
                    val next =
                        compose
                            .onNodeWithTag("preview.block.${following.start}")
                            .getUnclippedBoundsInRoot()
                    assertTrue(
                        "Formula overlaps the next block: formula=$bounds, next=$next",
                        next.top.value >= bounds.bottom.value,
                    )
                }
            }
            checkVisibleFormulas(editor)
            val updatedSource =
                source.replace(
                    "\\Gamma, X <: T_1 \\vdash t_2 : T_2",
                    "\\frac{\\Gamma, X <: T_1 \\vdash t_2 : T_2}{X <: T_1}",
                )
            val updatedDocument = model.repository.engine.parse(document.path, updatedSource)
            val updatedEditor = editor.copy(text = updatedSource, document = updatedDocument)
            compose.runOnIdle { currentEditor.value = updatedEditor }
            checkVisibleFormulas(updatedEditor)
            compose
                .onNodeWithTag("document.preview")
                .performScrollToNode(
                    hasTestTag("preview.block.${updatedDocument.headings.last().start}")
                )
            compose.onNodeWithTag("document.preview").performScrollToIndex(0)
            checkVisibleFormulas(updatedEditor)
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    private fun formulaText(source: String) = source.trim().drop(2).dropLast(2)
}
