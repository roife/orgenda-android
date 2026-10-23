package com.roifewu.orgenda.ui

import android.content.Context
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.graphics.Typeface
import android.text.*
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.inputmethod.BaseInputConnection
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.doOnPreDraw
import com.roifewu.orgenda.core.*
import kotlinx.coroutines.*

data class TextSnapshot(val text: String, val start: Int, val end: Int)

class EditorHistory {
    val undo = ArrayDeque<TextSnapshot>()
    val redo = ArrayDeque<TextSnapshot>()
    var source: String? = null
    var selectionStart = 0
    var selectionEnd = 0

    fun acceptSource(value: String) {
        if (source != null && source != value) {
            undo.clear()
            redo.clear()
        }
        source = value
    }

    fun acceptLocalChange(before: String, after: String) {
        if (before == after) return
        acceptSource(before)
        push(
            TextSnapshot(
                before,
                selectionStart.coerceIn(0, before.length),
                selectionEnd.coerceIn(0, before.length),
            )
        )
        val prefix = before.commonPrefixWith(after).length
        val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
        fun mapped(offset: Int): Int =
            when {
                offset <= prefix -> offset
                offset >= before.length - suffix -> offset + after.length - before.length
                else -> prefix + minOf(offset - prefix, after.length - prefix - suffix)
            }
        selectionStart = mapped(selectionStart).coerceIn(0, after.length)
        selectionEnd = mapped(selectionEnd).coerceIn(0, after.length)
        source = after
    }

    fun push(value: TextSnapshot) {
        if (undo.lastOrNull()?.text != value.text) undo.addLast(value)
        while (undo.size > 100 || undo.sumOf { it.text.length.toLong() } > 8 * 1024 * 1024) undo
            .removeFirst()
        redo.clear()
    }
}

private interface SyntaxDecoration

private class SyntaxColor(color: Int) : ForegroundColorSpan(color), SyntaxDecoration

private class SyntaxBackground(color: Int) : BackgroundColorSpan(color), SyntaxDecoration

private class SyntaxStyle(style: Int) : StyleSpan(style), SyntaxDecoration

private class SyntaxUnderline : UnderlineSpan(), SyntaxDecoration

private class SyntaxStrike : StrikethroughSpan(), SyntaxDecoration

