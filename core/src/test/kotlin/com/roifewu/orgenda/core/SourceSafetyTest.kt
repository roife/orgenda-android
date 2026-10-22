package com.roifewu.orgenda.core

import java.time.*
import kotlin.test.*
import org.junit.Test

class SourceSafetyTest {
    @Test
    fun checkboxOnlyChangesTheStatusByte() {
        NativeOrgEngine().use { engine ->
            val source = "* TODO A\r\n- [ ]  中文😀\r\n- [X]\tOther\r\n"
            val doc = engine.parse("a.org", source)
            val boxes = doc.syntax.nodes.filter { it.type == "checkbox" }
            assertEquals(
                source.replace("[ ]", "[X]"),
                engine.toggleCheckbox(doc, boxes.first().start, true),
            )
            assertEquals(
                source.replace("[X]", "[ ]"),
                engine.toggleCheckbox(doc, boxes.last().start, false),
            )
        }
    }

    @Test
    fun utf8PatchesPreserveAdjacentCombiningCharacters() {
        val s = SourceText("中😀e\u0301\r\nTODO")
        assertEquals(
            "中😀e\u0301\r\nDONE",
            s.apply(
                listOf(SourcePatch(s.byteOffset(s.text.indexOf("TODO")), s.bytes.size, "DONE"))
            ),
        )
        assertFails { s.apply(listOf(SourcePatch(1, 2, "x"))) }
        assertFails { s.byteOffset(2) }
        assertFails { s.apply(listOf(SourcePatch(-1, 0, "x"))) }
    }

    @Test
    fun parserDoesNotTruncateEmbeddedNull() {
        OrgParser().use { p ->
            val doc = p.parse("* TODO A\nbody\u0000text\n* TODO B\n")
            assertEquals(2, doc.blocks.count { it.type == "heading" })
            assertEquals(doc.source.bytes.size, doc.nodes.first().end)
        }
    }

    @Test
    fun metadataAndLiteralBlocksSurviveHeadingEdit() {
        NativeOrgEngine().use { e ->
            val source =
                "* TODO [#A] 中文😀 :work:\r\n:PROPERTIES:\r\n:ID: x\r\n:END:\r\n#+begin_src org\r\n* literal\r\n#+end_src\r\n* NEXT Keep\r\n"
            val d = e.parse("a.org", source)
            assertEquals(2, d.headings.size)
            val edited = e.changeItem(d, d.headings.first(), title = "新标题")
            assertEquals(source.replace("中文😀", "新标题"), edited)
        }
    }

    @Test
    fun staleOrAmbiguousStructuralEditsAreRejected() {
        NativeOrgEngine().use { e ->
            val a = e.parse("a.org", "* TODO Café\n")
            val b = e.parse("a.org", "* TODO Cafe\u0301\n")
            assertFails { e.changeItem(b, a.headings.first(), title = "x") }
        }
    }

    @Test
    fun fileWorkflowOnlyReadsTopLevelKeywords() {
        NativeOrgEngine().use { e ->
            val a =
                e.parse(
                    "a.org",
                    "#+TODO: NEXT(n) | FINISHED(f)\n* NEXT Yes\n#+begin_src org\n#+TODO: WRONG | BAD\n#+end_src\n* TODO Literal\n",
                )
            assertEquals("NEXT", a.headings[0].state)
            assertEquals("", a.headings[1].state)
            assertEquals("TODO Literal", a.headings[1].title)
        }
    }

    @Test
    fun subtreeMoveKeepsLiteralStars() {
        NativeOrgEngine().use { e ->
            val a =
                e.parse(
                    "a.org",
                    "* TODO A\n#+begin_src org\n* literal\n#+end_src\n** Child\n* Keep\n",
                )
            val b = e.parse("b.org", "* Parent\n")
            val (source, dest) = e.moveSubtree(a, a.headings.first(), b, b.headings.first())
            assertEquals("* Keep\n", source)
            assertTrue(dest.contains("** TODO A\n"))
            assertTrue(dest.contains("\n* literal\n"))
            assertTrue(dest.contains("*** Child\n"))
        }
    }

    @Test
    fun captureDoesNotReexpandInsertedAnswers() {
        val template = "* TODO %^{Title}\n%?"
        val prompt = CaptureEngine.expansions(template).first()
        assertEquals(
            "* TODO %U\n中😀",
            CaptureEngine.render(template, mapOf(prompt.start to "%U"), "中😀"),
        )
        assertFails { CaptureEngine.expansions("%(shell-command)") }
    }

