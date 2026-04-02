package com.llamatik.teammate.infrastructure

import com.llamatik.teammate.model.HardwareCompatibility
import java.io.File

object HardwareDetector {
    fun detect(): HardwareCompatibility {
        val runtime = Runtime.getRuntime()
        val totalRamMb = runtime.maxMemory() / (1024 * 1024)
        // Use system total RAM from management bean if possible
        val systemRamMb = runCatching {
            val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            if (bean is com.sun.management.OperatingSystemMXBean) {
                bean.totalMemorySize / (1024 * 1024)
            } else totalRamMb
        }.getOrDefault(totalRamMb)

        val freeDiskMb = File(System.getProperty("user.home")).freeSpace / (1024 * 1024)
        val cpuArch = System.getProperty("os.arch") ?: "unknown"
        val os = System.getProperty("os.name") ?: "unknown"

        return HardwareCompatibility(
            availableRamMb = systemRamMb,
            freeDiskMb = freeDiskMb,
            cpuArch = cpuArch,
            os = os,
            isRamSufficient = systemRamMb >= 8192, // 8 GB minimum
            isDiskSufficient = freeDiskMb >= 5120, // 5 GB minimum
            isArchCompatible = cpuArch.contains("aarch64", ignoreCase = true) ||
                cpuArch.contains("x86_64", ignoreCase = true) ||
                cpuArch.contains("amd64", ignoreCase = true),
            recommendation = when {
                systemRamMb >= 16384 && freeDiskMb >= 10240 -> "recommended"
                systemRamMb >= 8192 && freeDiskMb >= 5120 -> "may_run_slowly"
                else -> "insufficient_resources"
            },
        )
    }

    fun evaluateForModel(modelMinRamGb: Double, modelDiskGb: Double): String {
        val hw = detect()
        val minRamMb = (modelMinRamGb * 1024).toLong()
        val diskMb = (modelDiskGb * 1024).toLong()
        return when {
            hw.availableRamMb < minRamMb || hw.freeDiskMb < diskMb -> "insufficient_resources"
            hw.availableRamMb < (modelMinRamGb * 1024 * 1.5).toLong() -> "may_run_slowly"
            else -> "recommended"
        }
    }
}
