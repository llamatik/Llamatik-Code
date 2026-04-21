package com.llamatik.teammate.model

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class AttachedImage(
    val displayName: String,
    val absolutePath: String,
    val bytes: ByteArray,
    val mimeType: String = "image/jpeg",
) {
    // BufferedImage for thumbnail rendering — decoded lazily, never serialised
    val thumbnail: BufferedImage? by lazy {
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachedImage) return false
        return absolutePath == other.absolutePath
    }

    override fun hashCode(): Int = absolutePath.hashCode()

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "webp", "gif")

        fun isImagePath(path: String): Boolean =
            path.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS

        fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
            "png"  -> "image/png"
            "gif"  -> "image/gif"
            "webp" -> "image/webp"
            "bmp"  -> "image/bmp"
            else   -> "image/jpeg"
        }

        /**
         * Normalise to JPEG bytes so the native MultimodalBridge always receives a
         * consistent format. Falls back to raw bytes if ImageIO cannot decode the input.
         */
        fun normalizeBytes(raw: ByteArray, mimeType: String): ByteArray {
            if (mimeType == "image/jpeg") return raw
            return runCatching {
                val img = ImageIO.read(ByteArrayInputStream(raw)) ?: return raw
                val out = ByteArrayOutputStream()
                ImageIO.write(img, "jpg", out)
                out.toByteArray()
            }.getOrDefault(raw)
        }
    }
}
