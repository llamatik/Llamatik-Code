package com.llamatik.teammate.model

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.settings.LlamatikAppSettings

@Service(Service.Level.APP)
class ModelCapabilityService {

    data class CapabilityHint(
        val level: Level,
        val message: String,
        val suggestion: String = "",
    ) {
        enum class Level { INFO, WARNING }
    }

    enum class TaskComplexity { SIMPLE, MODERATE, COMPLEX, VERY_COMPLEX }

    fun estimateComplexity(prompt: String, fileCount: Int = 0): TaskComplexity {
        val p = prompt.lowercase()
        val hasMultiFile = fileCount > 1 || p.contains("all files") || p.contains("entire project") ||
            p.contains("refactor") || p.contains("migrate")
        val hasArchitectural = p.contains("architecture") || p.contains("design pattern") ||
            p.contains("restructure") || p.contains("split") || p.contains("modularize")
        val hasDeepAnalysis = p.contains("analyze") || p.contains("audit") ||
            p.contains("review") || p.contains("plan")
        val wordCount = prompt.split(Regex("\\s+")).size

        return when {
            hasArchitectural || (hasMultiFile && hasDeepAnalysis) -> TaskComplexity.VERY_COMPLEX
            hasMultiFile || hasArchitectural                      -> TaskComplexity.COMPLEX
            hasDeepAnalysis || wordCount > 60                    -> TaskComplexity.MODERATE
            else                                                  -> TaskComplexity.SIMPLE
        }
    }

    fun getHintForTask(prompt: String, fileCount: Int = 0): CapabilityHint? {
        val settings = service<LlamatikAppSettings>().data()
        val modelId = settings.modelPath.substringAfterLast('/').lowercase()
            .ifBlank { settings.modelIdentifier.lowercase() }
        val contextSize = settings.contextSize
        val complexity = estimateComplexity(prompt, fileCount)

        // Context too small
        if (contextSize < 4096 && complexity >= TaskComplexity.MODERATE) {
            return CapabilityHint(
                level = CapabilityHint.Level.WARNING,
                message = "Context size ($contextSize tokens) may be too small for this task.",
                suggestion = "Increase Context Size to at least 8192 in Settings → Inference.",
            )
        }

        // Small model for complex task
        if (complexity >= TaskComplexity.VERY_COMPLEX && isSmallModel(modelId)) {
            return CapabilityHint(
                level = CapabilityHint.Level.WARNING,
                message = "This task is complex and may challenge smaller models.",
                suggestion = "Consider using Qwen3-14B or DeepSeek-R1-32B for better planning quality.",
            )
        }

        // Context likely truncated for large project analysis
        if (complexity == TaskComplexity.VERY_COMPLEX && contextSize < 16384) {
            return CapabilityHint(
                level = CapabilityHint.Level.WARNING,
                message = "Large analysis tasks work best with 16k+ context tokens.",
                suggestion = "Increase Context Size in Settings → Inference for deeper analysis.",
            )
        }

        return null
    }

    fun getHintAfterPoorResult(modelId: String): CapabilityHint? {
        return if (isSmallModel(modelId)) {
            CapabilityHint(
                level = CapabilityHint.Level.WARNING,
                message = "The result quality may be limited by the current model size.",
                suggestion = "Try a larger model (Qwen3-14B or DeepSeek-R1-32B) for better results.",
            )
        } else null
    }

    fun getHintForContextTruncation(): CapabilityHint = CapabilityHint(
        level = CapabilityHint.Level.WARNING,
        message = "The context was truncated — some files or history was dropped.",
        suggestion = "Increase Context Size in Settings → Inference, or break the task into smaller parts.",
    )

    private fun isSmallModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return id.contains("1b") || id.contains("2b") || id.contains("3b") ||
            id.contains("mini") || id.contains("tiny") || id.contains("small") ||
            id.contains("e4b") || id.contains("0.5b") || id.contains("1.5b")
    }
}
