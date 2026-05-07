package com.llamatik.teammate.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.util.PluginLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Manages MCP stdio server processes.
 *
 * Each server runs as a child process communicating over stdin/stdout using
 * JSON-RPC 2.0 (the MCP protocol wire format).  Isolation guarantee: a crashed
 * server is marked FAILED and the rest of the plugin continues unaffected.
 *
 * Thread safety: all public methods are safe to call from any thread.
 * No IntelliJ EDT work happens inside this class — callers must dispatch to EDT
 * themselves when they need to update UI after receiving a result.
 */
@Service(Service.Level.APP)
class McpClientService {

    private val logger = PluginLogger.logger<McpClientService>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "llamatik-mcp").also { it.isDaemon = true }
    }

    // Active server sessions, keyed by config id
    private val sessions = ConcurrentHashMap<String, McpSession>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun startServer(config: McpServerConfig): McpServerStatus {
        if (sessions.containsKey(config.id)) {
            val existing = sessions[config.id]!!
            if (existing.state == McpServerState.RUNNING) return existing.status()
            sessions.remove(config.id)?.stop()
        }

        val session = McpSession(config)
        sessions[config.id] = session

        return try {
            session.start()
            session.initialize()
            logger.info("McpClientService: started '${config.name}' (id=${config.id})")
            session.status()
        } catch (e: Exception) {
            logger.warn("McpClientService: failed to start '${config.name}'", e)
            session.markFailed(e.message ?: "Unknown error")
            session.status()
        }
    }

    fun stopServer(serverId: String) {
        sessions.remove(serverId)?.also {
            it.stop()
            logger.info("McpClientService: stopped server id=$serverId")
        }
    }

    fun stopAll() {
        sessions.keys.toList().forEach { stopServer(it) }
    }

    fun serverStatus(serverId: String): McpServerStatus =
        sessions[serverId]?.status() ?: McpServerStatus(serverId, McpServerState.STOPPED)

    fun allStatuses(): Map<String, McpServerStatus> =
        sessions.mapValues { it.value.status() }

    // ── Discovery ─────────────────────────────────────────────────────────────

    fun listTools(serverId: String): List<McpToolDescriptor> =
        withSession(serverId) { it.listTools() } ?: emptyList()

    fun listResources(serverId: String): List<McpResourceDescriptor> =
        withSession(serverId) { it.listResources() } ?: emptyList()

    fun listPrompts(serverId: String): List<McpPromptDescriptor> =
        withSession(serverId) { it.listPrompts() } ?: emptyList()

    // ── Invocation ────────────────────────────────────────────────────────────

    fun callTool(serverId: String, toolName: String, arguments: Map<String, Any>): McpCallResult {
        val t0 = System.currentTimeMillis()
        return withSession(serverId) { session ->
            session.callTool(toolName, arguments)
        } ?: McpCallResult(
            serverId = serverId,
            toolName = toolName,
            success = false,
            content = "",
            error = "Server '$serverId' is not running.",
            durationMs = System.currentTimeMillis() - t0,
        )
    }

    fun readResource(serverId: String, uri: String): McpCallResult {
        val t0 = System.currentTimeMillis()
        return withSession(serverId) { session ->
            session.readResource(uri)
        } ?: McpCallResult(
            serverId = serverId,
            toolName = "read_resource",
            success = false,
            content = "",
            error = "Server '$serverId' is not running.",
            durationMs = System.currentTimeMillis() - t0,
        )
    }

    // ── Logging ───────────────────────────────────────────────────────────────

    fun getServerLogs(serverId: String): List<String> =
        sessions[serverId]?.getRecentLogs() ?: emptyList()

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun <T> withSession(serverId: String, block: (McpSession) -> T): T? {
        val session = sessions[serverId] ?: return null
        if (session.state != McpServerState.RUNNING) return null
        return try {
            block(session)
        } catch (e: Exception) {
            logger.warn("McpClientService: error calling session $serverId", e)
            session.markFailed(e.message ?: "Unknown error")
            null
        }
    }

    // ── Session ───────────────────────────────────────────────────────────────

    inner class McpSession(private val config: McpServerConfig) {
        var state: McpServerState = McpServerState.STOPPED
            private set

        private var process: Process? = null
        private var writer: BufferedWriter? = null
        private var reader: BufferedReader? = null
        private val lock = ReentrantLock()
        private val idGen = AtomicLong(1)
        private val recentLogs = ArrayDeque<String>(MAX_LOG_LINES)
        private var lastError: String? = null
        private var lastConnectedAt: Long? = null

        fun start() {
            state = McpServerState.STARTING
            val cmd = buildList {
                add(config.command)
                addAll(config.args)
            }
            val pb = ProcessBuilder(cmd).apply {
                if (config.workingDirectory.isNotBlank()) {
                    directory(java.io.File(config.workingDirectory))
                }
                val env = environment()
                config.env.forEach { (k, v) -> env[k] = v }
                redirectErrorStream(false)
            }
            val proc = pb.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))

            // Capture stderr in background
            executor.execute {
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        val redacted = redactSecrets(line)
                        addLog("[stderr] $redacted")
                    }
                } catch (_: Exception) {}
            }
        }

        fun initialize() {
            val initResponse = sendRequest(
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to mapOf("roots" to mapOf("listChanged" to false)),
                    "clientInfo" to mapOf("name" to "llamatik-code", "version" to "1.0"),
                )
            )
            checkError(initResponse, "initialize")

            sendNotification("notifications/initialized")
            state = McpServerState.RUNNING
            lastConnectedAt = System.currentTimeMillis()
            lastError = null
        }

        fun listTools(): List<McpToolDescriptor> {
            val response = sendRequest("tools/list", emptyMap())
            checkError(response, "tools/list")
            val tools = (getNestedMap(response, "result", "tools") as? List<*>) ?: return emptyList()
            return tools.filterIsInstance<Map<*, *>>().map { t ->
                val name = t["name"] as? String ?: ""
                val desc = t["description"] as? String ?: ""
                @Suppress("UNCHECKED_CAST")
                val schema = t["inputSchema"] as? Map<String, Any> ?: emptyMap()
                val risk = service<McpPermissionService>().classifyRisk(name, desc)
                McpToolDescriptor(
                    serverId = config.id,
                    name = name,
                    description = desc,
                    inputSchema = schema,
                    riskLevel = risk,
                    requiresApproval = risk != McpRiskLevel.LOW,
                )
            }
        }

        fun listResources(): List<McpResourceDescriptor> {
            val response = sendRequest("resources/list", emptyMap())
            if ((response["error"] as? Map<*, *>) != null) return emptyList()
            val resources = (getNestedMap(response, "result", "resources") as? List<*>) ?: return emptyList()
            return resources.filterIsInstance<Map<*, *>>().map { r ->
                McpResourceDescriptor(
                    serverId = config.id,
                    uri = r["uri"] as? String ?: "",
                    name = r["name"] as? String ?: "",
                    description = r["description"] as? String ?: "",
                    mimeType = r["mimeType"] as? String ?: "",
                )
            }
        }

        fun listPrompts(): List<McpPromptDescriptor> {
            val response = sendRequest("prompts/list", emptyMap())
            if ((response["error"] as? Map<*, *>) != null) return emptyList()
            val prompts = (getNestedMap(response, "result", "prompts") as? List<*>) ?: return emptyList()
            return prompts.filterIsInstance<Map<*, *>>().map { p ->
                McpPromptDescriptor(
                    serverId = config.id,
                    name = p["name"] as? String ?: "",
                    description = p["description"] as? String ?: "",
                )
            }
        }

        fun callTool(toolName: String, arguments: Map<String, Any>): McpCallResult {
            val t0 = System.currentTimeMillis()
            val response = sendRequest(
                "tools/call",
                mapOf("name" to toolName, "arguments" to arguments)
            )
            val elapsed = System.currentTimeMillis() - t0
            val error = (response["error"] as? Map<*, *>)?.get("message") as? String
            if (error != null) {
                return McpCallResult(config.id, toolName, false, "", error, elapsed)
            }
            val result = response["result"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val content = extractContent(result)
            return McpCallResult(config.id, toolName, true, content, null, elapsed)
        }

        fun readResource(uri: String): McpCallResult {
            val t0 = System.currentTimeMillis()
            val response = sendRequest("resources/read", mapOf("uri" to uri))
            val elapsed = System.currentTimeMillis() - t0
            val error = (response["error"] as? Map<*, *>)?.get("message") as? String
            if (error != null) {
                return McpCallResult(config.id, "read_resource", false, "", error, elapsed)
            }
            val result = response["result"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val contents = result["contents"] as? List<*> ?: emptyList<Any>()
            val text = contents.filterIsInstance<Map<*, *>>().joinToString("\n") {
                it["text"] as? String ?: it["blob"] as? String ?: ""
            }
            return McpCallResult(config.id, "read_resource", true, text, null, elapsed)
        }

        fun markFailed(error: String) {
            state = McpServerState.FAILED
            lastError = error
            addLog("[error] $error")
        }

        fun stop() {
            state = McpServerState.STOPPED
            try { writer?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
        }

        fun status() = McpServerStatus(
            serverId = config.id,
            state = state,
            lastError = lastError,
            lastConnectedAt = lastConnectedAt,
        )

        fun getRecentLogs(): List<String> = synchronized(recentLogs) { recentLogs.toList() }

        // ── JSON-RPC ─────────────────────────────────────────────────────────

        private fun sendRequest(method: String, params: Map<String, Any>): Map<String, Any> {
            val id = idGen.getAndIncrement()
            val requestJson = buildJsonRpcRequest(id, method, params)
            return lock.withTimed {
                write(requestJson)
                readResponse(id)
            }
        }

        private fun sendNotification(method: String) {
            val json = buildJsonRpcNotification(method)
            lock.withTimed { write(json) }
        }

        private fun write(json: String) {
            val w = writer ?: throw IllegalStateException("Writer not open")
            w.write(json)
            w.newLine()
            w.flush()
            addLog("[send] ${redactSecrets(json).take(200)}")
        }

        private fun readResponse(expectedId: Long): Map<String, Any> {
            val r = reader ?: throw IllegalStateException("Reader not open")
            val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = r.readLine()
                    ?: throw IllegalStateException("MCP server closed connection")
                if (line.isBlank()) continue
                addLog("[recv] ${line.take(200)}")
                val parsed = parseJson(line)
                if (parsed["id"] == expectedId || parsed["id"].toString() == expectedId.toString()) {
                    return parsed
                }
                // Notifications arrive interleaved — discard and keep reading
            }
            throw IllegalStateException("Timeout waiting for MCP response (method id=$expectedId)")
        }

        private fun checkError(response: Map<String, Any>, method: String) {
            val error = (response["error"] as? Map<*, *>)?.get("message") as? String
            if (error != null) throw IllegalStateException("MCP $method error: $error")
        }

        @Suppress("UNCHECKED_CAST")
        private fun extractContent(result: Map<*, *>): String {
            val content = result["content"] as? List<*> ?: return result.toString()
            return content.filterIsInstance<Map<*, *>>().joinToString("\n") { item ->
                when (item["type"] as? String) {
                    "text" -> item["text"] as? String ?: ""
                    "resource" -> {
                        val resource = item["resource"] as? Map<*, *> ?: emptyMap<Any, Any>()
                        resource["text"] as? String ?: resource["uri"] as? String ?: ""
                    }
                    else -> item.toString()
                }
            }
        }

        private fun getNestedMap(map: Map<String, Any>, vararg keys: String): Any? {
            var current: Any? = map
            for (key in keys) {
                current = (current as? Map<*, *>)?.get(key)
            }
            return current
        }

        private fun addLog(line: String) = synchronized(recentLogs) {
            if (recentLogs.size >= MAX_LOG_LINES) recentLogs.removeFirst()
            recentLogs.addLast(line)
        }

        private fun <T> ReentrantLock.withTimed(block: () -> T): T {
            if (!tryLock(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("MCP session is busy (lock timeout)")
            }
            return try { block() } finally { unlock() }
        }
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 35_000L
        private const val MAX_LOG_LINES = 200

        // ── Minimal JSON-RPC builders (no external JSON library required) ─────

        fun buildJsonRpcRequest(id: Long, method: String, params: Map<String, Any>): String {
            val paramsJson = mapToJson(params)
            return """{"jsonrpc":"2.0","id":$id,"method":${escapeString(method)},"params":$paramsJson}"""
        }

        fun buildJsonRpcNotification(method: String): String =
            """{"jsonrpc":"2.0","method":${escapeString(method)}}"""

        fun mapToJson(value: Any?): String = when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Number -> value.toString()
            is String -> escapeString(value)
            is List<*> -> "[${value.joinToString(",") { mapToJson(it) }}]"
            is Map<*, *> -> "{${value.entries.joinToString(",") { (k, v) -> "${escapeString(k.toString())}:${mapToJson(v)}" }}}"
            else -> escapeString(value.toString())
        }

        fun escapeString(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
                }
            }
            sb.append("\"")
            return sb.toString()
        }

        /**
         * Minimal recursive JSON parser.  Only handles the subset of JSON that MCP
         * servers produce: objects, arrays, strings, numbers, booleans, null.
         * Does NOT handle all edge cases — for production use this should be replaced
         * with a proper JSON library once the project adds one as a dependency.
         */
        fun parseJson(json: String): Map<String, Any> {
            @Suppress("UNCHECKED_CAST")
            return (parseValue(json.trim(), 0).first as? Map<String, Any>) ?: emptyMap()
        }

        private fun parseValue(s: String, start: Int): Pair<Any?, Int> {
            if (start >= s.length) return Pair(null, start)
            return when (s[start]) {
                '{' -> parseObject(s, start)
                '[' -> parseArray(s, start)
                '"' -> parseString(s, start)
                't' -> Pair(true, start + 4)
                'f' -> Pair(false, start + 5)
                'n' -> Pair(null, start + 4)
                else -> parseNumber(s, start)
            }
        }

        private fun parseObject(s: String, start: Int): Pair<Map<String, Any?>, Int> {
            val map = mutableMapOf<String, Any?>()
            var i = start + 1
            while (i < s.length) {
                i = skipWhitespace(s, i)
                if (s[i] == '}') return Pair(map, i + 1)
                if (s[i] == ',') { i++; continue }
                val (key, afterKey) = parseString(s, i)
                i = skipWhitespace(s, afterKey)
                if (i < s.length && s[i] == ':') i++
                i = skipWhitespace(s, i)
                val (value, afterValue) = parseValue(s, i)
                map[key] = value
                i = afterValue
            }
            return Pair(map, i)
        }

        private fun parseArray(s: String, start: Int): Pair<List<Any?>, Int> {
            val list = mutableListOf<Any?>()
            var i = start + 1
            while (i < s.length) {
                i = skipWhitespace(s, i)
                if (s[i] == ']') return Pair(list, i + 1)
                if (s[i] == ',') { i++; continue }
                val (value, after) = parseValue(s, i)
                list.add(value)
                i = after
            }
            return Pair(list, i)
        }

        private fun parseString(s: String, start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var i = start + 1
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> return Pair(sb.toString(), i + 1)
                    '\\' -> {
                        i++
                        if (i < s.length) when (s[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'u' -> {
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                i += 4
                            }
                            else -> sb.append(s[i])
                        }
                    }
                    else -> sb.append(c)
                }
                i++
            }
            return Pair(sb.toString(), i)
        }

        private fun parseNumber(s: String, start: Int): Pair<Any, Int> {
            var i = start
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val num = s.substring(start, i)
            return Pair(num.toLongOrNull() ?: num.toDoubleOrNull() ?: num, i)
        }

        private fun skipWhitespace(s: String, start: Int): Int {
            var i = start
            while (i < s.length && s[i].isWhitespace()) i++
            return i
        }

        // Ordered: Bearer first so the token value is captured before a broader Authorization match.
        private val SECRET_PATTERNS = listOf(
            // Bearer <token> — captures the token that follows the keyword + whitespace
            Regex("(?i)(bearer)\\s+([^\\s\"',}{]{4,})"),
            // key=value / key:"value" secrets for common sensitive key names
            Regex("(?i)(token|password|secret|apikey|api_key|authorization)[\"\\s:=]+[^\"\\s,}{]{4,}"),
        )

        fun redactSecrets(s: String): String {
            var result = s
            for (pattern in SECRET_PATTERNS) {
                result = result.replace(pattern) { mr ->
                    val prefix = mr.groupValues.getOrElse(1) { "" }
                    "$prefix=[REDACTED]"
                }
            }
            return result
        }
    }
}