    @Test
    fun capturePreservesPreambleAndCrLf() {
        NativeOrgEngine().use { e ->
            val doc = e.parse("a.org", "#+TITLE: Inbox\r\n* Old\r\n")
            val text =
                CaptureEngine.insert(
                    "* TODO New",
                    CaptureTemplate(prepend = true, emptyLines = 0),
                    doc,
                )
            assertEquals("#+TITLE: Inbox\r\n* TODO New\r\n* Old\r\n", text)
        }
    }

    @Test
    fun configurationDefaultsNullsAndEmptyArraysMatchIos() {
        val c =
            ConfigurationCodec.decode(
                """{"version":1,"files":null,"agenda":{"sources":[]},"unknown":1}"""
            )
        assertEquals("inbox.org", c.files.inbox)
        assertEquals(emptyList(), c.agenda.sources)
        assertFalse(ConfigurationCodec.encode(c).contains("unknown"))
        assertFails { ConfigurationCodec.decode("""{"version":null}""") }
        assertFails { ConfigurationCodec.decode("""{"version":"1"}""") }
        assertFails { ConfigurationCodec.decode("""{"version":1,"workflow":{"sequences":[]}}""") }
        assertFails {
            ConfigurationCodec.decode("""{"version":1,"files":{"inbox":"../outside.org"}}""")
        }
        assertFails {
            ConfigurationCodec.decode("""{"version":1,"reminders":{"repeatMinutes":0}}""")
        }
    }

    @Test
    fun repeatersPreserveCadenceAndLocalTime() {
        val zone = ZoneId.of("America/New_York")
        val base = LocalDateTime.of(2024, 3, 9, 9, 30).atZone(zone)
        val later = LocalDateTime.of(2024, 3, 12, 12, 0).atZone(zone)
        assertEquals(
            LocalDateTime.of(2024, 3, 10, 9, 30),
            OrgRepeater.parse("+1d")!!.next(base, later).toLocalDateTime(),
        )
        assertEquals(
            LocalDateTime.of(2024, 3, 13, 9, 30),
            OrgRepeater.parse("++1d")!!.next(base, later).toLocalDateTime(),
        )
        assertEquals(
            LocalDateTime.of(2024, 3, 13, 9, 30),
            OrgRepeater.parse(".+1d")!!.next(base, later).toLocalDateTime(),
        )
        val leap = LocalDateTime.of(2024, 2, 29, 8, 0).atZone(zone)
        assertEquals(
            LocalDate.of(2025, 2, 28),
            OrgRepeater.parse("+1y")!!.next(leap, later).toLocalDate(),
        )
    }

    @Test
    fun disjointTextChangesMergeButOverlapDoesNot() {
        assertEquals("A\nb\nC\n", TextMerge.merge("a\nb\nc\n", "A\nb\nc\n", "a\nb\nC\n"))
        assertNull(TextMerge.merge("a\n", "中文\n", "😀\n"))
        assertNull(TextMerge.merge("x\n", "é\n", "e\u0301\n"))
    }

    @Test
    fun timestampMutationRetainsRangeWarningAndOriginalSpacing() {
        val stamp = OrgStamp.parse("<2026-10-22 Thu\t09:30-10:45 ++1w -2d>", 0, 43)!!
        assertEquals(
            "<2026-10-23 Fri\t11:00-12:15 ++1w -2d>",
            stamp.replacingDate(LocalDateTime.of(2026, 10, 23, 11, 0)),
        )
        val bare = OrgStamp.parse("<2026-10-22 +1d>", 0, 17)!!
        assertEquals("<2026-10-23 +1d>", bare.replacingDate(LocalDateTime.of(2026, 10, 23, 0, 0)))
    }

    @Test
    fun unicodePositionMappingsRoundTripAtEveryScalarBoundary() {
        val s = SourceText("a中😀e\u0301\r\n🧪".repeat(40))
        for (offset in 0..s.bytes.size) if (s.boundary(offset))
            assertEquals(offset, s.byteOffset(s.utf16(offset)))
        assertFails { "broken\uD800".utf8Bytes() }
    }

