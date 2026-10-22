package com.roifewu.orgenda.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import io.noties.prism4j.Prism4j

/** Native tokenization and native text spans; no WebView or script execution. */
object CodeHighlight {
    private val cache =
        object : LruCache<String, SpannableString>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: SpannableString) =
                key.length * 2 + value.length * 16
        }

    @Synchronized
    fun text(
        source: String,
        language: String,
        primary: Int,
        secondary: Int,
        tertiary: Int,
        comment: Int,
    ): SpannableString {
        val key = "$language\u0000$primary:$secondary:$tertiary:$comment\u0000$source"
        cache.get(key)?.let {
            return it
        }
        val value = SpannableString(source)
        val normalized =
            when (val name = language.lowercase()) {
                "elisp",
                "emacs-lisp",
                "lisp" -> "clojure"
                "shell-script",
                "sh" -> "bash"
                else -> name
            }
        var offset = 0
        fun visit(nodes: List<Prism4j.Node>) {
            for (node in nodes) when (node) {
                is Prism4j.Text -> offset += node.literal().length
                is Prism4j.Syntax -> {
                    val start = offset
                    visit(node.children())
                    if (offset > start)
                        value.setSpan(
                            ForegroundColorSpan(
                                when (node.type()) {
                                    "comment",
                                    "prolog",
                                    "doctype" -> comment
                                    "string",
                                    "char",
                                    "attr-value",
                                    "regex" -> tertiary
                                    "number",
                                    "boolean",
                                    "constant",
                                    "symbol",
                                    "property" -> secondary
                                    else -> primary
                                }
                            ),
                            start,
                            offset,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                }
            }
        }
        val grammar = CodeGrammars.PARSER.grammar(normalized)
        if (grammar != null) runCatching { visit(CodeGrammars.PARSER.tokenize(source, grammar)) }
        else if (normalized == "bash") {
            // Shell comments and quoting differ from the C-family grammars.
            Regex(
                    """(?m)#[^\n]*$|"(?:[^"\\]|\\.)*"|'[^']*'|\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*|\b(?:if|then|else|elif|fi|for|while|do|done|case|esac|function|in)\b"""
                )
                .findAll(source)
                .forEach { match ->
                    value.setSpan(
                        ForegroundColorSpan(
                            if (match.value.startsWith('#')) comment
                            else if (match.value.startsWith('"') || match.value.startsWith('\''))
                                tertiary
                            else primary
                        ),
                        match.range.first,
                        match.range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
        }
        cache.put(key, value)
        return value
    }
}
