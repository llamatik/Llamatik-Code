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
    /** 1-based index of the shard currently downloading. 1 for single-file downloads. */
    val currentShard: Int = 1,
    /** Total number of shards. 1 for single-file downloads. */
    val totalShards: Int = 1,
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

enum class LicenseStatus {
    LICENSED,  // valid JetBrains Marketplace licence
    TRIAL,     // licence active, IDE-managed trial in progress
    FREE,      // no licence — free tier, all free features available
    UNKNOWN,   // LicensingFacade not yet initialised
}

data class TrialState(
    val daysRemaining: Int,
    val isInWarningPeriod: Boolean, // within 5 days of Pro trial expiry
    val warningDismissed: Boolean,
)