class NativeSourceEditor(context: Context, val history: EditorHistory) :
    AppCompatEditText(context) {
    var changed: (String, Int) -> Unit = { _, _ -> }
    var positionChanged: ((Int) -> Unit)? = null
    var historyChanged: ((Boolean, Boolean) -> Unit)? = null
    private var restoringPosition = false
    private var positionRequest = 0
    val canUndo
        get() = history.undo.isNotEmpty() && !hasComposition()

    val canRedo
        get() = history.redo.isNotEmpty() && !hasComposition()

    private fun reportHistory() {
        historyChanged?.invoke(canUndo, canRedo)
    }

    private var programmatic = false
    private var before: TextSnapshot? = null
    private var composing: TextSnapshot? = null
    private var typingText: String? = null
    private var typingCaret = -1
    private var typingTime = 0L
    private var changedCaret = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val parser = OrgParser()
    private var highlight: Job? = null
    private var generation = 0
    private val initialColors = UiColors(
        if (resources.configuration.isNightModeActive) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context)
    )
    var accent = initialColors.sourceAccent
        set(value) {
            if (field != value) {
                field = value
                scheduleHighlight()
            }
        }

    var codeAccent = initialColors.sourceCodeAccent
        set(value) {
            if (field != value) {
                field = value
                scheduleHighlight()
            }
        }

    var syntaxColors: Map<String, Int> = initialColors.sourceSyntax(Workflow())
        set(value) {
            if (field != value) {
                field = value
                scheduleHighlight()
            }
        }

    init {
        typeface = Typeface.MONOSPACE
        textSize = DocumentTypography.Callout
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        inputType =
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(initialColors.sourceForeground)
        val pad = (UiSpacing.edge.value * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                    if (!programmatic)
                        before =
                            TextSnapshot(
                                s?.toString() ?: "",
                                selectionStart.coerceAtLeast(0),
                                selectionEnd.coerceAtLeast(0),
                            )
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    changedCaret = start + count
                }

                override fun afterTextChanged(s: Editable?) {
                    if (programmatic || s == null) return
                    val active = BaseInputConnection.getComposingSpanStart(s) >= 0
                    if (active) {
                        if (composing == null) composing = before
                    } else {
                        (composing ?: before)?.let { recordTyping(it, changedCaret) }
                        composing = null
                    }
                    val string = s.toString()
                    history.source = string
                    changed(
                        string,
                        runCatching {
                                SourceText(string)
                                    .byteOffset(selectionStart.coerceIn(0, string.length))
                            }
                            .getOrDefault(0),
                    )
                    reportHistory()
                    scheduleHighlight()
                }
            }
        )
    }

    private fun recordTyping(previous: TextSnapshot, caret: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (
            previous.text != typingText ||
                previous.start != typingCaret ||
                previous.end != typingCaret ||
                now - typingTime >= 700
        )
            history.push(previous)
        else history.redo.clear()
        typingText = text?.toString()
        typingCaret = caret
        typingTime = now
    }

    fun finishTyping() {
        typingText = null
        typingCaret = -1
    }

    fun suspendInput() {
        clearComposingText()
        finishComposition()
        finishTyping()
    }

    fun resumeKeyboard() {
        post {
            if (isAttachedToWindow) {
                requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun finishComposition() {
        if (!hasComposition()) {
            composing?.let { recordTyping(it, selectionStart.coerceAtLeast(0)) }
            composing = null
            reportHistory()
        }
    }

    fun display(source: String, jumpByte: Int? = null) {
        if (hasComposition() && text?.toString() != source) return
        // A new backing version must not be overwritten by snapshots of an older
        // version. Recreating a view for the same draft keeps its document history.
        history.acceptSource(source)
        if (text?.toString() != source) {
            finishTyping()
            programmatic = true
            setText(source)
            programmatic = false
            scheduleHighlight()
        }
        reportHistory()
        if (jumpByte != null) {
            finishTyping()
            val mapping = SourceText(source)
            var byte = jumpByte.coerceIn(0, mapping.bytes.size)
            while (!mapping.boundary(byte)) byte--
            val offset = mapping.utf16(byte)
            restoringPosition = true
            val request = ++positionRequest
            setSelection(offset)
            // TextView moves its caret into view during pre-draw, adding a half-line
            // margin. Restore after those callbacks so that margin cannot move our
            // saved reading line back by one line during view recreation.
            doOnPreDraw {
                post {
                    if (request != positionRequest) return@post
                    layout?.let {
                        val maximum =
                            (it.height - (height - totalPaddingTop - totalPaddingBottom))
                                .coerceAtLeast(0)
                        scrollTo(
                            0,
                            it.getLineTop(it.getLineForOffset(offset.coerceAtMost(length())))
                                .coerceAtMost(maximum),
                        )
                    }
                    restoringPosition = false
                }
            }
            // An unchanged selection does not schedule a draw for the pending jump.
            invalidate()
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!programmatic && !restoringPosition && selStart >= 0) {
            if (
                !hasComposition() &&
                    typingText != null &&
                    text?.toString() == typingText &&
                    (selStart != typingCaret || selEnd != typingCaret)
            )
                finishTyping()
            captureReadingPosition()
        }
        // Selection is part of the document session even while Preview owns UI.
        // AppCompat can call this override before this class is initialized.
        if (text != null && selStart >= 0 && selEnd >= 0) {
            history?.selectionStart = selStart
            history?.selectionEnd = selEnd
        }
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (!restoringPosition) captureReadingPosition()
    }

    fun captureReadingPosition() {
        if (restoringPosition) return
        val value = text?.toString() ?: return
        val offset =
            layout?.let { it.getLineStart(it.getLineForVertical(scrollY.coerceAtLeast(0))) }
                ?: selectionStart.coerceAtLeast(0)
        runCatching { SourceText(value).byteOffset(offset.coerceIn(0, value.length)) }
            .getOrNull()
            ?.let { positionChanged?.invoke(it) }
    }

    fun selectSearchMatch(query: String): Boolean {
        if (hasComposition()) return false
        val match = SearchText.range(text?.toString().orEmpty(), query) ?: return false
        finishTyping()
        positionRequest++
        restoringPosition = false
        setSelection(match.first, match.last + 1)
        post {
            bringPointIntoView(match.first)
            captureReadingPosition()
        }
        return true
    }

    fun undo() {
        finishComposition()
        if (!canUndo) return
        finishTyping()
        history.redo.addLast(TextSnapshot(text.toString(), selectionStart, selectionEnd))
        restore(history.undo.removeLast())
    }

    fun redo() {
        finishComposition()
        if (!canRedo) return
        finishTyping()
        history.undo.addLast(TextSnapshot(text.toString(), selectionStart, selectionEnd))
        restore(history.redo.removeLast())
    }

    private fun restore(value: TextSnapshot) {
        programmatic = true
        setText(value.text)
        setSelection(value.start.coerceIn(0, length()), value.end.coerceIn(0, length()))
        programmatic = false
        history.source = value.text
        changed(value.text, SourceText(value.text).byteOffset(selectionStart))
        reportHistory()
        scheduleHighlight()
    }

    fun insert(
        value: String,
        selectionStart: Int = this.selectionStart,
        selectionEnd: Int = this.selectionEnd,
    ) {
        if (hasComposition()) return
        val source = text?.toString().orEmpty()
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, source.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, source.length)
        setSelection(start, end)
        applyInput(
            InputEdit(
                source.replaceRange(start, end, value),
                start + value.length,
                start + value.length,
            )
        )
    }

    fun command(command: InputCommand) {
        if (hasComposition()) return
        applyInput(OrgInputCommands.apply(text.toString(), selectionStart, selectionEnd, command))
    }

    private fun hasComposition() =
        (text?.let { BaseInputConnection.getComposingSpanStart(it) } ?: -1) >= 0

    private fun continueList(): Boolean {
        if (hasComposition()) return false
        val edit =
            OrgInputCommands.newline(text.toString(), selectionStart, selectionEnd) ?: return false
        applyInput(edit)
        return true
    }

    private fun applyInput(edit: InputEdit) {
        finishComposition()
        finishTyping()
        val old = text.toString()
        if (edit.text == old) return
        history.push(TextSnapshot(old, selectionStart, selectionEnd))
        // Keep the native Editable and IME connection alive; only replace the changed span.
        val prefix = old.commonPrefixWith(edit.text).length
        val suffix = old.substring(prefix).commonSuffixWith(edit.text.substring(prefix)).length
        programmatic = true
        beginBatchEdit()
        try {
            text?.replace(
                prefix,
                old.length - suffix,
                edit.text.substring(prefix, edit.text.length - suffix),
            )
            setSelection(edit.start, edit.end)
        } finally {
            endBatchEdit()
            programmatic = false
        }
        history.source = edit.text
        changed(edit.text, SourceText(edit.text).byteOffset(edit.start))
        reportHistory()
        scheduleHighlight()
    }

    override fun onCreateInputConnection(
        outAttrs: android.view.inputmethod.EditorInfo
    ): android.view.inputmethod.InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : android.view.inputmethod.InputConnectionWrapper(connection, false) {
            override fun finishComposingText(): Boolean {
                val result = super.finishComposingText()
                finishComposition()
                return result
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text?.toString() == "\n" && continueList()) return true
                return super.commitText(text, newCursorPosition)
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (
                    event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                        event.action == android.view.KeyEvent.ACTION_DOWN &&
                        continueList()
                )
                    return true
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && continueList()) return true
        if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed) redo() else undo()
            return true
        }
        if (event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_Y) {
            redo()
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_TAB) {
            command(if (event.isShiftPressed) InputCommand.OUTDENT else InputCommand.INDENT)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun scheduleHighlight() {
        highlight?.cancel()
        val revision = ++generation
        highlight = scope.launch {
            delay(120)
            while (hasComposition()) delay(60)
            finishComposition()
            val source = text?.toString() ?: return@launch
            val tokens =
                withContext(Dispatchers.Default) {
                    val syntax = parser.parse(source)
                    syntax.nodes
                        .filter {
                            it.type in
                                setOf(
                                    "heading",
                                    "keyword",
                                    "planning_keyword",
                                    "priority",
                                    "tag_list",
                                    "checkbox",
                                    "source_block",
                                    "example_block",
                                    "export_block",
                                    "fixed_width",
                                    "code",
                                    "verbatim",
                                    "latex_fragment",
                                    "latex_block",
                                    "macro",
                                    "export_snippet",
                                    "property",
                                    "drawer",
                                    "property_drawer",
                                    "clock_keyword",
                                    "clock_duration",
                                    "active_timestamp",
                                    "inactive_timestamp",
                                    "comment",
                                    "comment_block",
                                    "link",
                                    "plain_link",
                                    "angle_link",
                                    "bold",
                                    "italic",
                                    "underline",
                                    "strikethrough",
                                    "unordered_list_marker",
                                    "ordered_list_marker",
                                    "list_counter",
                                    "description_tag",
                                    "statistics_cookie",
                                    "target",
                                    "radio_target",
                                    "table",
                                    "footnote_definition_marker",
                                    "footnote_reference",
                                    "inline_footnote",
                                ) || it.type.endsWith("_begin") || it.type.endsWith("_end")
                        }
                        .map {
                            Triple(
                                syntax.source.utf16(it.start),
                                syntax.source.utf16(it.end),
                                it.type,
                            )
                        }
                }
            if (revision != generation || text?.toString() != source || hasComposition())
                return@launch
            val editable = text ?: return@launch
            programmatic = true
            try {
                editable.getSpans(0, editable.length, SyntaxDecoration::class.java).forEach {
                    editable.removeSpan(it)
                }
                fun color(name: String) = syntaxColors[name] ?: accent
                for ((start, end, kind) in tokens) {
                    if (start == end) continue
                    val raw = source.substring(start, end)
                    fun span(value: Any) {
                        editable.setSpan(value, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val foreground =
                        when (kind) {
                            "heading" ->
                                syntaxColors[
                                    "state:" +
                                        raw.trimStart('*', ' ', '\t').takeWhile {
                                            !it.isWhitespace()
                                        }] ?: accent
                            "priority" ->
                                color(
                                    when {
                                        raw.contains("#A") -> "red"
                                        raw.contains("#B") -> "orange"
                                        raw.contains("#C") -> "blue"
                                        else -> "muted"
                                    }
                                )
                            "checkbox" ->
                                color(
                                    if (raw.contains('X', true)) "green"
                                    else if (raw.contains('-')) "orange" else "muted"
                                )
                            "planning_keyword",
                            "comment",
                            "comment_block" -> color("muted")
                            "active_timestamp",
                            "inactive_timestamp",
                            "clock_keyword",
                            "clock_duration",
                            "unordered_list_marker",
                            "ordered_list_marker",
                            "list_counter",
                            "description_tag" -> color("secondary")
                            "code",
                            "verbatim",
                            "latex_fragment",
                            "latex_block",
                            "macro",
                            "export_snippet" -> codeAccent
                            "source_block",
                            "example_block",
                            "export_block",
                            "fixed_width",
                            "drawer",
                            "property_drawer",
                            "bold",
                            "italic",
                            "underline",
                            "strikethrough" -> null
                            else -> accent
                        }
                    foreground?.let { span(SyntaxColor(it)) }
                    if (
                        kind in
                            listOf(
                                "heading",
                                "priority",
                                "planning_keyword",
                                "clock_keyword",
                                "clock_duration",
                                "description_tag",
                                "checkbox",
                                "statistics_cookie",
                                "target",
                                "radio_target",
                                "keyword",
                            ) || kind.endsWith("_begin") || kind.endsWith("_end")
                    )
                        span(SyntaxStyle(Typeface.BOLD))
                    if (kind == "bold") span(SyntaxStyle(Typeface.BOLD))
                    if (kind in listOf("italic", "comment", "comment_block"))
                        span(SyntaxStyle(Typeface.ITALIC))
                    if (
                        kind in
                            listOf(
                                "underline",
                                "link",
                                "plain_link",
                                "angle_link",
                                "footnote_reference",
                                "inline_footnote",
                            )
                    )
                        span(SyntaxUnderline())
                    if (kind == "strikethrough") span(SyntaxStrike())
                    if (kind in listOf("tag_list", "statistics_cookie"))
                        span(SyntaxBackground(color("tag")))
                    if (
                        kind in
                            listOf(
                                "code",
                                "verbatim",
                                "latex_fragment",
                                "latex_block",
                                "source_block",
                                "example_block",
                                "export_block",
                                "fixed_width",
                                "drawer",
                                "property_drawer",
                            )
                    )
                        span(SyntaxBackground(color("surface")))
                }
            } finally {
                programmatic = false
            }
        }
    }

    fun dispose() {
        positionRequest++
        scope.cancel()
        parser.close()
    }
}
