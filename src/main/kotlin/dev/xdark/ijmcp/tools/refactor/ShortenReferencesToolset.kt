@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.refactor

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import dev.xdark.ijmcp.util.resolveFilesByPatterns
import dev.xdark.ijmcp.util.resolvePsi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.psi.KtFile

class ShortenReferencesToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Shortens fully qualified class references to simple names and adds imports.
        |Handles name clashes automatically — leaves FQ names where shortening would be ambiguous.
        |Supports both Java and Kotlin files.
        |
        |filePaths accepts a list of file paths or glob patterns (e.g. "src/**/*.java").
        |Glob mode processes all matching Java/Kotlin files in the project.
        |
        |Use after editing code with fully qualified names (e.g. via replace_text_in_file).
    """
	)
	suspend fun shorten_references(
		@McpDescription("List of file paths or glob patterns (e.g. 'src/**/*.java')") filePaths: List<String>,
	): Any {
		val project = currentCoroutineContext().project
		val psiFiles = resolveFilesByPatterns(project, filePaths, extensions = setOf("java", "kt")).resolvePsi(project)

		if (psiFiles.isEmpty()) {
			return "No Java/Kotlin files match ${filePaths.joinToString(", ")}"
		}

		val textsBefore = readAction {
			psiFiles.associate { it.relativePath to it.document.text }
		}

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				for (entry in psiFiles) {
					shortenFile(project, entry.psiFile)
				}
			}
		}

		val changedFiles = readAction {
			psiFiles.filter { it.document.text != textsBefore[it.relativePath] }
				.map { it.relativePath }
		}

		withContext(Dispatchers.EDT) {
			val fdm = FileDocumentManager.getInstance()
			for (entry in psiFiles) {
				fdm.saveDocument(entry.document)
			}
		}

		val message = buildString {
			if (psiFiles.size == 1) {
				if (changedFiles.isNotEmpty()) append("Shortened references in ").append(psiFiles[0].relativePath)
				else append("No fully qualified references to shorten in ").append(psiFiles[0].relativePath)
			} else {
				append("Processed ").append(psiFiles.size).append(" files, ").append(changedFiles.size)
					.append(" changed")
				if (changedFiles.isNotEmpty()) {
					append(":\n")
					changedFiles.forEach { append("  ").append(it).append('\n') }
				}
			}
		}

		return message.trimEnd()
	}

	private fun shortenFile(project: Project, psiFile: PsiFile) {
		if (psiFile is KtFile) {
			ShortenReferencesFacility.getInstance().shorten(psiFile, psiFile.textRange)
		} else {
			JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiFile)
		}
	}
}
