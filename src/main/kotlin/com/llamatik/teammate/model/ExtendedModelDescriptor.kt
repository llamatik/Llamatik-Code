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
    val downloadSourceUrl: String = "",
    val repositoryUrl: String = "",
    val modelCardUrl: String = "",
    val recommendedFileName: String? = null,
    val specialization: ModelSpecialization = ModelSpecialization.BALANCED,
    val catalogStatus: ModelCatalogStatus = ModelCatalogStatus.AVAILABLE,
    var status: ModelStatus = ModelStatus.NOT_DOWNLOADED,
    val localPath: String? = null,
    /** Absolute path to the companion mmproj GGUF file required for vision/multimodal inference. */
    val mmprojPath: String? = null,
) {
    val isVlm: Boolean get() = mmprojPath != null
}
