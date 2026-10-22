package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.LocalDate
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roifewu.orgenda.core.OrgRepeater
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditingInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun journalTitleAndBodySurviveStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { JournalSheet(LocalDate.of(2026, 10, 22), close = {}) { _, _ -> } } }
        compose.onNodeWithTag("journal.title").performTextReplacement("保留我的标题")
        compose.onNodeWithTag("journal.body").performTextReplacement("第一行\n尚未保存的正文")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("journal.title").assertTextContains("保留我的标题")
        compose.onNodeWithTag("journal.body").assertTextContains("第一行\n尚未保存的正文")
    }

    @Test fun disabledRepeatDraftSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var observed = ""
        restoration.setContent {
            var recurrence by rememberSaveable { mutableStateOf("++2w/3w") }
            observed = recurrence
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecurrenceField(recurrence) { recurrence = it } } }
        }
        compose.onNodeWithTag("recurrence.enabled").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recurrence.enabled").performClick()
        compose.runOnIdle { assertEquals("++2w/3w", observed) }
    }

    @Test fun partiallyTypedRepeatCountSurvivesStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var observed = ""
        restoration.setContent {
            var recurrence by rememberSaveable { mutableStateOf("+2d") }
            observed = recurrence
            MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecurrenceField(recurrence) { recurrence = it } } }
        }
        compose.onNodeWithTag("recurrence.interval").performScrollTo().performTextReplacement("")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recurrence.interval").performScrollTo().performTextInput("3")
        compose.runOnIdle { assertEquals("+3d", observed) }
    }

    @Test fun repeatToggleRestoresRuleAndNormalizesHabitMaximum() {
        var recurrence by mutableStateOf("++2w/3w")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecurrenceField(recurrence) { recurrence = it } } } }
        compose.onNodeWithTag("recurrence.enabled").performClick()
        compose.runOnIdle { assertEquals("", recurrence) }
        compose.onNodeWithTag("recurrence.enabled").performClick()
        compose.runOnIdle { assertEquals("++2w/3w", recurrence) }
        compose.onNodeWithTag("recurrence.interval").performScrollTo().performTextReplacement("5")
        compose.runOnIdle { assertEquals("++5w/5w", recurrence) }
        compose.onNodeWithTag("recurrence.habit_maximum").performScrollTo().performTextReplacement("1")
        compose.runOnIdle { assertEquals("++5w/5w", recurrence) }
    }

    @Test fun temporaryEmptyRepeatCountDoesNotRemoveTheEditor() {
        var recurrence by mutableStateOf("+2d")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RecurrenceField(recurrence) { recurrence = it } } } }
        compose.onNodeWithTag("recurrence.interval").performScrollTo().performTextReplacement("")
        compose.runOnIdle { assertNull(OrgRepeater.parse(recurrence)) }
        compose.onNodeWithTag("recurrence.interval").assertExists().performTextInput("3")
        compose.runOnIdle { assertEquals("+3d", recurrence) }
    }
}
