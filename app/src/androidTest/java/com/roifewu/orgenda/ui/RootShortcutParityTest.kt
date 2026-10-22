package com.roifewu.orgenda.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.roifewu.orgenda.OrgendaApplication
import com.roifewu.orgenda.OrgendaViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootShortcutParityTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OrgendaApplication
    private val store = ViewModelStore()
    private val intents = MutableStateFlow<Intent?>(null)

    private fun show(): OrgendaViewModel {
        runBlocking { app.ready.await() }
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[OrgendaViewModel::class.java]
        compose.setContent { OrgendaApp(model, intents) { intents.value = null } }
        compose.waitForIdle()
        return model
    }

    @After fun cleanup() { compose.runOnIdle { store.clear() } }

    @Test fun captureSheetSurvivesStateRestoration() {
        runBlocking { app.ready.await() }
        val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[OrgendaViewModel::class.java]
        val restoration = StateRestorationTester(compose)
        restoration.setContent { OrgendaApp(model, intents) { intents.value = null } }
        compose.runOnIdle { intents.value = Intent().putExtra("action", "capture") }
        compose.waitUntil(15000) { compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15000) { compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.label("capture")).assertIsDisplayed()
    }

    @Test fun ordinaryActivityIntentDoesNotCloseAnOpenDocument() {
        val model = show()
        val file = model.workspace.value.files.first { !it.directory && !it.trashed && it.contentBlob != null && it.path != "config.json" }
        runBlocking { model.open(file).join() }
        compose.waitForIdle()
        compose.runOnIdle { intents.value = Intent(Intent.ACTION_MAIN) }
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertEquals(file.id, model.editor.value?.file?.id) }
    }

    @Test fun captureShortcutFromCalendarOpensConfiguredCapture() {
        show()
        compose.runOnIdle { intents.value = Intent().putExtra("action", "today") }
        compose.waitUntil(15000) { compose.onAllNodesWithTag("calendar.timeline").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { intents.value = Intent().putExtra("action", "capture") }
        compose.waitUntil(15000) { compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.label("capture")).assertIsDisplayed()
        compose.onNodeWithText(app.label("preview_before_capture")).assertExists()
    }

    @Test fun shortcutWaitsUntilCaptureSheetCloses() {
        show()
        compose.runOnIdle { intents.value = Intent().putExtra("action", "capture") }
        compose.waitUntil(15000) { compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { intents.value = Intent().putExtra("action", "files") }
        compose.waitForIdle()
        compose.onNodeWithText(app.label("capture")).assertIsDisplayed()
        compose.onNodeWithText(app.label("cancel")).performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText(app.label("all_files")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.label("capture")).assertDoesNotExist()
    }
}
