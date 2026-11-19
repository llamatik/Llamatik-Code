package com.llamatik.agent.backends.local

import java.nio.file.Files
import java.nio.file.Path

/**
 * The bundled desktop llama.cpp build targets an x86-64 baseline of AVX2 + FMA + F16C. On an older CPU
 * (Intel before Haswell/2013, AMD before Excavator) the first native call executes an illegal instruction
 * and the OS kills the whole process, which for the IDE plugin means the IDE itself. Checking the CPU flags
 * first turns that into an ordinary error message.
 */
internal object CpuSupport {
    private val required = listOf("avx2", "fma", "f16c")

    /** Features the bundled build needs but [cpuinfo] does not list; empty when fine or when unknown. */
    fun missingFeatures(cpuinfo: String?, osName: String, arch: String): List<String> {
        if (!osName.contains("linux", ignoreCase = true)) return emptyList()
        if (arch != "amd64" && arch != "x86_64") return emptyList()
        val flags = cpuinfo?.lineSequence()
            ?.firstOrNull { it.startsWith("flags") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
            ?: return emptyList()
        return required.filterNot { it in flags }
    }

    fun problem(
        cpuinfoPath: Path = Path.of("/proc/cpuinfo"),
        osName: String = System.getProperty("os.name", ""),
        arch: String = System.getProperty("os.arch", ""),
    ): String? {
        val text = runCatching { if (Files.isReadable(cpuinfoPath)) Files.readString(cpuinfoPath) else null }.getOrNull()
        val missing = missingFeatures(text, osName, arch)
        if (missing.isEmpty()) return null
        return "this CPU does not support ${missing.joinToString(", ") { it.uppercase() }}, which the bundled " +
            "llama.cpp build requires (the model was not loaded to avoid crashing the IDE). " +
            "Use a Llamatik server instead (Settings > Llamatik Code > Server)"
    }
}
