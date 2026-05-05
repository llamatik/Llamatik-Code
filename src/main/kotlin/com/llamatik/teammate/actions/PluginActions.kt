package com.llamatik.teammate.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.diagnostics.DiagnosticsService
import com.llamatik.teammate.git.GitContextService
import com.llamatik.teammate.terminal.TerminalService

class AskAboutSelectionAction : BasePromptAction({ _, e ->
    "Explain the selected code and highlight risks or improvement opportunities:\n\n${e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)?.selectionModel?.selectedText.orEmpty()}"
})

class ExplainFileAction : BasePromptAction({ project, _ ->
    "Explain this file, its responsibilities, and key risks: ${com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path.orEmpty()}"
})

class RefactorSelectionAction : BasePromptAction({ _, e ->
    "Refactor this selected code. First explain the plan, then propose a safe patch:\n\n${e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)?.selectionModel?.selectedText.orEmpty()}"
})

class FixSelectedCodeAction : BasePromptAction({ _, e ->
    "Fix the selected code. Use tools if needed and propose a safe patch:\n\n${e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)?.selectionModel?.selectedText.orEmpty()}"
})

class GenerateTestsAction : BasePromptAction({ project, _ ->
    "Generate tests for the current file. Inspect the file first, then propose the new or updated test file. Current file: ${com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path.orEmpty()}"
})

class AddDocumentationAction : BasePromptAction({ project, _ ->
    "Add or improve documentation for the current file. Current file: ${com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path.orEmpty()}"
})

class EditWithPromptAction : BasePromptAction({ project, _ ->
    "Edit the current file based on this prompt. First explain the plan, then use tools and propose a patch. File: ${com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path.orEmpty()}\n\nTask: "
})

class ExplainFailingBuildAction : BasePromptAction({ project, _ ->
    val lastOut = project.service<TerminalService>().lastOutputAny()
    if (lastOut == null) {
        "Run the build first (e.g. use terminal_run with './gradlew build'), then ask me to explain the failure."
    } else {
        "Explain this build failure and suggest how to fix it:\n\n${lastOut.stdout.take(2000)}\n${lastOut.stderr.take(2000)}"
    }
})

class RunDiagnosticsAction : BasePromptAction({ project, _ ->
    val svc = project.service<DiagnosticsService>()
    val report = svc.run()
    val formatted = svc.formatReport(report, includeSensitive = false)
    "Review these Llamatik Code diagnostics and explain any warnings or failures:\n\n$formatted"
})

class ExplainCurrentChangesAction : BasePromptAction({ project, _ ->
    val gitSvc = project.service<GitContextService>()
    if (!gitSvc.isGitRepo()) "This is not a git repository."
    else {
        val ctx = gitSvc.suggestCommitContext(maxDiffChars = 3000)
        "Explain the current git changes in plain language and suggest what the commit message should be.\n\n$ctx"
    }
})
