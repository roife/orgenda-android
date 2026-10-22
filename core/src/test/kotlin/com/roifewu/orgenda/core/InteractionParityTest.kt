package com.roifewu.orgenda.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class InteractionParityTest {
    private val config = WorkspaceConfig.defaults()
    @Test fun moveSubtreesKeepsCrLfAndUnknownBodyBytes() = NativeOrgEngine().use { engine ->
        val source = "#+TITLE: 🧪\r\n* A\r\n** 子\r\n:UNKNOWN:\r\nraw  x\r\n:END:\r\n* B\r\n尾"
        val doc = engine.parse("a.org", source, config)
        val moved = Outline.move(doc, doc.headings[0], doc.headings[2], HeadingPlacement.CHILD)
        assertEquals("#+TITLE: 🧪\r\n* B\r\n尾\r\n** A\r\n*** 子\r\n:UNKNOWN:\r\nraw  x\r\n:END:\r\n", moved.text)
        val parsed = engine.parse("a.org", moved.text, config)
        doc.headings.forEach { before -> assertEquals(before.title, parsed.heading(moved.starts.getValue(before.start)).title) }
        assertThrows(IllegalArgumentException::class.java) { Outline.move(doc, doc.headings[0], doc.headings[1], HeadingPlacement.CHILD) }; Unit
    }
    @Test fun outlineDateFiltersIncludeParentsAndBothBounds() = NativeOrgEngine().use { engine ->
        val doc = engine.parse("a.org", "* Parent\n** TODO First\nSCHEDULED: <2026-10-22 Thu> DEADLINE: <2026-10-25 Sun>\n** DONE Other\nSCHEDULED: <2026-10-22 Thu>\n* Unrelated\n", config)
        val filter = OutlineFilter(setOf("TODO"), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 22), deadlineTo = LocalDate.of(2026, 10, 25))
        assertEquals(listOf("Parent", "First"), filter.apply(doc.headings).map { it.title })
        assertTrue(filter.copy(deadlineFrom = LocalDate.of(2026, 10, 26)).apply(doc.headings).isEmpty())
    }
    @Test fun rangeDraftChangesOnlyChosenEndpointAndKeyword() {
        val raw = "\tSCHEDULED:\t<2026-10-22 THU 09:00 +1w -2d>--<2026-10-24 Sat 10:30>  "
        val draft = PlanningDraft.parse(raw)!!
        assertEquals(raw, draft.source())
        val edited = draft.copy(keyword = "DEADLINE", timestamps = draft.timestamps.mapIndexed { index, stamp -> if (index == 1) stamp.copy(date = stamp.date.plusDays(1)) else stamp })
        assertEquals(raw.replace("SCHEDULED", "DEADLINE").replace("2026-10-24 Sat", "2026-10-25 Sun"), edited.source())
    }
    @Test fun planningLogsQuoteInactiveOriginalAndPrependNewest() = NativeOrgEngine().use { engine ->
        val configured = config.copy(logging = config.logging.copy(reschedule = "time"))
        val source = "* TODO Work\nSCHEDULED: <2026-10-22 Thu 09:00 +1w -2d>\n:LOGBOOK:\n- old\n:END:\n"
        val doc = engine.parse("a.org", source, configured)
        val edited = engine.setPlanning(doc, doc.headings.single(), "SCHEDULED", null, false, config = configured)
        assertTrue(edited.contains("- Not scheduled, was \"[2026-10-22 Thu 09:00 +1w -2d]\" on ["))
        assertTrue(edited.indexOf("- Not scheduled") < edited.indexOf("- old"))
        val fresh = engine.parse("fresh.org", "* TODO Work\n", configured)
        assertFalse(engine.setPlanning(fresh, fresh.headings.single(), "SCHEDULED", LocalDateTime.of(2026, 10, 23, 9, 0), true, config = configured).contains("LOGBOOK"))
    }
    @Test fun imagePathsDecodeOnceAndInheritDirectoryBeforeId() = NativeOrgEngine().use { engine ->
        val source = ":PROPERTIES:\n:DIR: ../images%20literal\n:END:\n* Parent\n:PROPERTIES:\n:ID: abcdef\n:END:\n** Child\n[[attachment:中文%20a%2B.png][Label]]\n[[file:../a%2520b.png]]\n[[https://example.com/中文.png?size=2#x]]\n"
        val doc = engine.parse("notes/a.org", source, config)
        val refs = doc.syntax.nodes.mapNotNull { ImageReference.from(doc.syntax, it) }
        assertEquals(3, refs.size)
        assertEquals(listOf("images%20literal/中文 a+.png"), refs[0].candidates(doc, ".attach"))
        assertEquals(listOf("a%20b.png"), refs[1].candidates(doc, ".attach"))
        assertEquals("Label", refs[0].label)
        assertTrue(refs[2].candidates(doc, ".attach").single().contains("%E4%B8%AD"))
        val withoutDirectory = engine.parse("notes/a.org", source.replace(":DIR: ../images%20literal\n", ""), config)
        val attachment = withoutDirectory.syntax.nodes.mapNotNull { ImageReference.from(withoutDirectory.syntax, it) }.first()
        assertEquals(listOf(".attach/ab/cdef/中文 a+.png", "notes/data/ab/cdef/中文 a+.png"), attachment.candidates(withoutDirectory, ".attach"))
    }
    @Test fun imagePathsRejectEscapesAndRemoteCredentials() = NativeOrgEngine().use { engine ->
        val doc = engine.parse("a.org", "[[file:../secret.png]]\n[[https://user:pass@example.com/x.png]]\n[[file:bad%GG.png]]\n", config)
        val refs = doc.syntax.nodes.mapNotNull { ImageReference.from(doc.syntax, it) }
        assertEquals(1, refs.size)
        assertThrows(IllegalArgumentException::class.java) { refs.single().candidates(doc, ".attach") }
        assertThrows(IllegalArgumentException::class.java) { ImageReference.network("https://user:pass@example.com/x.png") }; Unit
    }
    @Test fun classicPresetMatchesIosAndRejectsOverflowMaximum() {
        val classic = WorkspaceConfig.classic()
        assertEquals(listOf("TODO", "NEXT", "WAIT", "SOMEDAY", "URGENT", "DONE", "CANCELED"), classic.tokens)
        assertEquals("note", classic.style("WAIT").log.enter)
        assertEquals("time", classic.style("WAIT").log.leave)
        assertNull(OrgRepeater.parse("+1d/999999999999999999999999d"))
    }
    @Test fun defaultCompletionDoesNotInventLogsAndFormStateDoesNotAdvanceRepeaters() = NativeOrgEngine().use { engine ->
        val source = "* TODO Cycle\nSCHEDULED: <2026-10-22 Thu +1d>\nbody\n"
        val doc = engine.parse("a.org", source, config)
        val now = ZonedDateTime.parse("2026-10-22T12:00:00+08:00[Asia/Taipei]")
        val form = engine.transition(doc, doc.headings.single(), "DONE", config, now)
        assertEquals(source.replace("TODO", "DONE"), form)
        val quick = engine.complete(doc, doc.headings.single(), config, now)
        assertTrue(quick.contains("SCHEDULED: <2026-10-23 Fri +1d>"))
        assertTrue(quick.startsWith("* TODO Cycle"))
        assertTrue(quick.contains(":LAST_REPEAT: [2026-10-22 Thu 12:00]"))
        assertFalse(quick.contains("CLOSED:")); assertFalse(quick.contains("LOGBOOK"))
    }
    @Test fun completingMultipleRepeatersIsRejectedWithoutPartialEdits() = NativeOrgEngine().use { engine ->
        val doc = engine.parse("a.org", "* TODO Cycle\nSCHEDULED: <2026-10-22 Thu +1d> DEADLINE: <2026-10-24 Sat +1w>\n", config)
        assertThrows(IllegalArgumentException::class.java) { engine.complete(doc, doc.headings.single(), config) }
        assertTrue(doc.text.contains("TODO Cycle"))
    }
    @Test fun paragraphGroupingKeepsAllInteractiveDescendantsAndSeparatesBlankLines() = NativeOrgEngine().use { engine ->
        val doc = engine.parse("a.org", "first [[https://example.com][link]]\nsecond *bold*\n\nthird <2026-10-22 Thu>\n", config)
        val grouped = doc.syntax.groupParagraphs()
        assertEquals(doc.text, grouped.source.text)
        assertEquals(doc.syntax.nodes.filter { it.type == "link" }.map { it.start }, grouped.blocks.flatMap { grouped.descendants(it) }.filter { it.type == "link" }.map { it.start })
        assertEquals(doc.syntax.nodes.filter { it.type == "active_timestamp" }.map { it.start }, grouped.blocks.flatMap { grouped.descendants(it) }.filter { it.type == "active_timestamp" }.map { it.start })
        assertTrue(grouped.blocks.size >= 2)
    }

}
