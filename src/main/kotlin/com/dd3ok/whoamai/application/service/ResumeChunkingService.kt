package com.dd3ok.whoamai.application.service

import com.dd3ok.whoamai.adapter.out.persistence.ResumeChunk
import com.dd3ok.whoamai.common.util.ChunkIdGenerator
import com.dd3ok.whoamai.domain.*
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.convertValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.YearMonth

/**
 * Domain 객체(Resume)를 Persistence Layer에서 사용할 DTO(ResumeChunk)로
 * 변환(Chunking)하는 비즈니스 로직을 담당합니다.
 */
@Service
class ResumeChunkingService(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun generateChunks(resume: Resume): List<ResumeChunk> {
        val chunks = mutableListOf<ResumeChunk>()

        chunks.add(createSummaryChunk(resume))
        chunks.add(createSkillsChunk(resume))
        createRecentActivitiesChunk(resume)?.let { chunks.add(it) }
        chunks.add(createTotalExperienceChunk(resume))
        createEducationChunk(resume)?.let { chunks.add(it) }
        createCertificatesChunk(resume)?.let { chunks.add(it) }
        createHobbiesChunk(resume)?.let { chunks.add(it) }
        createInterestsChunk(resume)?.let { chunks.add(it) }
        createMbtiChunk(resume)?.let { chunks.add(it) }
        chunks.addAll(createExperienceChunks(resume))
        chunks.addAll(createProjectChunks(resume))

        logger.info("Translated domain object into ${chunks.size} infrastructure DTOs.")
        return chunks
    }

    private fun createSummaryChunk(resume: Resume): ResumeChunk {
        val summaryParts = mutableListOf("저는 ${resume.summary}".trim())
        if (resume.interests.isNotEmpty()) {
            summaryParts += "최근 관심사는 ${resume.interests.joinToString(", ")} 등입니다."
        }
        val summaryContent = summaryParts.joinToString(" ")
        return ResumeChunk(
            id = ChunkIdGenerator.forSummary(),
            type = "summary",
            content = summaryContent,
            source = objectMapper.convertValue(
                mapOf(
                    "name" to resume.name,
                    "summary" to resume.summary,
                    "recentActivities" to resume.recentActivities,
                    "blog" to resume.blog
                )
            )
        )
    }

    private fun createRecentActivitiesChunk(resume: Resume): ResumeChunk? {
        if (resume.recentActivities.isBlank()) return null
        return ResumeChunk(
            id = ChunkIdGenerator.forRecentActivities(),
            type = "recent_activity",
            content = "퇴사 이후 최근 활동은 다음과 같습니다. ${resume.recentActivities}",
            source = objectMapper.convertValue(mapOf("recentActivities" to resume.recentActivities))
        )
    }

    private fun createSkillsChunk(resume: Resume): ResumeChunk {
        val content = "보유하고 있는 주요 기술은 ${resume.skills.joinToString(", ")} 등 입니다."
        return ResumeChunk(
            id = ChunkIdGenerator.forSkills(),
            type = "skills",
            content = content,
            skills = resume.skills,
            source = objectMapper.convertValue(mapOf("skills" to resume.skills))
        )
    }

    private fun createTotalExperienceChunk(resume: Resume): ResumeChunk {
        val content = "전체 경력 기간 정보는 다음과 같습니다: " +
                resume.experiences.joinToString("; ") { exp ->
                    "${exp.company}에서 ${exp.period.start}부터 ${exp.period.end}까지 근무"
                } + ". " + totalExperience(resume.experiences)
        return ResumeChunk(
            id = ChunkIdGenerator.forTotalExperience(),
            type = "summary",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.experiences))
        )
    }

    private fun totalExperience(experiences: List<Experience>): String {
        val ranges = experiences.map { experience ->
            runCatching {
                val start = YearMonth.parse(experience.period.start)
                val end = YearMonth.parse(experience.period.end)
                require(!end.isBefore(start))
                (start.year * 12L + start.monthValue)..(end.year * 12L + end.monthValue)
            }.getOrNull()
        }
        if (ranges.isEmpty() || ranges.any { it == null }) return "정확한 총 경력을 계산할 날짜 정보가 부족합니다."
        var coveredUntil = Long.MIN_VALUE
        var months = 0L
        for (range in ranges.filterNotNull().sortedBy { it.first }) {
            val start = maxOf(range.first, coveredUntil + 1)
            if (start <= range.last) months += range.last - start + 1
            coveredUntil = maxOf(coveredUntil, range.last)
        }
        return "총 경력은 ${months / 12}년 ${months % 12}개월입니다. " +
            "월 단위로 입사월과 퇴사월을 포함하고, 겹치는 근무 월은 한 번만 계산한 값입니다."
    }

    private fun createEducationChunk(resume: Resume): ResumeChunk? {
        if (resume.education.isEmpty()) return null
        val content = "학력 정보는 다음과 같습니다.\n" + resume.education.joinToString("\n") { edu ->
            "${edu.school}에서 ${edu.major}을 전공했으며(${edu.period.start} ~ ${edu.period.end}), ${edu.degree} 학위를 받았습니다."
        }
        return ResumeChunk(
            id = ChunkIdGenerator.forEducation(),
            type = "education",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.education))
        )
    }

    private fun createCertificatesChunk(resume: Resume): ResumeChunk? {
        if (resume.certificates.isEmpty()) return null
        val content = "보유 자격증은 다음과 같습니다.\n" + resume.certificates.joinToString("\n") { cert ->
            "${cert.issuedAt}에 ${cert.issuer}에서 발급한 ${cert.title} 자격증을 보유하고 있습니다."
        }
        return ResumeChunk(
            id = ChunkIdGenerator.forCertificates(),
            type = "certificate",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.certificates))
        )
    }

    private fun createHobbiesChunk(resume: Resume): ResumeChunk? {
        if (resume.hobbies.isEmpty()) return null
        val content = "주요 취미는 다음과 같습니다.\n" + resume.hobbies.joinToString("\n") { hobby ->
            "${hobby.category}으로는 ${hobby.items.joinToString(", ")} 등을 즐깁니다."
        }
        return ResumeChunk(
            id = ChunkIdGenerator.forHobbies(),
            type = "hobby",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.hobbies))
        )
    }

    private fun createInterestsChunk(resume: Resume): ResumeChunk? {
        if (resume.interests.isEmpty()) return null
        val content = "최근 주요 관심사는 ${resume.interests.joinToString(", ")} 등 입니다."
        return ResumeChunk(
            id = ChunkIdGenerator.forInterests(),
            type = "interest",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.interests))
        )
    }

    private fun createMbtiChunk(resume: Resume): ResumeChunk? {
        if (resume.mbti.isBlank()) return null
        return ResumeChunk(
            id = ChunkIdGenerator.forMbti(),
            type = "personality",
            content = "저의 MBTI는 ${resume.mbti}입니다.",
            source = objectMapper.convertValue(mapOf("mbti" to resume.mbti))
        )
    }

    private fun createExperienceChunks(resume: Resume): List<ResumeChunk> {
        return resume.experiences.map { exp ->
            val contentParts = mutableListOf(
                "${exp.company}에서 근무한 경력 정보입니다.",
                "근무 기간은 ${exp.period.start}부터 ${exp.period.end}까지이며, ${exp.position}으로 근무했습니다."
            )
            if (exp.tags.isNotEmpty()) {
                contentParts += "주요 업무/관심 영역은 ${exp.tags.joinToString(", ")} 입니다."
            }
            contentParts += "해당 경력과 연결된 역할, 책임, 협업 방식, 문제 해결 경험에 대한 질문에는 이 정보를 우선 참고해주세요."
            val content = contentParts.joinToString(" ")
            ResumeChunk(
                id = ChunkIdGenerator.forExperience(exp.company),
                type = "experience",
                content = content,
                company = exp.company,
                source = objectMapper.convertValue(exp)
            )
        }
    }

    private fun createProjectChunks(resume: Resume): List<ResumeChunk> {
        return resume.projects.map { proj ->
            val contentParts = mutableListOf(
                "프로젝트 '${proj.title}'에 대한 상세 정보입니다.",
                "소속은 ${proj.company}입니다.",
                if (proj.period.start.isNotBlank() && proj.period.end.isNotBlank())
                    "기간은 ${proj.period.start}부터 ${proj.period.end}까지입니다." else "프로젝트 기간은 기록되어 있지 않습니다."
            )
            proj.description.takeIf { it.isNotBlank() }?.let { description ->
                contentParts += "프로젝트 설명은 다음과 같습니다: $description"
            }
            proj.problem.takeIf { it.isNotBlank() }?.let { contentParts += "해결할 문제: $it" }
            proj.contribution.takeIf { it.isNotBlank() }?.let { contentParts += "본인 기여: $it" }
            proj.outcome.takeIf { it.isNotBlank() }?.let { contentParts += "확인된 결과: $it" }
            if (proj.skills.isNotEmpty()) {
                contentParts += "주요 기술은 ${proj.skills.joinToString(", ")} 입니다."
            }
            if (proj.tags.isNotEmpty()) {
                contentParts += "핵심 주제와 업무 맥락은 ${proj.tags.joinToString(", ")} 입니다."
            }
            contentParts += "이 프로젝트와 관련된 역할, 성과, 트러블슈팅, 품질, 보안, 아키텍처 질문에는 이 정보를 우선 참고해주세요."
            val content = contentParts.joinToString(" ")
            ResumeChunk(
                id = ChunkIdGenerator.forProject(proj.title),
                type = "project",
                content = content,
                company = proj.company,
                skills = proj.skills,
                source = objectMapper.convertValue(proj)
            )
        }
    }
}