    @Test
    fun recurringCompletionPreservesPropertiesAndUnknownSyntax() {
        NativeOrgEngine().use { engine ->
            val source =
                "* TODO Habit\r\nSCHEDULED: <2026-10-22 Thu 09:00-10:00 +1d -2d>\r\n:PROPERTIES:\r\n:STYLE: habit\r\n:ID: stable\r\n:END:\r\n#+custom: keep\r\n"
            val doc = engine.parse("habit.org", source)
            val result =
                engine.complete(
                    doc,
                    doc.headings.first(),
                    WorkspaceConfig.defaults().copy(logging = Logging(done = "time")),
                    ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneId.of("Asia/Taipei")),
                )
            assertTrue(result.startsWith("* TODO Habit\r\n"))
            assertTrue(result.contains("<2026-10-23 Fri 09:00-10:00 +1d -2d>"))
            assertTrue(
                result.contains(":ID: stable\r\n:LAST_REPEAT: [2026-10-22 Thu 12:00]\r\n:END:\r\n")
            )
            assertTrue(result.endsWith("#+custom: keep\r\n"))
            assertTrue(result.contains("- State \"DONE\""))
        }
    }

    @Test
    fun structuralInsertionAfterUnterminatedHeadingStartsANewLine() {
        NativeOrgEngine().use { engine ->
            val source = "* TODO 中文😀"
            val doc = engine.parse("a.org", source)
            val item = doc.headings.single()
            val planning =
                engine.setPlanning(
                    doc,
                    item,
                    "SCHEDULED",
                    LocalDateTime.of(2026, 10, 22, 0, 0),
                    false,
                )
            assertTrue(planning.startsWith(source + "\nSCHEDULED:"))
            val property = engine.setProperty(doc, item, "ID", "stable")
            assertTrue(property.startsWith(source + "\n:PROPERTIES:"))
            assertEquals(
                "stable",
                engine.parse("a.org", property).headings.single().properties["ID"],
            )
            val logged =
                engine.complete(
                    doc,
                    item,
                    WorkspaceConfig.defaults().copy(logging = Logging(done = "time")),
                )
            assertTrue(logged.startsWith("* DONE 中文😀\nCLOSED:"))
            assertTrue(engine.parse("a.org", logged).headings.single().done)
        }
    }

    @Test
    fun notesCannotCloseDrawersAndOnlyOwnPropertiesDefineHabits() {
        NativeOrgEngine().use { engine ->
            val config = WorkspaceConfig.defaults().copy(logging = Logging(done = "note"))
            val doc =
                engine.parse(
                    "a.org",
                    "* TODO Parent\r\n:PROPERTIES:\r\n:STYLE: habit\r\n:END:\r\n** TODO Child\r\n",
                    config,
                )
            assertTrue(doc.headings.first().habit)
            assertFalse(doc.headings.last().habit)
            val result =
                engine.complete(doc, doc.headings.first(), config, note = "one\n:END:\ntwo")
            assertTrue(result.contains("  ,:END:\r\n"))
            val parsed = engine.parse("a.org", result, config)
            assertEquals(2, parsed.headings.size)
            assertFalse(parsed.syntax.blocks.any { it.error })
        }
    }

    @Test
    fun recurringEventKeepsItsNonTaskIdentity() {
        NativeOrgEngine().use { engine ->
            val config = WorkspaceConfig.defaults()
            val doc = engine.parse("events.org", "* Meeting\n<2026-10-22 Thu 14:00 +1w>\n", config)
            val result = engine.complete(doc, doc.headings.single(), config)
            val item = engine.parse("events.org", result).headings.single()
            assertFalse(item.isTask)
            assertEquals("Meeting", item.title)
            assertEquals(
                LocalDate.of(2026, 10, 29),
                item.stamps.first { it.active }.date.toLocalDate(),
            )
            assertNotNull(item.properties["LAST_REPEAT"])
        }
    }

    @Test
    fun configPathsAndGraphemeShortcutsMatchIos() {
        val config =
            ConfigurationCodec.decode(
                """{"version":1,"files":{"journal":"./journal/","attachments":".attach//"},"agenda":{"sources":["work/"],"excluded":[]}}"""
            )
        assertEquals("./journal/", config.files.journal)
        WorkspaceConfig.shortcut("👩‍💻")
        WorkspaceConfig.shortcut("e\u0301")
        assertFails { WorkspaceConfig.shortcut("ab") }
        assertFails {
            ConfigurationCodec.decode("""{"version":1,"workflow":{"sequences":[{"id":""}]}}""")
        }
        assertFails { ConfigurationCodec.decode("""{"version":1,"files":{"journal":"../"}}""") }
    }
}
