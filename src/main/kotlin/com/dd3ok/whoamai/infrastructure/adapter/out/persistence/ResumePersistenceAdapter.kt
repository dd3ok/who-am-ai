package com.dd3ok.whoamai.infrastructure.adapter.out.persistence

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

        // 중간 DTO를 다음 어댑터로 전달
        return vectorDBPort.indexResume(chunks)
    }

    override suspend fun findContentById(id: String): String? {
        return vectorDBPort.findChunkById(id)
    }

    override suspend fun searchSimilarSections(query: String, topK: Int): List<String> {
        return vectorDBPort.searchSimilarResumeSections(query, topK)
    }

    private fun generateResumeChunks(resume: Resume): List<ResumeChunk> {
        val chunks = mutableListOf<ResumeChunk>()
        try {
            val summaryContent = "저는 ${resume.name}입니다. ${resume.summary}"
            chunks.add(ResumeChunk(
                id = "summary",
                type = "summary",
                content = summaryContent,
                source = objectMapper.convertValue(mapOf("name" to resume.name, "summary" to resume.summary, "blog" to resume.blog))
            ))

            chunks.add(ResumeChunk(id = "skills", type = "skills", content = "보유하고 있는 주요 기술은 ${resume.skills.joinToString(", ")} 등 입니다.", skills = resume.skills, source = objectMapper.convertValue(mapOf("skills" to resume.skills))))

            val experienceSummaryForAI = "전체 경력 기간 정보는 다음과 같습니다: " +
                    resume.experiences.joinToString("; ") { exp ->
                        "${exp.company}에서 ${exp.period.start}부터 ${exp.period.end}까지 근무"
                    } + ". 이 정보를 바탕으로 총 경력을 계산해서 알려주세요."
            chunks.add(ResumeChunk(
                id = "experience_total_summary",
                type = "summary",
                content = experienceSummaryForAI,
                source = objectMapper.convertValue(mapOf("items" to resume.experiences))
            ))

            val educationContent = resume.education.joinToString("\n") { edu -> "${edu.school}에서 ${edu.major}을 전공했으며(${edu.period.start} ~ ${edu.period.end}), ${edu.degree} 학위를 받았습니다." }
            if (educationContent.isNotBlank()) { chunks.add(ResumeChunk(id = "education", type = "education", content = "학력 정보는 다음과 같습니다.\n$educationContent", source = objectMapper.convertValue(mapOf("items" to resume.education)))) }

            val certificateContent = resume.certificates.joinToString("\n") { cert -> "${cert.issuedAt}에 ${cert.issuer}에서 발급한 ${cert.title} 자격증을 보유하고 있습니다." }
            if (certificateContent.isNotBlank()) {
                chunks.add(ResumeChunk(
                    id = "certificates",
                    type = "certificate",
                    content = "보유 자격증은 다음과 같습니다.\n$certificateContent",
                    source = objectMapper.convertValue(mapOf("items" to resume.certificates))
                ))
            }

            val hobbyContent = resume.hobbies.joinToString("\n") { hobby -> "${hobby.category}으로는 ${hobby.items.joinToString(", ")} 등을 즐깁니다." }
            if (hobbyContent.isNotBlank()) { chunks.add(ResumeChunk(id = "hobbies", type = "hobby", content = "주요 취미는 다음과 같습니다.\n$hobbyContent", source = objectMapper.convertValue(mapOf("items" to resume.hobbies)))) }

            val interestContent = resume.interests.joinToString(", ")
            if (interestContent.isNotBlank()) {
                chunks.add(ResumeChunk(
                    id = "interests",
                    type = "interest",
                    content = "최근 주요 관심사는 ${interestContent} 등 입니다.",
                    source = objectMapper.convertValue(mapOf("items" to resume.interests))
                ))
            }

            if (resume.mbti.isNotBlank()) { chunks.add(ResumeChunk(id = "mbti", type = "personality", content = "저의 MBTI는 ${resume.mbti}입니다.", source = objectMapper.convertValue(mapOf("mbti" to resume.mbti)))) }

            resume.experiences.forEach { exp ->
                val contentBuilder = StringBuilder()
                contentBuilder.appendLine("${exp.company}에서 근무한 경력 정보입니다.")
                contentBuilder.appendLine("근무 기간은 ${exp.period.start}부터 ${exp.period.end}까지이며, ${exp.position}으로 근무했습니다.")
                chunks.add(ResumeChunk(
                    id = "experience_${exp.company.replace(" ", "_")}",
                    type = "experience",
                    content = contentBuilder.toString(),
                    company = exp.company, // company 필드 추가
                    source = objectMapper.convertValue(exp)
                ))
            }

            resume.projects.forEach { proj ->
                val projectId = "project_${proj.title.replace(Regex("\\s+"), "_")}"
                val projectContent = """
                프로젝트 '${proj.title}'에 대한 상세 정보입니다.
                - 소속: ${proj.company}
                - 기간: ${proj.period.start} ~ ${proj.period.end}
                - 설명: ${proj.description}
                - 주요 기술: ${proj.skills.joinToString(", ")}
            """.trimIndent()
                chunks.add(ResumeChunk(
                    id = projectId,
                    type = "project",
                    content = projectContent,
                    company = proj.company, // company 필드 추가
                    skills = proj.skills,   // skills 필드 추가
                    source = objectMapper.convertValue(proj)
                ))
            }

            logger.info("Translated domain object into ${chunks.size} infrastructure DTOs.")
        } catch (e: Exception) {
            logger.error("Error during chunk generation: ${e.message}", e)
        }
        return chunks
    }
}