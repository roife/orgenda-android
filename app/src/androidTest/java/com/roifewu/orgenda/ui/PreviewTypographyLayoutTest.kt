package com.roifewu.orgenda.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.Outline
import com.roifewu.orgenda.data.FileEntity
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreviewTypographyLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun metadataStaysWithItsTask() = checkLayout(392, 1f)

    @Test fun narrowLargeTextKeepsMetadataAndControlsReachable() = checkLayout(320, 2f)

    private fun checkLayout(width: Int, fontScale: Float) {
        val models = ViewModelStore()
        val model = ViewModelProvider(
            models,
            ViewModelProvider.AndroidViewModelFactory.getInstance(ApplicationProvider.getApplicationContext()),
        )[OrgendaViewModel::class.java]
        val source = """
            #+title: Welcome to orgenda
            #+author: orgenda
            #+startup: overview
            * Projects
            ** A small example
            Example body
            * TODO Try editing an Org heading :welcome:
            Task body
            * Inbox
        """.trimIndent() + "\n"
        val document = model.repository.engine.parse("typography.org", source)
        val task = document.headings.single { it.isTask }
        val example = document.headings.single { it.level == 2 }
        val keys = Outline.keys(document)
        model.collapsedHeadings[document.path] = setOf(keys.getValue(task.start), keys.getValue(example.start))
        val state = EditorState(FileEntity("ui-only", "ui-only", document.path), source, null, document)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var opened = 0
        var completed = 0
        try {
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { this.fontScale = fontScale }
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalContext provides context.createConfigurationContext(configuration),
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(density, fontScale),
                ) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            Surface(Modifier.width(width.dp)) {
                                OrgPreview(model, state, { opened++ }, { completed++ })
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            for (heading in document.headings.dropLast(1)) {
                compose.onNodeWithTag("preview.heading.title.${heading.start}").performScrollTo()
                compose.waitUntil(5000) { nativeTexts().any { it.text.toString() == heading.title } }
                val disclosure = compose.onNodeWithContentDescription(
                    context.label(if (heading == document.headings.first()) "collapse" else "expand") + " " + heading.title,
                ).fetchSemanticsNode().boundsInWindow
                compose.runOnIdle {
                    val text = nativeTexts().single { it.text.toString() == heading.title }
                    val position = IntArray(2)
                    text.getLocationInWindow(position)
                    val metrics = text.paint.fontMetrics
                    val firstLineCenter = position[1] + text.baseline + (metrics.ascent + metrics.descent) / 2
                    assertEquals("Disclosure must center on the first line of ${heading.title}",
                        firstLineCenter, disclosure.center.y, 1.5f)
                }
            }
            compose.onNodeWithTag("preview.heading.title.${task.start}").performScrollTo()
            val title = compose.onNodeWithTag("preview.heading.title.${task.start}")
                .fetchSemanticsNode().boundsInRoot
            val tags = compose.onNodeWithText("#welcome", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals("Tags must align with task text", title.left, tags.left, 1f)
            assertTrue("Tags must stay within the task's text group", tags.bottom <= title.bottom)
            compose.onNodeWithTag("document.preview").performScrollToIndex(0)
            compose.waitForIdle()
            File(context.getExternalFilesDir(null), "typography-$width-$fontScale.png").outputStream().use {
                compose.onNodeWithTag("document.preview").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("preview.heading.title.${task.start}").performScrollTo()
            compose.onNodeWithText("#welcome", useUnmergedTree = true).performClick()
            compose.runOnIdle { assertEquals("Metadata opens its task once", 1, opened) }
            compose.onNodeWithContentDescription(context.label("state") + " " + task.title).performClick()
            compose.runOnIdle { assertEquals("State control stays independently clickable", 1, completed) }
            compose.onNodeWithTag("preview.heading.title.${document.headings.last().start}")
                .performScrollTo().assertIsDisplayed()
        } finally {
            compose.runOnIdle { models.clear() }
        }
    }

    private fun nativeTexts(): List<TextView> {
        fun collect(view: View): List<TextView> =
            if (view is TextView) listOf(view)
            else if (view is ViewGroup) (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
            else emptyList()
        return collect(compose.activity.window.decorView)
    }
}
