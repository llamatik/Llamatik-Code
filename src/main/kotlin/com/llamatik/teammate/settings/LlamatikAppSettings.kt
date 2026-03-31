package com.llamatik.teammate.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.llamatik.teammate.model.ModelDescriptor
import java.nio.file.Path

@State(name = "LlamatikCodeSettings", storages = [Storage("llamatik_code_settings.xml")])
@Service(Service.Level.APP)
class LlamatikAppSettings : PersistentStateComponent<LlamatikAppSettings.State> {
    data class State(
        var modelPath: String = "",
        var modelIdentifier: String = "",
        var temperature: Double = 0.7,
        var maxTokens: Int = 1024,
        var contextSize: Int = 8192,
        var localOnly: Boolean = true,
        var backendId: String = "llamatik",
        var ignoredDirectories: String = ".git,node_modules,build,out,target,.idea",
        var projectRules: String = "",
        var autoApply: Boolean = false,
        var maxFileSizeKb: Int = 256,
        var maxContextChars: Int = 40000,
        var maxChangedFiles: Int = 10,
        var downloadedModels: MutableList<String> = mutableListOf(),
        /** Epoch ms when the trial was first started. 0 means not yet initialised. */
        var trialStartedAtEpoch: Long = 0L,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        this.myState = state
    }

    fun data(): State = myState

    fun storageDir(): Path = Path.of(PathManager.getPluginsPath(), "llamatik-code-models")

    fun predefinedModels(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "Qwen3-14B-Q4_K_M",
            displayName = "Qwen3 14B Q4_K_M",
            source = "Manual local download",
            description = "Recommended local default for the plugin. Download the GGUF yourself and then select the file path below.",
        ),
        ModelDescriptor(
            id = "Qwen2.5-Coder-7B-Instruct-Q4_K_M",
            displayName = "Qwen2.5 Coder 7B Q4_K_M",
            source = "Manual local download",
            description = "Smaller local coding model for lower storage and RAM usage.",
        ),
        ModelDescriptor(
            id = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M",
            displayName = "DeepSeek R1 Distill Qwen 7B Q4_K_M",
            source = "Manual local download",
            description = "Reasoning-oriented option, but still requires a manually downloaded GGUF file.",
        ),
    )
}
