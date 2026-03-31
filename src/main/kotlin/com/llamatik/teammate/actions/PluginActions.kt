package com.llamatik.teammate.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

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
