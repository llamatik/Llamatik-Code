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
        var modelIdentifier: String = "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF",
        var temperature: Double = 0.2,
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
        ModelDescriptor("Qwen/Qwen2.5-Coder-7B-Instruct-GGUF", "Qwen 2.5 Coder 7B", "Hugging Face", "Balanced coding model for local use."),
        ModelDescriptor("bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF", "DeepSeek R1 Distill Qwen 7B", "Hugging Face", "Reasoning-oriented local coding model."),
        ModelDescriptor("lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF", "Llama 3.1 8B Instruct", "Hugging Face", "General local assistant model."),
    )
}
