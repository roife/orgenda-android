package com.roifewu.orgenda.core

object TextMerge {
    data class Hunk(val start: Int, val end: Int, val lines: List<String>)
    private fun lines(text: String): List<String> = Regex("[^\n]*\n|[^\n]+$").findAll(text).map { it.value }.toList()
    private fun diff(old: List<String>, new: List<String>): List<Hunk> {
        // Bound memory for large files; a coarse hunk is conservative (conflict instead of lost text).
        if (old.size.toLong() * new.size > 1_000_000) {
            var start = 0; var oldEnd = old.size; var newEnd = new.size
            while (start < oldEnd && start < newEnd && old[start] == new[start]) start++
            while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) { oldEnd--; newEnd-- }
            return if (start == oldEnd && start == newEnd) emptyList() else listOf(Hunk(start, oldEnd, new.subList(start, newEnd)))
        }
        val dp = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) for (j in new.indices.reversed()) dp[i][j] = if (old[i] == new[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val result = mutableListOf<Hunk>(); var i = 0; var j = 0; var start = -1; var replacement = mutableListOf<String>()
        fun finish() { if (start >= 0) { result += Hunk(start, i, replacement); start = -1; replacement = mutableListOf() } }
        while (i < old.size || j < new.size) {
            if (i < old.size && j < new.size && old[i] == new[j]) { finish(); i++; j++ }
            else {
                if (start < 0) start = i
                if (j < new.size && (i == old.size || dp[i][j + 1] >= dp[i + 1][j])) replacement += new[j++] else i++
            }
        }
        finish(); return result
    }
    fun merge(base: String, local: String, remote: String): String? {
        if (local == remote || remote == base) return local
        if (local == base) return remote
        val original = lines(base); val a = diff(original, lines(local)); val b = diff(original, lines(remote))
        fun overlaps(x: Hunk, y: Hunk): Boolean = if (x.start == x.end || y.start == y.end) x.start <= y.end && y.start <= x.end else x.start < y.end && y.start < x.end
        if (a.any { x -> b.any { y -> x != y && overlaps(x, y) } }) return null
        val result = original.toMutableList()
        (a + b).distinct().sortedByDescending { it.start }.forEach { h -> result.subList(h.start, h.end).clear(); result.addAll(h.start, h.lines) }
        return result.joinToString("")
    }
}
