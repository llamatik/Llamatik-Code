package com.llamatik.teammate.model

data class ExtendedModelDescriptor(
    val id: String,
    val displayName: String,
    val quantization: String,
    val diskSizeGb: Double,
    val minRamGb: Double,
    val recommendedRamGb: Double,
    val tier: ModelTier,
    val useCases: List<String>,
    val tradeoffs: String,
    val isDefault: Boolean = false,
    val downloadSourceUrl: String = "", // placeholder
    var status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val localPath: String? = null,
)
