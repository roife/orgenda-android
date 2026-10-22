package com.roifewu.orgenda.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ParityRegressionTest {
    private val config = WorkspaceConfig.defaults()

    @Test
    fun journalUsesIosYearFileAndDayTimeHeadings() =
        NativeOrgEngine().use { engine ->
            val date = LocalDateTime.of(2026, 10, 22, 9, 35)
            val source =
                JournalIndex.append(
                    engine.parse("journal/2026.org", "", config),
                    date,
                    "",
                    "中文🧪\n*** Nested\nnotes",
                )
            assertTrue(source.startsWith("* 2026-10-22 Thu\n** 09:35 Journal entry\n"))
            val entries =
                JournalIndex.entries(engine.parse("journal/2026.org", source, config), "journal")
            assertEquals(1, entries.size)
            assertEquals(date, entries.single().date)
            assertTrue(entries.single().body.contains("*** Nested"))
            val shifted =
                JournalIndex.entries(
                    engine.parse("journal/2026.org", "#+TITLE: 日记\n" + source, config),
                    "journal",
                )
            assertEquals(entries.single().id, shifted.single().id)
        }

    @Test
    fun journalSupportsFilenamesAndNestedDefaultJournalFolders() =
        NativeOrgEngine().use { engine ->
            for (filename in listOf("20261022", "2026-10-22")) {
                val document =
                    engine.parse("notes/Journal/$filename.org", "Plain journal 🧪", config)
                assertEquals(
                    LocalDate.of(2026, 10, 22),
                    JournalIndex.entries(document, "journal").single().date.toLocalDate(),
                )
            }
            val document =
                engine.parse(
                    "notes/2026.org",
                    "* 2026-10-22 Thu\n** 13:20 First\nA\n** 13:20 Second\nB\n",
                    config,
                )
            assertEquals(2, JournalIndex.entries(document, "notes").size)
            assertTrue(JournalIndex.entries(document, "journal").isEmpty())
        }

    @Test
    fun calendarPagingPreservesPreferredDayAndYearDoesNotSelect() {
        val january = CalendarPosition(LocalDate.of(2028, 1, 31))
        val february = january.page(CalendarDensity.MONTH, 1)
        assertEquals(LocalDate.of(2028, 2, 29), february.selected)
        assertEquals(LocalDate.of(2028, 3, 31), february.page(CalendarDensity.MONTH, 1).selected)
        assertEquals(january.selected, january.page(CalendarDensity.YEAR, 5).selected)
        assertEquals(
            january.selected.dayOfWeek,
            january.page(CalendarDensity.WEEK, 1).selected.dayOfWeek,
        )
        assertEquals(
            12,
            february
                .select(LocalDate.of(2028, 2, 12))
                .page(CalendarDensity.MONTH, 1)
                .selected
                .dayOfMonth,
        )
    }

    @Test
    fun calendarProjectsPrimaryRepeaterAndMultidayEvent() =
        NativeOrgEngine().use { engine ->
            val document =
                engine.parse(
                    "calendar.org",
                    "* Trip\n<2026-10-22 Thu 09:00>--<2026-10-25 Sun 11:00>\n* TODO Work\nSCHEDULED: <2026-10-22 Thu 09:00 +1w> DEADLINE: <2026-10-23 Fri +1d>\n",
                    config,
                )
            val event = document.headings[0]
            val task = document.headings[1]
            assertEquals(4440L, event.event!!.durationMinutes)
            assertNotNull(Agenda.project(event, LocalDate.of(2026, 10, 24)))
            assertNull(Agenda.project(task, LocalDate.of(2026, 10, 24)))
            assertEquals(
                LocalDate.of(2026, 10, 29),
                Agenda.project(task, LocalDate.of(2026, 10, 29))!!.scheduled!!.date.toLocalDate(),
            )
            assertFalse(Agenda.overdue(event, LocalDate.of(2026, 11, 1)))
            assertTrue(Agenda.overdue(task, LocalDate.of(2026, 11, 1)))
        }

    @Test
    fun timestampEditingKeepsRangesWarningSpacingAndWeekdayStyle() {
        val raw = "<2026-10-22\tTHURSDAY\t09:00-10:30  +1w -2d>"
        val stamp = OrgStamp.parse(raw, 0, raw.length)!!
        assertEquals(
            "<2026-10-23\tFRIDAY\t10:00-11:30  ++1w -2d>",
            stamp.editing(LocalDateTime.of(2026, 10, 23, 10, 0), true, "++1w"),
        )
        assertEquals(90L, stamp.durationMinutes)
        val noRepeat = OrgStamp.parse("<2026-10-22 Thu 09:00-10:00 -2d>", 0, 0)!!
        assertEquals(
            "<2026-10-22 Thu 09:00-10:00 +1w -2d>",
            noRepeat.editing(noRepeat.date, true, "+1w"),
        )
    }

    @Test
    fun editingNotesKeepsMetadataSourceBlocksAndTimestampOnlyParagraph() =
        NativeOrgEngine().use { engine ->
            val text =
                "* TODO 记录\n<2026-10-22 Thu>\nFirst\n#+begin_src kotlin\nval unknown = 1\n#+end_src\nSecond\n:LOGBOOK:\n- Unknown log\n:END:\n"
            val doc = engine.parse("tasks.org", text, config)
            val edited = engine.replaceBody(doc, doc.headings.single(), "New notes")
            assertTrue(edited.contains("<2026-10-22 Thu>"))
            assertTrue(edited.contains("#+begin_src kotlin\nval unknown = 1\n#+end_src"))
            assertTrue(edited.contains(":LOGBOOK:\n- Unknown log\n:END:"))
            assertFalse(edited.contains("First"))
            assertFalse(edited.contains("Second"))
        }

    @Test
    fun unchangedItemCanRebaseAfterUnrelatedEditButAmbiguityFails() =
        NativeOrgEngine().use { engine ->
            val before = engine.parse("tasks.org", "* TODO First\n* TODO 第二\nBody\n", config)
            val old = before.headings[1]
            val after =
                engine.parse(
                    "tasks.org",
                    "#+TITLE: 新文档\n* TODO First renamed\n* TODO 第二\nBody\n",
                    config,
                )
            assertTrue(engine.resolve(after, old).start > old.start)
            val duplicate = engine.parse("tasks.org", "* TODO 第二\nBody\n* TODO 第二\nBody\n", config)
            assertThrows(IllegalArgumentException::class.java) { engine.resolve(duplicate, old) }
            Unit
        }

    @Test
    fun inputCommandsUseLinesAndPreserveUnicodeCrLf() {
        val source = "中文🧪\r\nnext\r\nlast"
        val edited =
            OrgInputCommands.apply(source, 1, source.indexOf("last"), InputCommand.CHECKBOX)
        assertEquals("- [ ] 中文🧪\r\n- [ ] next\r\nlast", edited.text)
        val checked = "  9. [X] 中文🧪"
        assertEquals(
            "  9. [X] 中文🧪\n  10. [ ] ",
            OrgInputCommands.newline(checked, checked.length, checked.length)!!.text,
        )
        assertEquals("", OrgInputCommands.newline("- [ ] ", 6, 6)!!.text)
        val block = "#+begin_src\n- text\n#+end_src\n"
        assertNull(
            OrgInputCommands.newline(block, block.indexOf("\n#+end"), block.indexOf("\n#+end"))
        )
    }

    @Test
    fun searchMatchesAccentsWithoutCorruptingSourceOffsets() {
        val source = "🧪 cafe\u0301 中文 CAFÉ"
        val range = SearchText.range(source, "café")!!
        assertEquals("cafe\u0301", source.substring(range))
        assertEquals(5, SourceText(source).byteOffset(range.first))
        assertTrue(SearchText.matches(source, "CAFE"))
        assertFalse(SearchText.matches(source, "missing"))
    }
}
