package com.dd3ok.whoamai.common.util

/**
 * 이력서 데이터 조각(Chunk)의 ID 생성 규칙을 관리하는 유틸리티.
 */
object ChunkIdGenerator {
    fun forProject(title: String): String = "project_${title.replace(Regex("\\s+"), "_")}"
    fun forExperience(company: String): String = "experience_${company.replace(" ", "_")}"
    fun forSummary(): String = "summary"
    fun forTotalExperience(): String = "experience_total_summary"
    fun forSkills(): String = "skills"
    fun forEducation(): String = "education"
    fun forCertificates(): String = "certificates"
    fun forInterests(): String = "interests"
    fun forMbti(): String = "mbti"
    fun forHobbies(): String = "hobbies"
}