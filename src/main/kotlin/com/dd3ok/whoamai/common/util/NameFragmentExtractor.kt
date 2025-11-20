package com.dd3ok.whoamai.common.util

/**
 * 이름 문자열을 다양한 파편으로 분리해 일치 가능성을 높여 주는 유틸.
 * (예: "유인재" -> ["유인재", "인재", "유"])
 */
object NameFragmentExtractor {
    private val HONORIFICS = listOf("님", "씨", "님들", "선생님")

    fun extract(fullName: String): Set<String> {
        val normalized = normalize(fullName)
        if (normalized.isBlank()) return emptySet()

        val fragments = mutableSetOf(normalized)

        if (normalized.length >= 2) {
            fragments.add(normalized.substring(1))
            fragments.add(normalized.takeLast(2))
        }

        return fragments.filter { it.length >= 2 }.toSet()
    }

    fun matches(query: String, fragments: Set<String>): Boolean {
        if (fragments.isEmpty()) return false
        return fragments.any { fragment ->
            query.contains(fragment) || HONORIFICS.any { suffix -> query.contains(fragment + suffix) }
        }
    }

    fun normalize(input: String): String = input
        .lowercase()
        .replace(Regex("\\s+"), "")
        .trim()
}
