package com.anezium.r08accessbridge

import java.text.Normalizer
import java.util.Locale

internal object SelfArmSettingsTextMatcher {
    private val combiningMarks = Regex("\\p{Mn}+")
    private val whitespace = Regex("\\s+")
    private val hyphenLike = Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]")

    fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val punctuationNormalized = value
            .replace('\u00a0', ' ')
            .replace(hyphenLike, "-")
        return Normalizer.normalize(punctuationNormalized, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()
    }

    fun containsAny(value: String, vararg needles: String): Boolean {
        val normalizedValue = normalize(value)
        if (normalizedValue.isBlank()) return false
        return needles.any { needle ->
            val normalizedNeedle = normalize(needle)
            normalizedNeedle.isNotBlank() && normalizedValue.contains(normalizedNeedle)
        }
    }

    fun containsBuildIdentifier(
        value: String,
        buildDisplay: String,
        buildId: String,
    ): Boolean {
        val normalizedValue = normalize(value)
        if (normalizedValue.isBlank()) return false
        return listOf(buildDisplay, buildId)
            .map(::normalize)
            .filter { it.length >= 4 }
            .any { normalizedValue.contains(it) }
    }
}
