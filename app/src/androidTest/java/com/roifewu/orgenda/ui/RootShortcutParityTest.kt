package com.roifewu.orgenda.ui

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
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
    private val app
        get() =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as OrgendaApplication

    private val store = ViewModelStore()
    private val intents = MutableStateFlow<Intent?>(null)

    private fun navigation(key: String) =
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
                hasText(app.label(key))
        )

    private fun show(): OrgendaViewModel {
        runBlocking { app.ready.await() }
        val model =
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[
                OrgendaViewModel::class.java]
        compose.setContent { OrgendaApp(model, intents) { intents.value = null } }
        compose.waitForIdle()
        return model
    }

    @After
    fun cleanup() {
        compose.runOnIdle { store.clear() }
    }

    @Test
    fun captureSheetSurvivesStateRestoration() {
        runBlocking { app.ready.await() }
        val model =
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[
                OrgendaViewModel::class.java]
        val restoration = StateRestorationTester(compose)
        restoration.setContent { OrgendaApp(model, intents) { intents.value = null } }
        compose.runOnIdle { intents.value = Intent().putExtra("action", "capture") }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty()
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.label("capture")).assertIsDisplayed()
    }

    @Test
    fun ordinaryActivityIntentDoesNotCloseAnOpenDocument() {
        val model = show()
        val file =
            model.workspace.value.files.first {
                !it.directory && !it.trashed && it.contentBlob != null && it.path != "config.json"
            }
        runBlocking { model.open(file).join() }
        compose.waitForIdle()
        compose.runOnIdle { intents.value = Intent(Intent.ACTION_MAIN) }
        compose.waitForIdle()
        compose.runOnIdle { org.junit.Assert.assertEquals(file.id, model.editor.value?.file?.id) }
    }

    @Test
    fun captureShortcutFromCalendarOpensConfiguredCapture() {
        show()
        compose.runOnIdle { intents.value = Intent().putExtra("action", "today") }
        compose.waitUntil(15000) {
            compose.onAllNodesWithTag("calendar.timeline").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { intents.value = Intent().putExtra("action", "capture") }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.label("capture")).assertIsDisplayed()
        compose.onNodeWithText(app.label("preview_before_capture")).assertExists()
    }

    @Test
    fun shortcutWaitsUntilCaptureSheetCloses() {
        show()
        compose.runOnIdle { intents.value = Intent().putExtra("action", "capture") }
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(app.label("capture")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { intents.value = Intent().putExtra("action", "files") }
        compose.waitForIdle()
        compose.onNodeWithText(app.label("capture")).assertIsDisplayed()
        compose.onNodeWithText(app.label("cancel")).performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onAllNodesWithText(app.label("all_files")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(app.label("capture")).assertDoesNotExist()
    }

    @Test
    fun interruptedTabTransitionsKeepSearchDraft() {
        show()
        compose.runOnIdle { intents.value = Intent().putExtra("action", "search") }
        compose.onNodeWithTag("search.query").performTextReplacement("Navigation draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.mainClock.autoAdvance = false
        try {
            repeat(3) {
                navigation("files").performClick()
                compose.mainClock.advanceTimeBy(48)
                navigation("agenda").performClick()
                compose.mainClock.advanceTimeBy(48)
                navigation("search").performClick()
                compose.mainClock.advanceTimeBy(48)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("search.query").assertTextContains("Navigation draft")
    }

    @Test
    fun documentReturnRestoresSearchDraft() {
        val model = show()
        compose.runOnIdle { intents.value = Intent().putExtra("action", "search") }
        compose.onNodeWithTag("search.query").performTextReplacement("Document return")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val file =
            model.workspace.value.files.first {
                !it.directory && !it.trashed && it.contentBlob != null && it.path != "config.json"
            }
        runBlocking { model.open(file).join() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(app.label("back")).performClick()
        compose.waitUntil(15000) { model.editor.value == null }
        compose.onNodeWithTag("search.query").assertTextContains("Document return")
    }

    @Test
    fun settingsSectionReturnsToSettingsList() {
        show()
        navigation("settings").assertDoesNotExist()
        navigation("files").performClick()
        val settings = compose.onNodeWithContentDescription(app.label("settings"))
        val sync = compose.onNodeWithContentDescription(app.label("sync"))
        org.junit.Assert.assertTrue(
            settings.fetchSemanticsNode().boundsInRoot.right <=
                sync.fetchSemanticsNode().boundsInRoot.left
        )
        settings.performClick()
        compose.onNodeWithText(app.label("workflow")).performScrollTo().performClick()
        compose.onNodeWithContentDescription(app.label("back")).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.label("workflow")).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.label("back")).assertDoesNotExist()
        compose.onNodeWithContentDescription(app.label("close")).performClick()
        compose.onNodeWithText(app.label("all_files")).assertIsDisplayed()
        navigation("files").assertIsSelected()
    }

    @Test
    fun settingsSearchOpensSheetAndReturnsToSearch() {
        show()
        navigation("search").performClick()
        compose
            .onNodeWithText(app.label("settings"), substring = false)
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(app.label("workflow"), substring = false).performClick()
        compose.onNodeWithContentDescription(app.label("back")).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.label("close")).performClick()
        compose.onNodeWithTag("search.query").assertIsDisplayed()
        navigation("search").assertIsSelected()
    }

    @Test
    fun settingsSheetKeepsInvalidConfigurationUntilUserDiscardsIt() {
        val model = show()
        navigation("files").performClick()
        compose.onNodeWithContentDescription(app.label("settings")).performClick()
        compose.runOnIdle { model.configureRaw("{") }
        compose.onNodeWithContentDescription(app.label("close")).performClick()
        compose.onNodeWithText(app.label("continue_editing")).performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("{", model.configuration.value?.raw) }
        compose.onNodeWithContentDescription(app.label("close")).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.label("discard")).performClick()
        compose.onNodeWithText(app.label("all_files")).assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertFalse(model.configuration.value?.dirty == true) }
    }
}
