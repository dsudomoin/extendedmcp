@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.files

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

private fun findMismatchDiagnostic(documentText: String, searchText: String): String {
	val anchorLen = minOf(20, searchText.length)
	val anchor = searchText.substring(0, anchorLen)
	var bestMatchLen = 0
	var bestOffset = -1
	var start = 0
	while (true) {
		val idx = documentText.indexOf(anchor, start)
		if (idx < 0) break
		var matchLen = anchorLen
		val limit = minOf(searchText.length, documentText.length - idx)
		while (matchLen < limit && searchText[matchLen] == documentText[idx + matchLen]) matchLen++
		if (matchLen > bestMatchLen) {
			bestMatchLen = matchLen
			bestOffset = idx
		}
		start = idx + 1
	}
	if (bestOffset < 0 || bestMatchLen >= searchText.length) {
		return "no occurrences of \"${searchText.take(80)}\""
	}
	val line = documentText.substring(0, bestOffset + bestMatchLen).count { it == '\n' } + 1
	val exp = searchText.substring(bestMatchLen, minOf(bestMatchLen + 20, searchText.length)).escapeWhitespace()
	val act = documentText.substring(bestOffset + bestMatchLen, minOf(bestOffset + bestMatchLen + 20, documentText.length)).escapeWhitespace()
	return "no exact match (best candidate at line $line, diverges at offset $bestMatchLen/${searchText.length}): expected \"$exp\" but found \"$act\""
}

private fun String.escapeWhitespace() = replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

class BatchReplaceTextToolset : McpToolset {

	@Serializable
	data class TextReplacement(
		val filePath: String,
		val oldText: String,
		val newText: String,
		val replaceAll: Boolean = true,
	)

	@McpTool
	@McpDescription(
		"""
        |Replaces text in multiple files or multiple text regions in one call.
        |Batch version of replace_text_in_file — saves round-trips when making several changes.
        |
        |Each replacement specifies: filePath, oldText, newText, and optional replaceAll.
        |Replacements are grouped by file and applied atomically per file using range markers,
        |so earlier replacements don't invalidate later ones even if they shift text.
        |
        |Returns a summary of replacements applied per file.
        |oldText is matched exactly — all whitespace and indentation are significant.
        |Fails if any replacement's oldText is not found in the target file.
        |On failure, the error pinpoints the closest candidate and the exact offset where it diverges.
    """
	)
	suspend fun batch_replace_text_in_file(
		@McpDescription("List of text replacements to apply")
		replacements: List<TextReplacement>,
	): Any {
		if (replacements.isEmpty()) mcpFail("replacements must not be empty")

		val project = currentCoroutineContext().project
		val byFile = replacements.groupBy { it.filePath }

		return buildString {
			for ((filePath, fileReplacements) in byFile) {
				val resolved = resolveFile(project, filePath)
				val document = resolved.document

				val marked = mutableListOf<Pair<RangeMarker, String>>()

				readAction {
					marked.clear()
					val text = document.text
					for (r in fileReplacements) {
						if (r.oldText.isEmpty()) mcpFail("$filePath: oldText must not be empty")

						var found = false
						var currentStart = 0
						while (true) {
							val idx = text.indexOf(r.oldText, currentStart)
							if (idx < 0) break
							found = true
							marked.add(document.createRangeMarker(idx, idx + r.oldText.length, true) to r.newText)
							if (!r.replaceAll) break
							currentStart = idx + r.oldText.length
						}

						if (!found) mcpFail("$filePath: ${findMismatchDiagnostic(text, r.oldText)}")
					}
				}

				marked.sortByDescending { it.first.startOffset }

				withContext(Dispatchers.EDT) {
					WriteCommandAction.runWriteCommandAction(project) {
						for ((marker, newText) in marked) {
							if (!marker.isValid) continue
							document.replaceString(marker.startOffset, marker.endOffset, newText)
							marker.dispose()
						}
					}
					FileDocumentManager.getInstance().saveDocument(document)
				}

				append(filePath).append(": ").append(marked.size).appendLine(" replacement(s) applied")
			}
		}.trimEnd()
	}
}
