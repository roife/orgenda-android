package com.roifewu.orgenda.ui

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.OrgendaApplication
import com.roifewu.orgenda.OrgendaViewModel
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaterialAdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val app
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as OrgendaApplication

    private val store = ViewModelStore()
    private val intents = MutableStateFlow<Intent?>(null)
    private var diagnosticName = "uninitialized"
    private var viewportDiagnostics = "Content not composed"

    private fun navigation(key: String) =
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                (hasContentDescription(app.label(key)) or hasText(app.label(key)))
        )

    private fun show(fontScale: Float, height: Int = 360, action: String? = null) {
        diagnosticName = "material-adaptive-$fontScale-$height-${action ?: "agenda"}"
        runBlocking { app.ready.await() }
        val model =
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[
                OrgendaViewModel::class.java]
        intents.value = action?.let { Intent().putExtra("action", it) }
        compose.setContent {
            val configuration =
                Configuration(LocalConfiguration.current).apply {
                    this.fontScale = fontScale
                    densityDpi = 160
                    screenWidthDp = 900
                    screenHeightDp = height
                    smallestScreenWidthDp = height
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalConfiguration provides configuration,
            ) {
                val density = LocalDensity.current
                val systemBars = WindowInsets.systemBars
                val topInset = systemBars.getTop(density)
                val bottomInset = systemBars.getBottom(density)
                SideEffect {
                    viewportDiagnostics =
                        "Viewport: 900 x $height px; density=${density.density}; " +
                            "fontScale=${density.fontScale}; systemBars top=$topInset, " +
                            "bottom=$bottomInset px"
                }
                // This is the app's available viewport, excluding the host's physical system bars.
                Box(
                    Modifier.requiredSize(900.dp, height.dp)
                        .consumeWindowInsets(WindowInsets.systemBars)
                ) {
                    OrgendaApp(model, intents) { intents.value = null }
                }
            }
        }
        compose.waitUntil(15000) { model.workspace.value.workspace != null }
        compose.waitForIdle()
    }

    private fun waitForFiles() {
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("files.browser").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        navigation("files").assertIsSelected()
        compose.onNodeWithTag("files.browser").assertIsDisplayed()
    }

    @After
    fun cleanup() {
        try {
            val directory = app.cacheDir
            runCatching {
                File(directory, "$diagnosticName.txt").writeText(
                    viewportDiagnostics + "\n\n" +
                        compose.onRoot(useUnmergedTree = true).printToString()
                )
            }
            runCatching {
                File(directory, "$diagnosticName.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }.onFailure {
                File(directory, "$diagnosticName-capture-error.txt").writeText(it.toString())
            }
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test
    fun landscapeCalendarDateOccupiesTheAppBarAboveNavigationAndContent() {
        show(fontScale = 1f, action = "today")
        compose.onNodeWithTag("calendar.datePicker").assertIsDisplayed()
        val bar = compose.onNodeWithTag("calendar.topBar").fetchSemanticsNode().boundsInRoot
        val tab = navigation("calendar").fetchSemanticsNode().boundsInRoot
        val timeline = compose.onNodeWithTag("calendar.timeline").fetchSemanticsNode().boundsInRoot
        assertTrue("The date bar spans above the navigation rail", bar.left < tab.center.x)
        assertTrue("Navigation starts below the date bar", tab.top >= bar.bottom)
        assertTrue("Calendar content starts below the date bar", timeline.top >= bar.bottom)
        compose.onNodeWithTag("calendar.datePicker").performClick()
        compose.onNodeWithText(app.label("cancel")).assertIsDisplayed().performClick()
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        compose.onNodeWithTag("calendar.day.$tomorrow").performClick()
        navigation("agenda").performClick()
        navigation("calendar").performClick()
        compose.onNodeWithTag("calendar.today").assertIsDisplayed().performClick()
        compose.onNodeWithTag("calendar.today").assertDoesNotExist()
        compose.onAllNodesWithTag("calendar.datePicker").assertCountEquals(1)
    }

    @Test
    fun largeTextUsesTheWholeFilesPaneInsteadOfAnEmptyDetailPane() {
        show(fontScale = 2f, action = "files")
        waitForFiles()
        compose.onNodeWithText(app.label("select_file")).assertDoesNotExist()
    }

    @Test
    fun regularTextKeepsTheWideFilesDetailPane() {
        show(fontScale = 1f, action = "files")
        waitForFiles()
        compose.onNodeWithText(app.label("select_file")).assertIsDisplayed()
    }

    @Test
    fun shortLandscapeCanScrollToSearchAndNavigateAtLargeText() {
        show(fontScale = 2f, height = 240)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(4)
            .assertAll(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        listOf("agenda", "calendar", "files", "search").forEach { key ->
            navigation(key).assert(hasContentDescription(app.label(key)))
        }
        navigation("agenda").assertIsSelected()
        navigation("search").assertIsNotDisplayed()
        navigation("search").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("search.query").fetchSemanticsNodes().isNotEmpty()
        }
        navigation("search").assertIsSelected()
        compose.onNodeWithTag("search.query").assertIsDisplayed()
    }
}
