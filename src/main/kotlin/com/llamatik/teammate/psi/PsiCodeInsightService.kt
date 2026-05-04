package com.llamatik.teammate.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.llamatik.teammate.util.PluginLogger
import java.io.File

@Service(Service.Level.PROJECT)
class PsiCodeInsightService(private val project: Project) {

    private val log = PluginLogger.logger<PsiCodeInsightService>()

    data class FileStructure(
        val path: String,
        val language: String,
        val declarations: List<DeclarationInfo>,
    )

    data class DeclarationInfo(
        val kind: String,
        val name: String,
        val line: Int,
    )

    data class UsageInfo(
        val file: String,
        val line: Int,
        val snippet: String,
    )

    fun fileStructure(relativePath: String): FileStructure {
        val basePath = project.basePath ?: return FileStructure(relativePath, "unknown", emptyList())
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, relativePath))
            ?: return FileStructure(relativePath, "unknown", emptyList())
        return ReadAction.compute<FileStructure, Exception> {
            val psi = PsiManager.getInstance(project).findFile(vFile)
                ?: return@compute FileStructure(relativePath, "unknown", emptyList())
            val lang = psi.language.displayName
            val decls = extractDeclarations(psi)
            FileStructure(relativePath, lang, decls)
        }
    }

    fun findUsages(symbolName: String, maxResults: Int = 20): List<UsageInfo> {
        return ReadAction.compute<List<UsageInfo>, Exception> {
            val scope = GlobalSearchScope.projectScope(project)
            val results = mutableListOf<UsageInfo>()
            // Search across all files for PSI named elements matching the symbol
            FilenameIndex.getAllFilesByExt(project, "kt", scope).forEach { vf ->
                if (results.size >= maxResults) return@forEach
                val psi = PsiManager.getInstance(project).findFile(vf) ?: return@forEach
                PsiTreeUtil.findChildrenOfType(psi, PsiNamedElement::class.java).forEach { elem ->
                    if (results.size >= maxResults) return@forEach
                    if (elem.name == symbolName) {
                        ReferencesSearch.search(elem, scope).forEach { ref ->
                            if (results.size >= maxResults) return@forEach
                            val refFile = ref.element.containingFile ?: return@forEach
                            val doc = com.intellij.openapi.editor.Document::class.java
                            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                                .getDocument(refFile.virtualFile ?: return@forEach)
                            val offset = ref.element.textOffset
                            val line = document?.getLineNumber(offset)?.plus(1) ?: 0
                            val lineStart = document?.getLineStartOffset(line - 1) ?: offset
                            val lineEnd = document?.getLineEndOffset(line - 1) ?: (offset + 80)
                            val snippet = document?.getText(
                                com.intellij.openapi.util.TextRange(lineStart, lineEnd)
                            )?.trim() ?: ""
                            val relPath = refFile.virtualFile?.path
                                ?.removePrefix(project.basePath ?: "")
                                ?.trimStart('/') ?: refFile.name
                            results += UsageInfo(relPath, line, snippet)
                        }
                    }
                }
            }
            results
        }
    }

    fun findDeclaration(symbolName: String): DeclarationInfo? {
        return ReadAction.compute<DeclarationInfo?, Exception> {
            val scope = GlobalSearchScope.projectScope(project)
            FilenameIndex.getAllFilesByExt(project, "kt", scope).forEach { vf ->
                val psi = PsiManager.getInstance(project).findFile(vf) ?: return@forEach
                PsiTreeUtil.findChildrenOfType(psi, PsiNamedElement::class.java).forEach { elem ->
                    if (elem.name == symbolName) {
                        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                            .getDocument(vf)
                        val line = doc?.getLineNumber(elem.textOffset)?.plus(1) ?: 0
                        val kind = elem.javaClass.simpleName
                            .replace("KtClass", "class")
                            .replace("KtFunction", "function")
                            .replace("KtProperty", "property")
                            .lowercase()
                        return@compute DeclarationInfo(kind, symbolName, line)
                    }
                }
            }
            null
        }
    }

    private fun extractDeclarations(psi: PsiFile): List<DeclarationInfo> {
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(psi.virtualFile ?: return emptyList())
        return PsiTreeUtil.findChildrenOfType(psi, PsiNamedElement::class.java)
            .filter { elem ->
                val name = elem.name ?: return@filter false
                name.isNotBlank() && !name.startsWith("<")
            }
            .map { elem ->
                val line = doc?.getLineNumber(elem.textOffset)?.plus(1) ?: 0
                val kind = elem.javaClass.simpleName
                    .replace("KtClass", "class")
                    .replace("KtFunction", "fun")
                    .replace("KtProperty", "val")
                    .lowercase()
                DeclarationInfo(kind, elem.name ?: "", line)
            }
            .take(100)
    }
}
