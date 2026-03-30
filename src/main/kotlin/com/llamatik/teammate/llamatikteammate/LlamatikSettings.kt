package com.llamatik.teammate.llamatikteammate

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "LlamatikSettings", storages = [Storage("llamatik_teammate.xml")])
@Service(Service.Level.APP)
class LlamatikSettings : PersistentStateComponent<LlamatikSettings.State> {

    data class State(
        var modelPath: String = ""
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun modelPath(): String = state.modelPath.trim()
    fun setModelPath(path: String) { state = state.copy(modelPath = path) }
}
