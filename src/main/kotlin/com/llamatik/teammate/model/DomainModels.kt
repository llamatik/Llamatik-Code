package com.llamatik.teammate.model

// Already in ChatModels.kt: ChatMessage, ChatRole, AgentRole, ChatSession, EditProposal, ModelDescriptor
// New domain types:

enum class ModelTier { TIER_1, TIER_2, TIER_3 }

enum class ModelStatus {
    NOT_DOWNLOADED, DOWNLOADING, PAUSED, FAILED,
    DOWNLOADED, LOADING, LOADED, UNLOADED, CORRUPTED
}

enum class ModelSpecialization {
    CODING,
    GENERAL_REASONING,
    BALANCED,
}

enum class ModelCatalogStatus {
    RECOMMENDED,
    AVAILABLE,
    LEGACY,
    EXPERIMENTAL,
}

data class DownloadProgressState(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val status: ModelStatus,
)

data class HardwareCompatibility(
    val availableRamMb: Long,
    val freeDiskMb: Long,
    val cpuArch: String,
    val os: String,
    val isRamSufficient: Boolean,
    val isDiskSufficient: Boolean,
    val isArchCompatible: Boolean,
    val recommendation: String, // "recommended", "may_run_slowly", "insufficient_resources"
)

data class LoadedModelState(
    val modelId: String,
    val modelPath: String,
    val loadedAtMs: Long,
)

enum class AgentPhase { ANALYZE, PLAN, PROPOSE_CHANGES, APPLY_CHANGES, VERIFY }

data class AgentTaskState(
    val taskId: String,
    val prompt: String,
    val currentPhase: AgentPhase,
    val phaseHistory: List<AgentPhase>,
    val filesInvolved: List<String>,
    val proposedChanges: List<EditProposal>,
    val waitingForApproval: Boolean,
    val result: String?,
    val error: String?,
)

enum class PluginReadinessState { NO_MODEL, LOADING_MODEL, MODEL_LOADED, INDEXING, READY }

data class TrialState(
    val trialStartMs: Long,
    val daysRemaining: Int,
    val isExpired: Boolean,
    val isInWarningPeriod: Boolean, // within 5 days of expiry
    val warningDismissed: Boolean,
)
