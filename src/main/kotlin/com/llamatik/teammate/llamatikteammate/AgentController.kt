package com.llamatik.teammate.llamatikteammate

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.library.platform.LlamaBridge

class AgentController(private val project: Project) {

    private var isInitOk: Boolean? = null

    fun send(prompt: String, callback: (String) -> Unit) {
        val modelPath = service<LlamatikSettings>().modelPath()
        if (modelPath.isBlank()) {
            callback(
                "No model configured.\n\n" +
                        "Go to Settings → Tools → Llamatik Teammate and set the path to a .gguf model."
            )
            return
        }

        // Init once per IDE session
        val ok = isInitOk ?: run {
            val initOk = LlamaBridge.initGenerateModel(modelPath)
            isInitOk = initOk
            initOk
        }

        if (!ok) {
            callback("Failed to init model at:\n$modelPath\n\n(See idea.log for native loading details.)")
            return
        }

        val context = collectProjectContext()
        val fullPrompt = buildPrompt(prompt, context)

        try {
            val text = LlamaBridge.generate(fullPrompt)
            callback(text)
        } catch (t: Throwable) {
            callback("Error while generating: ${t.message}")
        }
    }

    private fun collectProjectContext(): String {
        // Keep it minimal for now; we can add “selected editor text” next.
        return "Project: ${project.name}"
    }

    private fun buildPrompt(userPrompt: String, context: String): String =
        """
You are an expert Kotlin + IntelliJ plugin assistant.

$context

User:
$userPrompt
        """.trimIndent()
}
