package com.dd3ok.whoamai.adapter.out.persistence

import com.dd3ok.whoamai.application.port.out.ResumePersistencePort
import com.dd3ok.whoamai.domain.Resume
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ResumePersistenceAdapter(
    private val vectorDBPort: VectorDBPort,
    private val objectMapper: ObjectMapper
) : ResumePersistencePort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun index(resume: Resume): Int {
        logger.info("Translating Domain 'Resume' object to Infrastructure 'ResumeChunk' DTOs.")
        val chunks = generateResumeChunks(resume)

        if (chunks.isEmpty()) {
            logger.warn("No resume chunks were generated. Indexing skipped.")
            return 0
        }

        return vectorDBPort.indexResume(chunks)
    }

    override suspend fun findContentById(id: String): String? {
        return vectorDBPort.findChunkById(id)
    }

    override suspend fun searchSimilarSections(query: String, topK: Int): List<String> {
        return vectorDBPort.searchSimilarResumeSections(query, topK)
    }

    /**
     * 여러 private 메소드를 호출하여 청크 생성 과정을 오케스트레이션합니다.
     */
    private fun generateResumeChunks(resume: Resume): List<ResumeChunk> {
        return try {
            val chunks = mutableListOf<ResumeChunk>()

            chunks.add(createSummaryChunk(resume))
            chunks.add(createSkillsChunk(resume))
            chunks.add(createTotalExperienceChunk(resume))
            createEducationChunk(resume)?.let { chunks.add(it) }
            createCertificatesChunk(resume)?.let { chunks.add(it) }
            createHobbiesChunk(resume)?.let { chunks.add(it) }
            createInterestsChunk(resume)?.let { chunks.add(it) }
            createMbtiChunk(resume)?.let { chunks.add(it) }
            chunks.addAll(createExperienceChunks(resume))
            chunks.addAll(createProjectChunks(resume))

            logger.info("Translated domain object into ${chunks.size} infrastructure DTOs.")
            chunks
        } catch (e: Exception) {
            logger.error("Error during chunk generation: ${e.message}", e)
            emptyList()
        }
    }

    // --- Private Helper Methods for Chunk Generation ---

    private fun createSummaryChunk(resume: Resume): ResumeChunk {
        val summaryContent = "저는 ${resume.name}입니다. ${resume.summary}"
        return ResumeChunk(
            id = "summary",
            type = "summary",
            content = summaryContent,
            source = objectMapper.convertValue(
                mapOf(
                    "name" to resume.name,
                    "summary" to resume.summary,
                    "blog" to resume.blog
                )
            )
        )
    }

    private fun createSkillsChunk(resume: Resume): ResumeChunk {
        val content = "보유하고 있는 주요 기술은 ${resume.skills.joinToString(", ")} 등 입니다."
        return ResumeChunk(
            id = "skills",
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
                } + ". 이 정보를 바탕으로 총 경력을 계산해서 알려주세요."
        return ResumeChunk(
            id = "experience_total_summary",
            type = "summary",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.experiences))
        )
    }

    private fun createEducationChunk(resume: Resume): ResumeChunk? {
        if (resume.education.isEmpty()) return null
        val content = "학력 정보는 다음과 같습니다.\n" + resume.education.joinToString("\n") { edu ->
            "${edu.school}에서 ${edu.major}을 전공했으며(${edu.period.start} ~ ${edu.period.end}), ${edu.degree} 학위를 받았습니다."
        }
        return ResumeChunk(
            id = "education",
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
            id = "certificates",
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
            id = "hobbies",
            type = "hobby",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.hobbies))
        )
    }

    private fun createInterestsChunk(resume: Resume): ResumeChunk? {
        if (resume.interests.isEmpty()) return null
        val content = "최근 주요 관심사는 ${resume.interests.joinToString(", ")} 등 입니다."
        return ResumeChunk(
            id = "interests",
            type = "interest",
            content = content,
            source = objectMapper.convertValue(mapOf("items" to resume.interests))
        )
    }

    private fun createMbtiChunk(resume: Resume): ResumeChunk? {
        if (resume.mbti.isBlank()) return null
        return ResumeChunk(
            id = "mbti",
            type = "personality",
            content = "저의 MBTI는 ${resume.mbti}입니다.",
            source = objectMapper.convertValue(mapOf("mbti" to resume.mbti))
        )
    }

    private fun createExperienceChunks(resume: Resume): List<ResumeChunk> {
        return resume.experiences.map { exp ->
            val content = """
                ${exp.company}에서 근무한 경력 정보입니다.
                근무 기간은 ${exp.period.start}부터 ${exp.period.end}까지이며, ${exp.position}으로 근무했습니다.
            """.trimIndent()
            ResumeChunk(
                id = "experience_${exp.company.replace(" ", "_")}",
                type = "experience",
                content = content,
                company = exp.company,
                source = objectMapper.convertValue(exp)
            )
        }
    }

    private fun createProjectChunks(resume: Resume): List<ResumeChunk> {
        return resume.projects.map { proj ->
            val content = """
                프로젝트 '${proj.title}'에 대한 상세 정보입니다.
                - 소속: ${proj.company}
                - 기간: ${proj.period.start} ~ ${proj.period.end}
                - 설명: ${proj.description}
                - 주요 기술: ${proj.skills.joinToString(", ")}
            """.trimIndent()
            ResumeChunk(
                id = "project_${proj.title.replace(Regex("\\s+"), "_")}",
                type = "project",
                content = content,
                company = proj.company,
                skills = proj.skills,
                source = objectMapper.convertValue(proj)
            )
        }
    }
}