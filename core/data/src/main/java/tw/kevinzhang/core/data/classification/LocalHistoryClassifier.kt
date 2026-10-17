package tw.kevinzhang.core.data.classification

enum class LocalDecisionSource { OWN_ACCOUNT, LEARNED, RULE, HISTORY, FALLBACK }
data class LocalCategoryChoice(val categoryId: String, val source: LocalDecisionSource, val reason: String)
data class LearningSample(val transferId: String, val categoryId: String, val features: LocalClassificationFeatures, val correctionSequence: Long) {
    override fun toString() = "LearningSample([REDACTED])"
}

private fun usable(sample: LearningSample) = sample.correctionSequence >= 0 && sample.categoryId != "transfer-account" && !sample.categoryId.startsWith("local-fallback-")
fun chooseLearned(features: LocalClassificationFeatures, examples: List<LearningSample>): LocalCategoryChoice? =
    examples.asSequence().filter { usable(it) && features.identity.isNotEmpty() && it.features == features && it.correctionSequence > 0 }
        .maxByOrNull { it.correctionSequence }?.let { LocalCategoryChoice(it.categoryId, LocalDecisionSource.LEARNED, "EXACT_MANUAL_PATTERN") }

fun chooseHistory(features: LocalClassificationFeatures, examples: List<LearningSample>): LocalCategoryChoice? {
    if (features.identity.isEmpty()) return null
    val tokens = bigrams(features.identity)
    val ranked = examples.distinctBy { it.transferId }.asSequence()
        .filter { usable(it) && it.features.direction == features.direction }
        .map { sample ->
            val other = bigrams(sample.features.identity)
            val score = if (sample.features.identity == features.identity) 1.0
                else (tokens intersect other).size.toDouble() / (tokens union other).size.coerceAtLeast(1)
            sample to score
        }.filter { it.second >= 0.6 }
        .sortedWith(compareByDescending<Pair<LearningSample, Double>> { it.second }
            .thenByDescending { it.first.correctionSequence }.thenBy { it.first.transferId })
        .take(50).toList()
    val best = ranked.groupBy { it.first.categoryId }.entries.sortedWith(
        compareByDescending<Map.Entry<String, List<Pair<LearningSample, Double>>>> { it.value.sumOf { pair -> pair.second } }
            .thenByDescending { it.value.count { pair -> pair.first.features.accountId == features.accountId } }
            .thenByDescending { it.value.maxOf { pair -> pair.first.correctionSequence } }.thenBy { it.key },
    ).firstOrNull() ?: return null
    return LocalCategoryChoice(best.key, LocalDecisionSource.HISTORY, "MANUAL_HISTORY_SIMILARITY")
}
