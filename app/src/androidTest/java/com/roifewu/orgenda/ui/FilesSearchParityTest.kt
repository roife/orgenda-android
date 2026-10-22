package com.roifewu.orgenda.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.roifewu.orgenda.core.NativeOrgEngine
import com.roifewu.orgenda.data.FileEntity
import com.roifewu.orgenda.data.OperationEntity
import com.roifewu.orgenda.data.WorkspaceSnapshot
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilesSearchParityTest {
    @get:Rule val compose = createComposeRule()

    private fun file(path: String, directory: Boolean = false, trashed: Boolean = false) =
        FileEntity(path, "workspace", path, directory = directory, trashed = trashed)

    @Test
    fun implicitDirectoriesKeepDocumentsReachableWithoutDuplicatingExplicitSubtrees() {
        val files =
            listOf(
                file("loose/deep/note.org"),
                file("projects", directory = true),
                file("projects/task.org"),
                file("config.json"),
                file(".hidden/private.org"),
                file("gone.org", trashed = true),
            )
        assertEquals(
            listOf("projects", "loose/deep/note.org"),
            visibleFolderFiles(files, "").map { it.path },
        )
        assertEquals(
            listOf("projects/task.org"),
            visibleFolderFiles(files, "projects").map { it.path },
        )
        assertEquals(
            listOf("loose/deep/note.org"),
            visibleFolderFiles(files, "loose").map { it.path },
        )
    }

    @Test
    fun taskAndCalendarSuggestionsRespectTheirOwnCategories() {
        val document =
            NativeOrgEngine().use {
                it.parse(
                    "inbox.org",
                    "* TODO Work :work:\n* Meeting :event:meeting:\n<2026-10-22 Thu>\n* Memo :note:memo:\n",
                )
            }
        assertEquals(
            listOf("work"),
            searchTagSuggestions(document.headings, "tasks").map { it.first },
        )
        assertEquals(
            setOf("event", "meeting"),
            searchTagSuggestions(document.headings, "calendar").map { it.first }.toSet(),
        )
        assertTrue("memo" in searchTagSuggestions(document.headings, "all").map { it.first })
    }

    @Test
    fun emptyScopedSearchOffersWorkingExpandAndClearActions() {
        var expanded = false
        var cleared = false
        compose.setContent {
            MaterialTheme {
                SearchEmptyContent("tasks", false, { expanded = true }, { cleared = true })
            }
        }
        compose.onNodeWithTag("search.expandScope").performClick()
        compose.onNodeWithTag("search.clear").performClick()
        compose.runOnIdle {
            assertTrue(expanded)
            assertTrue(cleared)
        }
    }

    @Test
    fun folderSearchResultRemainsAnAccessibleOpenAction() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                SearchResultItem(
                    SearchHit("folder", "projects", 0, "Projects", "", "files", directory = true),
                    "project",
                ) {
                    opened = true
                }
            }
        }
        compose.onNodeWithText("Projects", substring = false).performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun deletedFolderHasOneRestoreEntryWithoutHidingSeparateDeletesAtTheSameOriginalPath() {
        val parent =
            file("projects", directory = true, trashed = true)
                .copy(remotePath = ".orgenda-trash/one/item", deletedAt = 30)
        val child =
            file("projects/task.org", trashed = true)
                .copy(remotePath = ".orgenda-trash/one/item/task.org", deletedAt = 30)
        val separate =
            child.copy(id = "separate", remotePath = ".orgenda-trash/two/item", deletedAt = 20)
        val missingParent =
            file("orphan/task.org", trashed = true)
                .copy(remotePath = ".orgenda-trash/three/item/task.org", deletedAt = 40)
        assertEquals(
            listOf(missingParent.id, parent.id, separate.id),
            deletedFileRoots(listOf(parent, child, separate, missingParent), emptyList()).map {
                it.id
            },
        )
    }

    @Test
    fun unsyncedFolderDeleteGroupsChildrenButPreservesIndependentlyQueuedDeletes() {
        val parent = file("projects", directory = true, trashed = true).copy(deletedAt = 30)
        val child = file("projects/task.org", trashed = true).copy(deletedAt = 30)
        val separate = file("projects/previous.org", trashed = true).copy(deletedAt = 20)
        val operations =
            listOf(
                OperationEntity(
                    "folder-trash",
                    "workspace",
                    parent.id,
                    "trash",
                    "projects",
                    ".orgenda-trash/one/item",
                    null,
                    null,
                    true,
                    30,
                ),
                OperationEntity(
                    "file-trash",
                    "workspace",
                    separate.id,
                    "trash",
                    separate.path,
                    ".orgenda-trash/two/item",
                    null,
                    null,
                    false,
                    20,
                ),
            )
        assertEquals(
            listOf(parent.id, separate.id),
            deletedFileRoots(listOf(parent, child, separate), operations).map { it.id },
        )
    }

    @Test
    fun enteringFolderStartsAtTopAndBackRestoresParentScrollPosition() {
        var folder by mutableStateOf("root")
        compose.setContent {
            MaterialTheme {
                FileFolderList(folder, Modifier.fillMaxWidth().height(200.dp)) {
                    items(100) { Text("$folder $it", Modifier.height(48.dp)) }
                }
            }
        }
        compose.onNodeWithTag("files.browser").performScrollToIndex(40)
        compose.onNodeWithText("root 40").assertIsDisplayed()
        compose.runOnIdle { folder = "child" }
        compose.onNodeWithText("child 0").assertIsDisplayed()
        compose.runOnIdle { folder = "root" }
        compose.onNodeWithText("root 40").assertIsDisplayed()
    }

    @Test
    fun failedMoveKeepsNameAndAllowsRetryWhilePendingMoveDisablesActions() {
        val original = file("original.org")
        val pending = CompletableDeferred<Unit>()
        var attempts = 0
        var closed = false
        compose.setContent {
            MaterialTheme {
                MoveFileSheet(
                    WorkspaceSnapshot(files = listOf(original)),
                    original,
                    { closed = true },
                ) { target ->
                    assertEquals("renamed.org", target)
                    attempts++
                    if (attempts == 1) pending.await()
                }
            }
        }
        compose.onNodeWithTag("files.move.name").performTextReplacement("renamed.org")
        compose.onNodeWithTag("files.move.confirm").performClick()
        compose.onNodeWithTag("files.move.name").assertIsNotEnabled()
        compose.onNodeWithTag("files.move.cancel").assertIsNotEnabled()
        compose.runOnIdle { pending.completeExceptionally(IllegalStateException("Move rejected")) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Move rejected").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("files.move.name").assertTextContains("renamed.org")
        compose.onNodeWithTag("files.move.confirm").assertIsEnabled()
        compose.runOnIdle { assertFalse(closed) }
        compose.onNodeWithTag("files.move.confirm").performClick()
        compose.runOnIdle {
            assertTrue(closed)
            assertEquals(2, attempts)
        }
    }
}
