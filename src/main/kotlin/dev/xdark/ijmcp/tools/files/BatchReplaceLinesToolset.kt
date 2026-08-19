@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.files

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

class BatchReplaceLinesToolset : McpToolset {

	@Serializable
	data class Replacement(
		val filePath: String,
		val startLine: Int,
		val endLine: Int,
		val newText: String = "",
	)

	@McpTool
	@McpDescription(
		"""
        |Replaces multiple line ranges across one or more files in a single call.
        |Much more efficient than multiple replace_lines calls when making several changes.
        |
        |Each replacement has: filePath, startLine, endLine, newText (all 1-based, inclusive).
        |Ranges within the same file must not overlap.
        |Replacements are applied bottom-to-top per file so line numbers stay stable.
        |
        |IMPORTANT — boundary alignment:
        |  Your newText for each replacement must cover EXACTLY lines startLine..endLine.
        |  - First line of newText replaces startLine
        |  - Last line of newText replaces endLine
        |  Do NOT include content from the line BEFORE startLine or AFTER endLine.
        |  Verify: compare the first/last line of your newText against startLine/endLine in the file.
        |  Common mistake: including the closing brace/paren from endLine+1 in newText.
    """
	)
	suspend fun batch_replace_lines(
		@McpDescription("Array of replacements to apply") replacements: List<Replacement>,
	): Any {
		if (replacements.isEmpty()) mcpFail("replacements must not be empty")

		val project = currentCoroutineContext().project
		val byFile = replacements.groupBy { it.filePath }

		return buildString {
			for ((filePath, fileReplacements) in byFile) {
				val resolved = resolveFile(project, filePath)
				val document = resolved.document
				val lineCount = document.lineCount

				val sorted = fileReplacements.sortedByDescending { it.startLine }

				for (r in sorted) {
					if (r.startLine <= 0) mcpFail("$filePath: startLine must be > 0 (got ${r.startLine})")
					if (r.endLine <= 0) mcpFail("$filePath: endLine must be > 0 (got ${r.endLine})")
					if (r.startLine > r.endLine) mcpFail("$filePath: startLine (${r.startLine}) must be <= endLine (${r.endLine})")
					if (r.startLine > lineCount) mcpFail("$filePath: startLine (${r.startLine}) exceeds file length ($lineCount lines)")
					if (r.endLine > lineCount) mcpFail("$filePath: endLine (${r.endLine}) exceeds file length ($lineCount lines)")
				}

				for (i in 0 until sorted.size - 1) {
					val upper = sorted[i]
					val lower = sorted[i + 1]
					if (lower.endLine >= upper.startLine) {
						mcpFail("$filePath: overlapping ranges [${lower.startLine}-${lower.endLine}] and [${upper.startLine}-${upper.endLine}]")
					}
				}

				withContext(Dispatchers.EDT) {
					WriteCommandAction.runWriteCommandAction(project) {
						for (r in sorted) {
							val startOffset = document.getLineStartOffset(r.startLine - 1)
							val endOffset = document.getLineEndOffset(r.endLine - 1)
							document.replaceString(startOffset, endOffset, r.newText)
						}
					}
					FileDocumentManager.getInstance().saveDocument(document)
				}

				append(filePath).append(": ").append(fileReplacements.size).appendLine(" replacement(s) applied")
				appendContext(document, sorted.last().startLine)
			}
		}.trimEnd()
	}

	private fun StringBuilder.appendContext(document: Document, firstChangeLine: Int) {
		val lineCount = document.lineCount
		val contextStart = max(1, firstChangeLine - 1)
		val contextEnd = min(lineCount, firstChangeLine + 3)
		val chars = document.immutableCharSequence
		for (lineNumber in contextStart..contextEnd) {
			val lineIndex = lineNumber - 1
			append("  L").append(lineNumber).append(": ")
			append(chars, document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
			append('\n')
		}
	}
}
