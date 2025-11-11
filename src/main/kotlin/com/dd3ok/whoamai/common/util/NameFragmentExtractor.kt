package com.dd3ok.whoamai.common.util

/**
 * 이름 문자열에서 일정 길이 이상의 부분 문자열을 추출하는 유틸리티.
 * 공백을 제거하고 소문자로 통일한 뒤 조각을 만들어 이름 변형 검색에 재사용한다.
 */
object NameFragmentExtractor {
    private val whitespaceRegex = Regex("\\s+")

    fun extract(source: String, minLength: Int = 2): List<String> {
        val normalized = source.lowercase().replace(whitespaceRegex, "")
        if (normalized.isBlank()) {
            return emptyList()
        }
        if (normalized.length <= minLength) {
            return listOf(normalized)
        }

        val fragments = mutableSetOf<String>()
        for (start in normalized.indices) {
            for (end in (start + minLength)..normalized.length) {
                fragments.add(normalized.substring(start, end))
            }
        }
        return fragments.toList()
    }
}
