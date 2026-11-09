package com.mallstorm.qwencoder

/** Line-based diff (common prefix/suffix trimmed, LCS in the middle). */
data class DiffLine(val kind: Char, val text: String, val oldNo: Int, val newNo: Int)

class DiffResult(val lines: List<DiffLine>, val added: Int, val removed: Int)

object DiffUtil {
    private const val MAX_CELLS = 3_000_000

    private fun split(t: String): List<String> {
        if (t.isEmpty()) return emptyList()
        return t.removeSuffix("\n").split("\n")
    }

    fun diff(oldText: String, newText: String): DiffResult {
        val a = split(oldText)
        val b = split(newText)
        var s = 0
        while (s < a.size && s < b.size && a[s] == b[s]) s++
        var ea = a.size
        var eb = b.size
        while (ea > s && eb > s && a[ea - 1] == b[eb - 1]) {
            ea--
            eb--
        }
        val am = a.subList(s, ea)
        val bm = b.subList(s, eb)
        val out = ArrayList<DiffLine>()
        var added = 0
        var removed = 0
        var on = 1
        var nn = 1
        fun ctx(t: String) { out.add(DiffLine(' ', t, on, nn)); on++; nn++ }
        fun del(t: String) { out.add(DiffLine('-', t, on, 0)); on++; removed++ }
        fun add(t: String) { out.add(DiffLine('+', t, 0, nn)); nn++; added++ }

        for (k in 0 until s) ctx(a[k])
        val n = am.size
        val m = bm.size
        if (n.toLong() * m.toLong() <= MAX_CELLS) {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    dp[i][j] = if (am[i] == bm[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                if (am[i] == bm[j]) {
                    ctx(am[i]); i++; j++
                } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                    del(am[i]); i++
                } else {
                    add(bm[j]); j++
                }
            }
            while (i < n) { del(am[i]); i++ }
            while (j < m) { add(bm[j]); j++ }
        } else {
            for (t in am) del(t)
            for (t in bm) add(t)
        }
        for (k in ea until a.size) ctx(a[k])
        return DiffResult(out, added, removed)
    }
}
