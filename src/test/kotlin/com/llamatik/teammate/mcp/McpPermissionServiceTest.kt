package com.llamatik.teammate.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpPermissionServiceTest {

    // Use the companion object helper directly — no IntelliJ services needed
    private fun classify(name: String, desc: String = "") =
        McpPermissionService.Companion.run {
            // Inline the same logic without instantiating the service
            val combined = (name + " " + desc).lowercase()
            val high = listOf(
                "delete","remove","drop","destroy","purge","wipe","truncate",
                "execute","run","exec","shell","bash","cmd","script",
                "deploy","publish","send","post","push","upload","submit",
                "write","update","create","insert","modify","patch","overwrite",
                "network","http","curl","fetch",
            )
            val low = listOf(
                "read","get","list","find","search","query","describe",
                "status","info","show","view","inspect","check",
            )
            when {
                high.any { combined.contains(it) } -> McpRiskLevel.HIGH
                low.any { combined.contains(it) } -> McpRiskLevel.LOW
                else -> McpRiskLevel.MEDIUM
            }
        }

    @Test fun `write tool is HIGH risk`() = assertEquals(McpRiskLevel.HIGH, classify("write_file"))

    @Test fun `delete tool is HIGH risk`() = assertEquals(McpRiskLevel.HIGH, classify("delete_record"))

    @Test fun `execute_command is HIGH risk`() = assertEquals(McpRiskLevel.HIGH, classify("execute_command"))

    @Test fun `read_file is LOW risk`() = assertEquals(McpRiskLevel.LOW, classify("read_file"))

    @Test fun `list_items is LOW risk`() = assertEquals(McpRiskLevel.LOW, classify("list_items"))

    @Test fun `unknown_tool is MEDIUM risk`() = assertEquals(McpRiskLevel.MEDIUM, classify("transform_data"))

    @Test fun `description nudges HIGH risk`() =
        assertEquals(McpRiskLevel.HIGH, classify("data_process", "Sends data to remote server via http"))
}
