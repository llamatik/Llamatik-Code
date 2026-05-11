package com.llamatik.teammate.skills

enum class SkillStatus { NOT_DOWNLOADED, DOWNLOADED, ACTIVE }

data class SkillDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val category: String,
    val prompt: String,
    val version: String = "1.0",
    val author: String = "Llamatik",
    val tags: List<String> = emptyList(),
    val sourceUrl: String = "",
    val isCustom: Boolean = false,
    val filePath: String = "",
)
