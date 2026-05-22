package com.llamatik.teammate.release

/**
 * A single tool contract test case loaded from a JSON file under
 * src/test/resources/release/tool-cases/<tool-name>/<case-id>.json.
 *
 * JSON schema:
 * {
 *   "id": "basic-read",
 *   "tool": "read_file",
 *   "arguments": { "path": "src/Main.kt" },
 *   "fixtureProject": "kotlin-gradle-simple",  // optional; resolves to release/fixtures/projects/<name>
 *   "expectSuccess": true,
 *   "expectContentContains": ["class Main"],    // optional
 *   "expectContentNotContains": [],             // optional
 *   "description": "reads a file that exists"
 * }
 */
data class ToolContractCase(
    val id: String,
    val tool: String,
    val arguments: Map<String, String>,
    val fixtureProject: String?,
    val expectSuccess: Boolean,
    val expectContentContains: List<String>,
    val expectContentNotContains: List<String>,
    val description: String,
)
