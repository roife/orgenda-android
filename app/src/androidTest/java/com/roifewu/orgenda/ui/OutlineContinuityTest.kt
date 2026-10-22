package com.roifewu.orgenda.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.roifewu.orgenda.EditorState
import com.roifewu.orgenda.OrgendaViewModel
import com.roifewu.orgenda.core.HeadingPlacement
import com.roifewu.orgenda.core.Outline
import com.roifewu.orgenda.data.FileEntity
import org.junit.Rule
import org.junit.Test

class OutlineContinuityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movingExpandedSubtreeRevealsItsNewParentAndDoesNotReplayAfterRestoration() {
        val models = ViewModelStore()
        val model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(ApplicationProvider.getApplicationContext()))[OrgendaViewModel::class.java]
        val source = "* Source\n** Branch\n*** Leaf\n* Destination\n** Existing\n"
        val original = model.repository.engine.parse("outline.org", source)
        val branch = original.headings.single { it.title == "Branch" }
        val destination = original.headings.single { it.title == "Destination" }
        val moved = Outline.move(original, branch, destination, HeadingPlacement.CHILD)
        val updated = model.repository.engine.parse("outline.org", moved.text)
        val oldKeys = Outline.keys(original)
        val newKeys = Outline.keys(updated)
        val remapped = moved.starts.mapNotNull { (old, new) -> oldKeys[old]?.let { key -> newKeys[new]?.let { key to it } } }.toMap()
        var state by mutableStateOf(EditorState(FileEntity("ui-only", "ui-only", "outline.org"), source, null, original))
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { OutlineSheet(model, state) {} } }
            compose.onNodeWithTag("outline.toggle.0").performClick()
            compose.onNodeWithTag("outline.toggle.${branch.start}").performClick()
            compose.onNodeWithText("Leaf").assertIsDisplayed()
            // Repository publication can arrive before the operation's movement map.
            compose.runOnIdle { state = state.copy(text = moved.text, document = updated) }
            compose.runOnIdle { state = state.copy(headingMove = moved, headingMoveKeys = remapped, jumpByte = moved.starts.getValue(branch.start), navigationRevision = state.navigationRevision + 1) }
            compose.onNodeWithText("Leaf").assertIsDisplayed()
            compose.onNodeWithText("Existing").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Leaf").assertIsDisplayed()
            compose.onNodeWithTag("outline.toggle.${moved.starts.getValue(branch.start)}").performClick()
            compose.onNodeWithText("Leaf").assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Leaf").assertDoesNotExist()
            compose.onNodeWithText("Existing").assertIsDisplayed()
        } finally { compose.runOnIdle { models.clear() } }
    }

    @Test fun outlineFilterAndItsPriorExpansionSurviveStateRestoration() {
        val models = ViewModelStore()
        val model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(ApplicationProvider.getApplicationContext()))[OrgendaViewModel::class.java]
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = "* Parent\n** TODO Pending child\n** DONE Completed child\n* Another parent\n"
        val document = model.repository.engine.parse("outline.org", source)
        val state = EditorState(FileEntity("ui-only", "ui-only", "outline.org"), source, null, document)
        val restoration = StateRestorationTester(compose)
        try {
            restoration.setContent { MaterialTheme { OutlineSheet(model, state) {} } }
            compose.onNodeWithTag("outline.toggle.0").performClick()
            compose.onNodeWithText("Completed child").assertIsDisplayed()
            compose.onNodeWithTag("outline.filter").performClick()
            compose.onNodeWithTag("outline.filter.state.TODO").performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("outline.filter.state.TODO").assertIsSelected()
            compose.onNodeWithTag("outline.filter.close").performScrollTo().performClick()
            compose.onNodeWithText("Parent").assertIsDisplayed()
            compose.onNodeWithText("Pending child").assertIsDisplayed()
            compose.onNodeWithText("Completed child").assertDoesNotExist()
            compose.onNodeWithText("Another parent").assertDoesNotExist()
            compose.onNodeWithText(context.label("clear")).performClick()
            compose.onNodeWithText("Completed child").assertIsDisplayed()
            compose.onNodeWithText("Another parent").assertIsDisplayed()
        } finally { compose.runOnIdle { models.clear() } }
    }
}
