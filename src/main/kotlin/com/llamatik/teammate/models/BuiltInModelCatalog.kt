package com.llamatik.teammate.models

import com.llamatik.teammate.model.ExtendedModelDescriptor
import com.llamatik.teammate.model.ModelTier

object BuiltInModelCatalog {
    val models: List<ExtendedModelDescriptor> = listOf(
        // Tier 1
        ExtendedModelDescriptor(
            id = "phi-3-mini-q4",
            displayName = "Phi-3 Mini",
            quantization = "Q4_K_M",
            diskSizeGb = 2.2,
            minRamGb = 4.0,
            recommendedRamGb = 8.0,
            tier = ModelTier.TIER_1,
            useCases = listOf("Quick queries", "Low-resource machines"),
            tradeoffs = "Fast, low RAM, but limited reasoning depth",
        ),
        ExtendedModelDescriptor(
            id = "qwen2.5-coder-7b-q4",
            displayName = "Qwen2.5-Coder 7B",
            quantization = "Q4_K_M",
            diskSizeGb = 4.4,
            minRamGb = 6.0,
            recommendedRamGb = 8.0,
            tier = ModelTier.TIER_1,
            useCases = listOf("Code completion", "Explanation", "Bug fixing"),
            tradeoffs = "Good balance of speed and code quality",
        ),
        // Tier 2
        ExtendedModelDescriptor(
            id = "qwen3-14b-q4",
            displayName = "Qwen3-14B",
            quantization = "Q4_K_M",
            diskSizeGb = 9.0,
            minRamGb = 12.0,
            recommendedRamGb = 16.0,
            tier = ModelTier.TIER_2,
            useCases = listOf("Complex reasoning", "Refactoring", "Architecture"),
            tradeoffs = "Excellent reasoning, requires 16 GB RAM",
            isDefault = true,
        ),
        ExtendedModelDescriptor(
            id = "qwen2.5-coder-7b-full",
            displayName = "Qwen2.5-Coder 7B (full precision)",
            quantization = "F16",
            diskSizeGb = 14.0,
            minRamGb = 16.0,
            recommendedRamGb = 24.0,
            tier = ModelTier.TIER_2,
            useCases = listOf("High-quality code generation"),
            tradeoffs = "Higher quality than Q4 variant, needs more RAM",
        ),
        ExtendedModelDescriptor(
            id = "deepseek-coder-6.7b-q4",
            displayName = "DeepSeek-Coder 6.7B",
            quantization = "Q4_K_M",
            diskSizeGb = 3.8,
            minRamGb = 6.0,
            recommendedRamGb = 8.0,
            tier = ModelTier.TIER_2,
            useCases = listOf("Code generation", "Completion"),
            tradeoffs = "Strong coder, reasonable resource use",
        ),
        // Tier 3
        ExtendedModelDescriptor(
            id = "qwen2.5-coder-14b-q4",
            displayName = "Qwen2.5-Coder 14B",
            quantization = "Q4_K_M",
            diskSizeGb = 8.7,
            minRamGb = 12.0,
            recommendedRamGb = 16.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Advanced code generation", "Large refactors"),
            tradeoffs = "Top code quality, requires substantial RAM",
        ),
        ExtendedModelDescriptor(
            id = "codestral-22b-q4",
            displayName = "Codestral 22B",
            quantization = "Q4_K_M",
            diskSizeGb = 13.5,
            minRamGb = 16.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Fill-in-middle", "Long context code"),
            tradeoffs = "Exceptional code but needs 32 GB for smooth use",
        ),
        ExtendedModelDescriptor(
            id = "deepseek-33b-distill-q4",
            displayName = "DeepSeek 33B Distill",
            quantization = "Q4_K_M",
            diskSizeGb = 19.0,
            minRamGb = 24.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Deep reasoning", "Complex architecture tasks"),
            tradeoffs = "Best reasoning quality, demands high-end hardware",
        ),
    )

    fun byTier(tier: ModelTier) = models.filter { it.tier == tier }
    fun findById(id: String) = models.firstOrNull { it.id == id }
    fun recommended() = models.first { it.isDefault }
}
