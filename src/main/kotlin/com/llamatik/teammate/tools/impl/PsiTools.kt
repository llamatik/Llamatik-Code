package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.psi.PsiCodeInsightService
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult

class PsiFileStructureTool(project: Project) : BaseProjectTool(project) {
    override val name = "psi_file_structure"
    override val description = "List classes/functions/properties in a file using PSI. Arguments: path"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val path = call.arguments["path"] ?: return ToolResult(name, false, "Missing path")
        return try {
            val svc = context.project.service<PsiCodeInsightService>()
            val structure = svc.fileStructure(path)
            if (structure.declarations.isEmpty()) return ToolResult(name, true, "No declarations found in $path (language: ${structure.language})")
            val text = buildString {
                appendLine("File: $path (${structure.language})")
                structure.declarations.forEach { d ->
                    appendLine("  ${d.kind.padEnd(10)} ${d.name}  (line ${d.line})")
                }
            }.trim()
            ToolResult(name, true, text)
        } catch (e: Exception) {
            ToolResult(name, false, "PSI unavailable for $path: ${e.message}")
        }
    }
}

class PsiFindSymbolTool(project: Project) : BaseProjectTool(project) {
    override val name = "psi_find_symbol"
    override val description = "Find where a symbol (class/function/property) is declared. Arguments: symbol"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val symbol = call.arguments["symbol"] ?: return ToolResult(name, false, "Missing symbol")
        return try {
            val svc = context.project.service<PsiCodeInsightService>()
            val decl = svc.findDeclaration(symbol)
                ?: return ToolResult(name, true, "Symbol '$symbol' not found in project")
            ToolResult(name, true, "${decl.kind} '${decl.name}' declared at line ${decl.line}")
        } catch (e: Exception) {
            ToolResult(name, false, "PSI search failed: ${e.message}")
        }
    }
}

class PsiFindUsagesTool(project: Project) : BaseProjectTool(project) {
    override val name = "psi_find_usages"
    override val description = "Find all usages of a symbol. Arguments: symbol"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val symbol = call.arguments["symbol"] ?: return ToolResult(name, false, "Missing symbol")
        return try {
            val svc = context.project.service<PsiCodeInsightService>()
            val usages = svc.findUsages(symbol)
            if (usages.isEmpty()) return ToolResult(name, true, "No usages found for '$symbol'")
            val text = usages.joinToString("\n") { u -> "${u.file}:${u.line}  ${u.snippet}" }
            ToolResult(name, true, "Usages of '$symbol':\n$text")
        } catch (e: Exception) {
            ToolResult(name, false, "PSI search failed: ${e.message}")
        }
    }
}
