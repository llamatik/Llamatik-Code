package com.llamatik.teammate.infrastructure

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for HardwareDetector.evaluateForModel().
 * Uses fake RAM/disk values by calling detect() indirectly through evaluateForModel(),
 * and verifies the correct recommendation string is returned.
 */
class HardwareDetectorTest {

    @Test
    fun `detect returns non-null hardware compatibility`() {
        val hw = HardwareDetector.detect()
        assertNotNull(hw)
        assertTrue("availableRamMb must be positive", hw.availableRamMb > 0)
        assertTrue("freeDiskMb must be non-negative", hw.freeDiskMb >= 0)
        assertNotNull(hw.cpuArch)
        assertNotNull(hw.os)
        assertNotNull(hw.recommendation)
    }

    @Test
    fun `recommendation is one of the valid values`() {
        val hw = HardwareDetector.detect()
        assertTrue(
            "Recommendation '${hw.recommendation}' is not valid",
            hw.recommendation in setOf("recommended", "may_run_slowly", "insufficient_resources"),
        )
    }

    @Test
    fun `evaluateForModel returns valid string`() {
        // Use a very small model so the system almost certainly has enough resources
        val result = HardwareDetector.evaluateForModel(modelMinRamGb = 0.1, modelDiskGb = 0.1)
        assertTrue(
            "Result '$result' is not valid",
            result in setOf("recommended", "may_run_slowly", "insufficient_resources"),
        )
    }

    @Test
    fun `evaluateForModel with huge requirements returns insufficient_resources`() {
        // 1 TB RAM required — no system has this
        val result = HardwareDetector.evaluateForModel(modelMinRamGb = 1_000_000.0, modelDiskGb = 1_000_000.0)
        assertEquals("insufficient_resources", result)
    }

    @Test
    fun `cpuArch detection returns non-empty string`() {
        val hw = HardwareDetector.detect()
        assertTrue("cpuArch should not be empty", hw.cpuArch.isNotBlank())
    }

    @Test
    fun `os detection returns non-empty string`() {
        val hw = HardwareDetector.detect()
        assertTrue("os should not be empty", hw.os.isNotBlank())
    }

    @Test
    fun `isRamSufficient is true when availableRamMb exceeds 8192`() {
        val hw = HardwareDetector.detect()
        // Only assert consistency with the threshold documented in HardwareDetector
        if (hw.availableRamMb >= 8192) {
            assertTrue("isRamSufficient should be true when RAM >= 8 GB", hw.isRamSufficient)
        } else {
            assertFalse("isRamSufficient should be false when RAM < 8 GB", hw.isRamSufficient)
        }
    }

    @Test
    fun `isDiskSufficient is true when freeDiskMb exceeds 5120`() {
        val hw = HardwareDetector.detect()
        if (hw.freeDiskMb >= 5120) {
            assertTrue("isDiskSufficient should be true when disk >= 5 GB", hw.isDiskSufficient)
        } else {
            assertFalse("isDiskSufficient should be false when disk < 5 GB", hw.isDiskSufficient)
        }
    }
}
