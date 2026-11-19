package com.llamatik.agent.backends.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuSupportTest {
    private val sandyBridge = "processor : 0\nflags\t\t: fpu sse4_2 avx aes xsave\n"
    private val haswell = "processor : 0\nflags\t\t: fpu sse4_2 avx avx2 fma f16c bmi2\n"
    private val none = emptyList<String>()

    @Test
    fun sandyBridgeLacksAvx2FmaAndF16c() {
        assertEquals(listOf("avx2", "fma", "f16c"), CpuSupport.missingFeatures(sandyBridge, "Linux", "amd64"))
    }

    @Test
    fun haswellIsFine() {
        assertEquals(none, CpuSupport.missingFeatures(haswell, "Linux", "amd64"))
    }

    @Test
    fun otherPlatformsAndUnreadableCpuinfoAreNotBlocked() {
        assertEquals(none, CpuSupport.missingFeatures(sandyBridge, "Mac OS X", "x86_64"))
        assertEquals(none, CpuSupport.missingFeatures(sandyBridge, "Linux", "aarch64"))
        assertEquals(none, CpuSupport.missingFeatures(null, "Linux", "amd64"))
    }
}
