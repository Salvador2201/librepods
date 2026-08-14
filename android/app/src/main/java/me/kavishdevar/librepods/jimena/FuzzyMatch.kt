package me.kavishdevar.librepods.jimena

/** Minimal difflib-style similarity ratio (0..1), no external dependency. */
fun similarityRatio(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val distance = levenshtein(a, b)
    val maxLen = maxOf(a.length, b.length)
    return 1.0 - (distance.toDouble() / maxLen)
}

private fun levenshtein(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost
            )
        }
    }
    return dp[a.length][b.length]
}

/** True if any word in [text] fuzzy-matches [target] above [threshold]. */
fun containsFuzzyWord(text: String, target: String, threshold: Double = 0.75): Boolean {
    return text.lowercase().split(Regex("\\s+")).any { word ->
        similarityRatio(word, target) >= threshold
    }
}
