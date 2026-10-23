package com.roifewu.orgenda.core

import kotlin.test.*
import org.junit.Test

class OrgResolutionTest {
    @Test
    fun fileLinksResolveRelativeToTheOriginAndKeepTheirSearch() {
        assertEquals(
            OrgLink("archive/中文.org", "* Finished"),
            OrgLink.parse("notes/tasks.org", "file:../archive/中文.org::* Finished"),
        )
        assertEquals(OrgLink("notes/tasks.org", "#stable"), OrgLink.parse("notes/tasks.org", "#stable"))
        assertEquals(OrgLink("notes/tasks.org", "* Task"), OrgLink.parse("notes/tasks.org", "* Task"))
        assertNull(OrgLink.parse("notes/tasks.org", "other.org").search)
    }

    @Test
    fun linksRejectPathsOutsideTheWorkspace() {
        for (target in listOf("../../outside.org", "/outside.org", "file:../../outside.org", "file:../.."))
            assertFailsWith<IllegalArgumentException>(target) {
                OrgLink.parse("notes/tasks.org", target)
            }
        assertFailsWith<IllegalArgumentException> { OrgLink.parse("tasks.org", "~/outside.org") }
    }

    @Test
    fun lineLinksPreserveUnicodeOffsetsAndEveryNewlineConvention() {
        for (newline in listOf("\n", "\r\n", "\r")) {
            val text = "中文😀${newline}Second${newline}尾"
            val source = SourceText(text)
            assertEquals(0, OrgLink("a.org", "0").byteOffset(null, text))
            assertEquals(
                source.byteOffset(text.indexOf("Second")),
                OrgLink("a.org", "2").byteOffset(null, text),
            )
            assertEquals(
                source.byteOffset(text.indexOf("尾")),
                OrgLink("a.org", "3").byteOffset(null, text),
            )
            assertEquals(source.bytes.size, OrgLink("a.org", "99").byteOffset(null, text))
        }
    }

    @Test
    fun headingAndIdLinksResolveWithinTheDocument() =
        NativeOrgEngine().use { engine ->
            val document =
                engine.parse(
                    "a.org",
                    "* First\n* 中文😀\n:PROPERTIES:\n:ID: stable\n:CUSTOM_ID: custom\n:END:\n",
                )
            val start = document.headings.last().start
            for (search in listOf("* 中文😀", "#stable", "#custom"))
                assertEquals(start, OrgLink("a.org", search).byteOffset(document))
            assertNull(OrgLink("a.org", "#missing").byteOffset(document))
            assertNull(OrgLink("a.org", null).byteOffset(document))
        }

    @Test
    fun explicitTargetsTakePrecedenceOverFoldedTextSearch() =
        NativeOrgEngine().use { engine ->
            val document = engine.parse("a.org", "* 中文\nanchor appears first\n<<anchor>>\nCafé\n")
            assertEquals(
                document.syntax.source.byteOffset(document.text.indexOf("<<anchor>>")),
                OrgLink("a.org", "anchor").byteOffset(document),
            )
            assertEquals(
                document.syntax.source.byteOffset(document.text.indexOf("Café")),
                OrgLink("a.org", "cafe").byteOffset(document),
            )
            assertNull(OrgLink("a.org", "missing").byteOffset(document))
        }

    @Test
    fun archiveDefaultsExpandTheSourceFilenameRelativeToItsFolder() =
        NativeOrgEngine().use { engine ->
            val document = engine.parse("notes/tasks.org", "* TODO Task\n")
            assertEquals(
                ArchiveTarget("notes/tasks.org_archive", "* Finished"),
                ArchiveTarget.resolve(document, document.headings.single(), "%s_archive::* Finished"),
            )
        }

    @Test
    fun nearestArchivePropertyOverridesFileAndWorkspaceRules() =
        NativeOrgEngine().use { engine ->
            val document =
                engine.parse(
                    "notes/tasks.org",
                    "#+ARCHIVE: file.org::\n* Parent\n:PROPERTIES:\n:ARCHIVE: parent.org::\n:END:\n** TODO Child\n:PROPERTIES:\n:ARCHIVE: ../done.org::* Done\n:END:\n** TODO Sibling\n* TODO Other\n",
                )
            val targets = document.headings.map { ArchiveTarget.resolve(document, it, "default.org::") }
            assertEquals(
                listOf("notes/parent.org", "done.org", "notes/parent.org", "notes/file.org"),
                targets.map { it.path },
            )
            assertEquals("* Done", targets[1].heading)
        }

    @Test
    fun archiveDeclarationsInsideLiteralBlocksDoNotOverrideDefaults() =
        NativeOrgEngine().use { engine ->
            val document =
                engine.parse("a.org", "#+begin_src org\n#+ARCHIVE: ignored.org::\n#+end_src\n* TODO Task\n")
            assertEquals(
                ArchiveTarget("default.org", ""),
                ArchiveTarget.resolve(document, document.headings.single(), "default.org::"),
            )
        }

    @Test
    fun archiveRejectsAmbiguousDeclarationsAndOutdatedHeadings() =
        NativeOrgEngine().use { engine ->
            val document = engine.parse("a.org", "#+ARCHIVE: a.org::\n#+ARCHIVE: b.org::\n* TODO Task\n")
            assertFailsWith<IllegalArgumentException> {
                ArchiveTarget.resolve(document, document.headings.single(), "default.org::")
            }
            val newer = engine.parse("a.org", "* TODO Updated\n")
            assertFailsWith<IllegalArgumentException> {
                ArchiveTarget.resolve(newer, document.headings.single(), "default.org::")
            }
            Unit
        }

    @Test
    fun archiveRejectsUnsafePathsAndMalformedRules() =
        NativeOrgEngine().use { engine ->
            val document = engine.parse("notes/a.org", "* TODO Task\n")
            val invalidRules =
                listOf(
                    "../../outside.org::",
                    "/outside.org::",
                    "~/outside.org::",
                    "a.md::",
                    "a.org::*",
                    "a.org::Title",
                    "a.org::x::y",
                    "%x.org::",
                )
            for (rule in invalidRules) {
                assertFailsWith<IllegalArgumentException>(rule) {
                    ArchiveTarget.resolve(document, document.headings.single(), rule)
                }
            }
        }
}
