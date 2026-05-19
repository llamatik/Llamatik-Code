package com.llamatik.teammate.release

/**
 * A scripted agent conversation loaded from a JSON file under
 * src/test/resources/release/agent-scripts/<script-id>.json.
 *
 * JSON schema:
 * {
 *   "id": "read-then-fix",
 *   "description": "Agent reads a file then applies a fix",
 *   "fixtureProject": "kotlin-gradle-simple",
 *   "prompt": "Fix the bug in Calculator.kt",
 *   "responses": [
 *     "<tool_call name=\"read_file\"><arg name=\"path\">src/main/kotlin/Calculator.kt</arg></tool_call>",
 *     "<tool_call name=\"replace_in_file\">...</tool_call>"
 *   ],
 *   "expectToolsUsed": ["read_file", "replace_in_file"],
 *   "expectFilesChanged": ["src/main/kotlin/Calculator.kt"],
 *   "expectFinalMessageContains": [],
 *   "maxRounds": 5
 * }
 */
data class AgentScript(
    val id: String,
    val description: String,
    val fixtureProject: String,
    val prompt: String,
    val responses: List<String>,
    val expectToolsUsed: List<String>,
    val expectFilesChanged: List<String>,
    val expectFinalMessageContains: List<String>,
    val maxRounds: Int,
)
