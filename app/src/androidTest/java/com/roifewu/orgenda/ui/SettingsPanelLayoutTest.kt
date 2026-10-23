package com.roifewu.orgenda.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import android.graphics.Bitmap
import android.content.res.Configuration
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.OrgendaApplication
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.LocalPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsPanelLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val app
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OrgendaApplication
    private val store = ViewModelStore()

    @After
    fun cleanup() { store.clear() }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150) // Let the Android window render the settled Compose frame.
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(app.cacheDir, "settings-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun show(fontScale: Float) {
        runBlocking { app.ready.await() }
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[OrgendaViewModel::class.java]
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                OrgendaTheme(LocalPreferences(theme = if (fontScale > 1f) "dark" else "light")) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Surface(Modifier.requiredSize(320.dp, 740.dp)) {
                            val snapshot by model.workspace.collectAsState()
                            SettingsScreen(model, snapshot)
                        }
                    }
                }
            }
        }
        compose.waitUntil(15000) { model.workspace.value.workspace != null }
        compose.waitForIdle()
    }

    private fun text(key: String) = compose.onNodeWithText(app.label(key))
    private fun open(key: String) {
        if (compose.onAllNodesWithText(app.label(key)).fetchSemanticsNodes().isEmpty()) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.label(key)))
        }
        text(key).performScrollTo().performClick()
    }
    private fun back() { compose.onNodeWithContentDescription(app.label("back")).performClick() }

    private fun panels(fontScale: Float) {
        show(fontScale)
        capture("root-$fontScale")
        for (section in listOf("workflow", "capture_templates", "files_agenda", "reminders", "configuration")) {
            open(section)
            capture("$section-$fontScale")
            when (section) {
                "workflow" -> {
                    text("workflow_preset").performClick()
                    text("cancel").performClick()
                    compose.onAllNodesWithContentDescription(app.label("add_state"))[0].performScrollTo().performClick()
                    capture("state-editor-$fontScale")
                    text("cancel").performScrollTo().assertIsDisplayed().performClick()
                    compose.onNodeWithTag("choice.log_redeadline").performScrollTo().assertIsDisplayed().performClick()
                    capture("choice-$fontScale")
                    compose.onNodeWithContentDescription(app.label("close")).performClick()
                }
                "capture_templates" -> {
                    open("add")
                    capture("template-editor-$fontScale")
                    text("cancel").performScrollTo().assertIsDisplayed().performClick()
                }
                "files_agenda" -> {
                    open("included_paths")
                    capture("paths-$fontScale")
                    text("close").performScrollTo().assertIsDisplayed().performClick()
                    compose.onAllNodesWithContentDescription(app.label("browse"))[0].performScrollTo().performClick()
                    capture("workspace-picker-$fontScale")
                    text("cancel").performClick()
                    text("max_level").performScrollTo().assertIsDisplayed()
                }
                "reminders" -> {
                    text("warning_days").performScrollTo().assertIsDisplayed()
                    capture("reminder-timing-$fontScale")
                    text("warning_days").performClick()
                    capture("reminder-dialog-$fontScale")
                    text("cancel").performClick()
                }
                "configuration" -> {
                    val importButton = text("import_json").fetchSemanticsNode().boundsInRoot
                    val exportButton = text("export_json").fetchSemanticsNode().boundsInRoot
                    assertTrue("Import and export must not overlap", !importButton.overlaps(exportButton))
                    open("paste_configuration")
                    capture("paste-$fontScale")
                    text("cancel").performScrollTo().assertIsDisplayed().performClick()
                    text("reset").performScrollTo().assertIsDisplayed()
                }
            }
            back()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("choice.appearance"))
        capture("appearance-$fontScale")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.label("connect_dav")))
        open("connect_dav")
        capture("webdav-$fontScale")
        text("cancel").performClick()
    }

    @Test fun narrowPanelsRemainNavigable() { panels(1f) }
    @Test fun largeTextPanelsRemainNavigable() { panels(2f) }
    @Test
    fun selectedDesignKeepsDefaultsAndLoggingVisibleTogether() {
        runBlocking { app.ready.await() }
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[OrgendaViewModel::class.java]
        val chinese = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
        compose.setContent {
            val registry = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(
                LocalDensity provides Density(1.5f, 1f),
                LocalContext provides chinese,
                LocalActivityResultRegistryOwner provides registry,
            ) {
                OrgendaTheme(LocalPreferences(theme = "light")) {
                    Surface(Modifier.requiredSize(390.dp, 634.dp).testTag("settings.reference")) {
                        val snapshot by model.workspace.collectAsState()
                        SettingsScreen(model, snapshot, initialSection = "workflow")
                    }
                }
            }
        }
        compose.waitUntil(15000) { model.workspace.value.workspace != null }
        compose.onNodeWithTag("choice.initial").assertIsDisplayed()
        compose.onNodeWithTag("choice.log_redeadline").assertIsDisplayed()
        compose.onNodeWithContentDescription(chinese.label("log_drawer")).assertIsDisplayed()
        compose.onNodeWithTag("choice.initial").assertHeightIsAtLeast(48.dp)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        File(app.cacheDir, "settings-selected-design.png").outputStream().use {
            compose.onNodeWithTag("settings.reference").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
