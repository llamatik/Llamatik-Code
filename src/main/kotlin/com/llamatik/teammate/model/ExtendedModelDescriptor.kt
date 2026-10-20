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
    /** Download URL for the companion mmproj GGUF required for vision/multimodal inference. Null for text-only models. */
    val mmprojDownloadUrl: String? = null,
    /** Absolute path to the companion mmproj GGUF file required for vision/multimodal inference. */
    val mmprojPath: String? = null,
    /**
     * Ordered list of shard URLs for split GGUF models (e.g. GLM-4.5-Air).
     * When non-empty, [downloadSourceUrl] is ignored and shards are downloaded
     * sequentially; the resulting files are left as individual shards — llama.cpp
     * accepts the first shard path and auto-discovers the rest.
     * When empty, [downloadSourceUrl] is used as the single download URL.
     */
    val shardDownloadUrls: List<String> = emptyList(),
) {
    val isVlm: Boolean get() = mmprojPath != null
    val isMultiShard: Boolean get() = shardDownloadUrls.isNotEmpty()
}
