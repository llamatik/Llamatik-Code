package com.llamatik.teammate.context

import com.intellij.openapi.components.Service

@Service(Service.Level.PROJECT)
class ProjectIndexingState {
    enum class State { IDLE, INDEXING, READY, FAILED }

    @Volatile var state: State = State.IDLE
    @Volatile var indexedFileCount: Int = 0
    @Volatile var totalFileCount: Int = 0

    fun startIndexing() { state = State.INDEXING }
    fun finishIndexing(count: Int) { state = State.READY; indexedFileCount = count; totalFileCount = count }
    fun failIndexing() { state = State.FAILED }
    fun isReady(): Boolean = state == State.READY
}
