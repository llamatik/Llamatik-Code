package com.llamatik.teammate.models

import com.llamatik.teammate.model.ExtendedModelDescriptor
import com.llamatik.teammate.model.ModelCatalogStatus
import com.llamatik.teammate.model.ModelSpecialization
import com.llamatik.teammate.model.ModelTier

// Catalog strategy:
//   Tier 1 — Lightweight local assistants (≤8 GB RAM): fast, low-resource helpers
//   Tier 2 — Balanced local assistants (12–16 GB RAM): strong reasoning + coding for everyday use
//   Tier 3 — Claude Sonnet-like coding/reasoning models (24–64 GB RAM): agentic, repo-scale work
//
// TODO (Ultra / workstation tier): Kimi K2 — exceptional agentic coding quality but requires
//   80–140 GB RAM even in Q4; excluded from default catalog to avoid confusing normal users.

object BuiltInModelCatalog {
    val models: List<ExtendedModelDescriptor> = listOf(

        // ── Tier 1 — Lightweight ─────────────────────────────────────────────
        ExtendedModelDescriptor(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B it",
            quantization = "bf16",
            diskSizeGb = 8.8,
            minRamGb = 4.0,
            recommendedRamGb = 8.0,
            tier = ModelTier.TIER_1,
            useCases = listOf("Quick reasoning", "Code help", "Portable setups"),
            tradeoffs = "Very efficient generalist with coding ability, but not code-specialized.",
            repositoryUrl = "https://ai.google.dev/gemma/docs/core",
            modelCardUrl = "https://ai.google.dev/gemma/docs/core/model_card_4",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
            downloadSourceUrl = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-bf16.gguf?download=true",
            mmprojDownloadUrl = "https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/mmproj-gemma-4-E2B-it-bf16.gguf?download=true"
        ),
        ExtendedModelDescriptor(
            id = "gemma-4-e4b-q4",
            displayName = "Gemma 4 E4B it",
            quantization = "Q8_0",
            diskSizeGb = 8.1,
            minRamGb = 6.0,
            recommendedRamGb = 8.0,
            tier = ModelTier.TIER_1,
            useCases = listOf("Reasoning", "Coding help", "Agent-ready local assistant"),
            tradeoffs = "Strong small generalist for plugin workflows; broader than a coding-only model.",
            repositoryUrl = "https://ai.google.dev/gemma/docs/core",
            modelCardUrl = "https://ai.google.dev/gemma/docs/core/model_card_4",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
            downloadSourceUrl = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q8_0.gguf?download=true",
            mmprojDownloadUrl = "https://huggingface.co/ggml-org/gemma-4-E4B-it-GGUF/resolve/main/mmproj-gemma-4-E4B-it-Q8_0.gguf?download=true"
        ),

        // ── Tier 2 — Balanced ────────────────────────────────────────────────
        ExtendedModelDescriptor(
            id = "qwen3-14b-q4",
            displayName = "Qwen3-14B",
            quantization = "Q4_K_M",
            diskSizeGb = 9.0,
            minRamGb = 12.0,
            recommendedRamGb = 16.0,
            tier = ModelTier.TIER_2,
            useCases = listOf("Complex reasoning", "Refactoring", "Architecture"),
            tradeoffs = "Excellent reasoning and broad coding help, especially for complex tasks. Best 16 GB RAM option.",
            repositoryUrl = "https://huggingface.co/Qwen/Qwen3-14B",
            modelCardUrl = "https://huggingface.co/Qwen/Qwen3-14B-GGUF",
            downloadSourceUrl = "https://huggingface.co/ggml-org/Qwen3-14B-GGUF/resolve/main/Qwen3-14B-Q4_K_M.gguf?download=true",
            recommendedFileName = "qwen3-14b-q4_k_m.gguf",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
        ),
        ExtendedModelDescriptor(
            id = "gemma-4-26b-a4b-q4",
            displayName = "Gemma 4 26B A4B",
            quantization = "Q4_K_M",
            diskSizeGb = 16.8,
            minRamGb = 16.0,
            recommendedRamGb = 24.0,
            tier = ModelTier.TIER_2,
            useCases = listOf("Reasoning", "Coding", "Agentic workflows"),
            tradeoffs = "Powerful MoE option for advanced local assistance with better headroom than small Gemma variants.",
            repositoryUrl = "https://ai.google.dev/gemma/docs/core",
            modelCardUrl = "https://ai.google.dev/gemma/docs/core/model_card_4",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
            downloadSourceUrl = "https://huggingface.co/ggml-org/gemma-4-26B-A4B-it-GGUF/resolve/main/gemma-4-26B-A4B-it-Q4_K_M.gguf?download=true"
        ),

        // ── Tier 3 — Claude Sonnet-like coding / reasoning ───────────────────
        ExtendedModelDescriptor(
            // Best local balanced model; default for general use
            id = "qwen3.6-27b-q4",
            displayName = "Qwen3.6-27B",
            quantization = "Q4_K_M",
            diskSizeGb = 17.1,
            minRamGb = 20.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Complex reasoning", "Large refactors", "Agentic workflows"),
            tradeoffs = "Excellent balance of reasoning depth and coding quality for demanding local tasks.",
            repositoryUrl = "https://huggingface.co/unsloth/Qwen3.6-27B-MTP-GGUF",
            modelCardUrl = "https://huggingface.co/unsloth/Qwen3.6-27B-MTP-GGUF",
            downloadSourceUrl = "https://huggingface.co/unsloth/Qwen3.6-27B-MTP-GGUF/resolve/main/Qwen3.6-27B-Q4_K_M.gguf?download=true",
            recommendedFileName = "Qwen3.6-27B-Q4_K_M.gguf",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
            isDefault = true,
        ),
        ExtendedModelDescriptor(
            // Closest local coding-specialized model to a Claude Sonnet-like IDE experience
            id = "qwen3-coder-30b-a3b-q4",
            displayName = "Qwen3-Coder 30B A3B",
            quantization = "Q4_K_M",
            diskSizeGb = 18.6,
            minRamGb = 24.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Agentic coding", "Repository-scale refactors", "Tool use", "Architecture"),
            tradeoffs = "Closest local coding-specialized option to a Claude Sonnet-like IDE experience, but requires a high-end machine.",
            repositoryUrl = "https://huggingface.co/Qwen/Qwen3-Coder-30B-A3B-Instruct",
            modelCardUrl = "https://huggingface.co/unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF",
            downloadSourceUrl = "https://huggingface.co/unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF/resolve/main/Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf?download=true",
            recommendedFileName = "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf",
            specialization = ModelSpecialization.CODING,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
        ),
        ExtendedModelDescriptor(
            id = "devstral-small-1.1-q4",
            displayName = "Devstral Small 1.1",
            quantization = "Q4_K_M",
            diskSizeGb = 14.3,
            minRamGb = 16.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Agentic software engineering", "Multi-file edits", "Codebase exploration", "Bug fixing"),
            tradeoffs = "Excellent agentic coding model for tool-heavy IDE workflows; more practical than older Codestral for software-engineering agents.",
            repositoryUrl = "https://huggingface.co/mistralai/Devstral-Small-2507",
            modelCardUrl = "https://huggingface.co/unsloth/Devstral-Small-2507-GGUF",
            downloadSourceUrl = "https://huggingface.co/unsloth/Devstral-Small-2507-GGUF/resolve/main/Devstral-Small-2507-Q4_K_M.gguf?download=true",
            recommendedFileName = "Devstral-Small-2507-Q4_K_M.gguf",
            specialization = ModelSpecialization.CODING,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
        ),
        ExtendedModelDescriptor(
            // Fill-in-middle specialist; superseded for agentic use by Devstral
            id = "codestral-22b-q4",
            displayName = "Codestral 22B",
            quantization = "Q4_K_M",
            diskSizeGb = 13.3,
            minRamGb = 16.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Fill-in-middle", "Long context code"),
            tradeoffs = "Proven FIM specialist for long-context code tasks; superseded by Devstral for agentic software engineering.",
            repositoryUrl = "https://huggingface.co/mistralai/Codestral-22B-v0.1",
            modelCardUrl = "https://huggingface.co/QuantFactory/Codestral-22B-v0.1-GGUF",
            downloadSourceUrl = "https://huggingface.co/paulie6us/Codestral-22B-v0.1-Q4_K_M-GGUF/resolve/main/codestral-22b-v0.1-q4_k_m.gguf?download=true",
            recommendedFileName = "Codestral-22B-v0.1.Q4_K_M.gguf",
            specialization = ModelSpecialization.CODING,
            catalogStatus = ModelCatalogStatus.LEGACY,
        ),
        ExtendedModelDescriptor(
            id = "glm-4.5-air-q4",
            displayName = "GLM-4.5 Air",
            quantization = "Q4_K_M",
            diskSizeGb = 73.0,
            minRamGb = 32.0,
            recommendedRamGb = 64.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Hybrid reasoning", "Coding", "Tool use", "Agentic workflows"),
            tradeoffs = "Strong balanced reasoning/coding model with thinking and non-thinking modes; heavier and less coding-specialized than Qwen3-Coder.",
            repositoryUrl = "https://huggingface.co/zai-org/GLM-4.5-Air",
            modelCardUrl = "https://huggingface.co/unsloth/GLM-4.5-Air-GGUF",
            downloadSourceUrl = "https://huggingface.co/unsloth/GLM-4.5-Air-GGUF/resolve/main/Q4_K_M/GLM-4.5-Air-Q4_K_M-00001-of-00002.gguf?download=true",
            recommendedFileName = "GLM-4.5-Air-Q4_K_M-00001-of-00002.gguf",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
        ),
        ExtendedModelDescriptor(
            id = "deepseek-33b-distill-q4",
            displayName = "DeepSeek 33B Distill",
            quantization = "Q4_K_M",
            diskSizeGb = 19.9,
            minRamGb = 24.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Deep reasoning", "Complex architecture tasks"),
            tradeoffs = "Strong high-end reasoning model for difficult architecture and analysis tasks.",
            repositoryUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-32B",
            modelCardUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-32B",
            specialization = ModelSpecialization.GENERAL_REASONING,
            catalogStatus = ModelCatalogStatus.AVAILABLE,
            downloadSourceUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-32B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-32B-Q4_K_M.gguf?download=true"
        ),
        ExtendedModelDescriptor(
            id = "gemma-4-26b-a4b-q8",
            displayName = "Gemma 4 26B A4B it",
            quantization = "Q8_0",
            diskSizeGb = 26.9,
            minRamGb = 16.0,
            recommendedRamGb = 24.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Reasoning", "Coding", "Agentic workflows"),
            tradeoffs = "Powerful MoE option for advanced local assistance with better headroom than small Gemma variants.",
            repositoryUrl = "https://ai.google.dev/gemma/docs/core",
            modelCardUrl = "https://ai.google.dev/gemma/docs/core/model_card_4",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
            downloadSourceUrl = "https://huggingface.co/ggml-org/gemma-4-26B-A4B-it-GGUF/resolve/main/gemma-4-26B-A4B-it-Q8_0.gguf?download=true",
            mmprojDownloadUrl = "https://huggingface.co/ggml-org/gemma-4-26B-A4B-it-GGUF/resolve/main/mmproj-gemma-4-26B-A4B-it-Q8_0.gguf?download=true"
        ),
        ExtendedModelDescriptor(
            id = "gemma-4-31b-q8",
            displayName = "Gemma 4 31B it",
            quantization = "Q8_0",
            diskSizeGb = 32.7,
            minRamGb = 24.0,
            recommendedRamGb = 32.0,
            tier = ModelTier.TIER_3,
            useCases = listOf("Best Gemma reasoning", "Coding", "Advanced agent workflows"),
            tradeoffs = "Premium Gemma option with strong reasoning and coding, but requires a high-end machine.",
            repositoryUrl = "https://ai.google.dev/gemma/docs/core",
            modelCardUrl = "https://ai.google.dev/gemma/docs/core/model_card_4",
            downloadSourceUrl = "https://huggingface.co/ggml-org/gemma-4-31B-it-GGUF/resolve/main/gemma-4-31B-it-Q8_0.gguf?download=true",
            mmprojDownloadUrl = "https://huggingface.co/ggml-org/gemma-4-31B-it-GGUF/resolve/main/mmproj-gemma-4-31B-it-Q8_0.gguf?download=true",
            specialization = ModelSpecialization.BALANCED,
            catalogStatus = ModelCatalogStatus.RECOMMENDED,
        ),
    )

    fun byTier(tier: ModelTier) = models.filter { it.tier == tier }
    fun findById(id: String) = models.firstOrNull { it.id == id }
    fun recommended() = models.first { it.isDefault }
}
