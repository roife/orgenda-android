package com.roifewu.orgenda.core

import org.junit.Assert.*
import org.junit.Test

class OutlineIndentationTest {
    @Test
    fun movingAndReparentingPreservesIndentedBodyTextAndCrLf() {
        NativeOrgEngine().use { engine ->
            val first = "* First\r\n   Source 中文 body\r\n"
            val second = "* Second\r\n   Destination body\r\n"
            val document = engine.parse("outline.org", first + second)
            val reordered = Outline.move(document, document.headings[0], document.headings[1], HeadingPlacement.AFTER)
            assertEquals(second + first, reordered.text)
            val nested = Outline.move(document, document.headings[0], document.headings[1], HeadingPlacement.CHILD)
            assertEquals(second + "*" + first, nested.text)
            val updated = engine.parse("outline.org", nested.text)
            assertEquals(2, updated.heading(nested.starts.getValue(document.headings[0].start)).level)
            val promoted = Outline.move(updated, updated.headings[1], updated.headings[0], HeadingPlacement.BEFORE)
            assertEquals(first + second, promoted.text)
        }
    }

    @Test
    fun orphanDrawerDelimitersStillPreventMoving() {
        NativeOrgEngine().use { engine ->
            val document = engine.parse("outline.org", "* First\n   Valid body\n:END:\n* Second\n")
            assertThrows(IllegalArgumentException::class.java) {
                Outline.move(document, document.headings[0], document.headings[1], HeadingPlacement.AFTER)
            }
        }
    }

    @Test
    fun unfinishedLiteralBlocksAndDrawersAreStillUnsafe() {
        NativeOrgEngine().use { engine ->
            for (text in listOf(
                "* First\n   Valid body\n#+begin_src org\nunfinished\n",
                "* First\n   Valid body\n:LOGBOOK:\nunfinished\n",
            )) {
                val document = engine.parse("outline.org", text)
                assertThrows(IllegalArgumentException::class.java) {
                    document.syntax.requireSafe(0, document.syntax.source.bytes.size)
                }
            }
        }
    }
}
