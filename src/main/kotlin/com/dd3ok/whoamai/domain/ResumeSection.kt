package com.dd3ok.whoamai.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Resume(
    val name: String = "",
    val mbti: String = "",
    val summary: String = "",
    val blog: String = "",
    val skills: List<String> = emptyList(),
    val certificates: List<Certificate> = emptyList(),
    val education: List<Education> = emptyList(),
    val experiences: List<Experience> = emptyList(),
    val projects: List<Project> = emptyList(),
    val hobbies: List<Hobby> = emptyList(),
    val interests: List<String> = emptyList()
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Certificate(val title: String = "", val issuedAt: String = "", val issuer: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Education(val school: String = "", val major: String = "", val period: Period = Period(), val degree: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Experience(val company: String = "", val aliases: List<String> = emptyList(), val period: Period = Period(), val position: String = "", val tags: List<String> = emptyList())
@JsonIgnoreProperties(ignoreUnknown = true)
data class Project(val title: String = "", val company: String = "", val period: Period = Period(), val skills: List<String> = emptyList(), val tags: List<String> = emptyList(), val description: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Period(val start: String = "", val end: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class Hobby(val category: String = "", val items: List<String> = emptyList())
