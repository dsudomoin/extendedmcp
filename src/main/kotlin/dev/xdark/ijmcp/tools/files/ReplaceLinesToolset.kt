@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.files

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class ReplaceLinesToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Replaces a range of lines in a file with new text.
        |More token-efficient than replace_text_in_file — only send line numbers and the new content,
        |no need to repeat the old text.
        |
        |startLine and endLine are 1-based and inclusive.
        |The text content of those lines is replaced with newText.
        |If newText is empty, the lines are deleted.
        |Returns a few lines of context around the replacement for verification.
        |
        |IMPORTANT — boundary alignment:
        |  Your newText must cover EXACTLY the content of lines startLine..endLine.
        |  - First line of newText replaces startLine
        |  - Last line of newText replaces endLine
        |  Do NOT include content from the line BEFORE startLine or AFTER endLine.
        |  Verify: compare the first/last line of your newText against startLine/endLine in the file.
        |  Common mistake: including the closing brace/paren from endLine+1 in newText.
    """
	)
	suspend fun replace_lines(
		@McpDescription("Path relative to the project root") filePath: String,
		@McpDescription("1-based first line to replace (inclusive)") startLine: Int,
		@McpDescription("1-based last line to replace (inclusive)") endLine: Int,
		@McpDescription("Replacement text (empty to delete lines)") newText: String = "",
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, filePath)
		val document = resolved.document
		val lineCount = document.lineCount

		if (startLine <= 0) mcpFail("startLine must be > 0")
		if (endLine <= 0) mcpFail("endLine must be > 0")
		if (startLine > endLine) mcpFail("startLine must be <= endLine")
		if (startLine > lineCount) mcpFail("startLine exceeds file length ($lineCount lines)")
		if (endLine > lineCount) mcpFail("endLine exceeds file length ($lineCount lines)")

		val startOffset = document.getLineStartOffset(startLine - 1)
		val endOffset = document.getLineEndOffset(endLine - 1)

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				document.replaceString(startOffset, endOffset, newText)
			}
			FileDocumentManager.getInstance().saveDocument(document)
		}

		val newLineCount = document.lineCount
		val newTextLines = if (newText.isEmpty()) 0 else newText.count { it == '\n' } + 1
		val contextStart = max(1, startLine - 2)
		val contextEnd = min(newLineCount, startLine + newTextLines + 1)

		return buildString {
			append("[replaced lines ").append(startLine).append('-').append(endLine).appendLine("]")
			val chars = document.immutableCharSequence
			for (lineNumber in contextStart..contextEnd) {
				val lineIndex = lineNumber - 1
				append('L').append(lineNumber).append(": ")
				append(chars, document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
				append('\n')
			}
		}.trimEnd()
	}
}
