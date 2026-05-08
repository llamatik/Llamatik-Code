package com.llamatik.teammate.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.llamatik.teammate.mcp.McpServerConfigService
import com.llamatik.teammate.model.ModelDescriptor
import java.nio.file.Path

@State(name = "LlamatikCodeSettings", storages = [Storage("llamatik_code_settings.xml")])
@Service(Service.Level.APP)
class LlamatikAppSettings : PersistentStateComponent<LlamatikAppSettings.State> {
    data class State(
        var modelPath: String = "",
        var modelIdentifier: String = "",
        var temperature: Double = 0.7,
        var maxTokens: Int = 8192,
        var contextSize: Int = 16384,
        var numThreads: Int = 4,
        var useMmap: Boolean = true,
        var flashAttention: Boolean = false,
        var batchSize: Int = 2048,
        var localOnly: Boolean = true,
        var backendId: String = "llamatik",
        var ignoredDirectories: String = ".git,node_modules,build,out,target,.idea,.gradle,.gradle-cache,__pycache__,.tox,dist,.kotlin,.cxx,.cache,generated,intermediates",
        var projectRules: String = "",
        var autoApply: Boolean = false,
        var maxFileSizeKb: Int = 256,
        var maxContextChars: Int = 40000,
        var maxChangedFiles: Int = 10,
        var downloadedModels: MutableList<String> = mutableListOf(),
        /** Epoch ms when the trial was first started. 0 means not yet initialised. */
        var trialStartedAtEpoch: Long = 0L,
        var onboardingCompleted: Boolean = false,
        var unloadModelOnIdle: Boolean = false,
        var trialWarningDismissed: Boolean = false,
        var indexingExcludedFolders: String = ".git,node_modules,build,out,target,.idea,.gradle",
        var agentAutoApprove: Boolean = false,
        var agentAutoBuild: Boolean = true,
        var agentAutoFix: Boolean = false,
        var modelStoragePath: String = "",
        var downloadedSkillIds: MutableList<String> = mutableListOf(),
        var activeSkillIds: MutableList<String> = mutableListOf(),
        /** Path to the companion mmproj GGUF file for multimodal/VLM inference. Empty = not configured. */
        var mmprojPath: String = "",
        var downloadedMmprojs: MutableList<String> = mutableListOf(),
        var agentExecutionMode: String = "NORMAL",
        /** MCP server configurations — persisted as flat objects for XmlSerializer compatibility. */
        var mcpServers: MutableList<McpServerConfigService.StoredMcpServer> = mutableListOf(),
        /** Per-tool auto-approval entries: "serverId::toolName" for Pro users. */
        var mcpAutoApprovedTools: MutableList<String> = mutableListOf(),
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        this.myState = state
    }

    fun data(): State = myState

    fun storageDir(): Path = Path.of(System.getProperty("user.home"), ".llamatik", "models")

    fun predefinedModels(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "Qwen3-14B-Q4_K_M",
            displayName = "Qwen3 14B Q4_K_M",
            source = "Hugging Face GGUF",
            description = "Recommended local default for the plugin. Strong reasoning and coding balance for refactors, code understanding, and architecture work.",
        ),
        ModelDescriptor(
            id = "Qwen2.5-Coder-7B-Instruct-Q4_K_M",
            displayName = "Qwen2.5 Coder 7B Q4_K_M",
            source = "Hugging Face GGUF",
            description = "Smaller coding-specialized model for lower RAM usage and fast everyday IntelliJ assistance.",
        ),
        ModelDescriptor(
            id = "Gemma-4-E4B-Q4_K_M",
            displayName = "Gemma 4 E4B Q4_K_M",
            source = "Gemma / Google model distribution",
            description = "Small modern generalist with strong reasoning and useful coding performance for local plugin workflows.",
        ),
        ModelDescriptor(
            id = "DeepSeek-R1-Distill-Qwen-32B-Q4_K_M",
            displayName = "DeepSeek R1 Distill Qwen 32B Q4_K_M",
            source = "Manual local download",
            description = "High-end reasoning-oriented option for larger machines; useful for complex architecture and analysis tasks.",
        ),
    )
}
