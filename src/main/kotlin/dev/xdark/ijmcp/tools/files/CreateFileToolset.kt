@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.files

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.findOrCreateFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.io.path.name
import kotlin.io.path.pathString

class CreateFileToolset : McpToolset {

	@Serializable
	data class FileToCreate(
		val filePath: String,
		val content: String,
	)

	@McpTool
	@McpDescription(
		"""
        |Creates one or more files at the specified paths within the project directory and populates them with text.
        |Creates any necessary parent directories automatically.
    """
	)
	suspend fun create_file(
		@McpDescription("List of files to create, each with filePath and content")
		files: List<FileToCreate>,
		@McpDescription("Whether to overwrite existing files. If false, an error is returned if any file exists.")
		overwrite: Boolean = false,
	): Any {
		val project = currentCoroutineContext().project
		val results = mutableListOf<String>()
		for (file in files) {
			val path = project.resolveInProject(file.filePath)
			try {
				// FileDocumentManager requires EDT, while writeAction {} runs on a background
				// thread — saveDocument goes after the write command, as in the other toolsets.
				withContext(Dispatchers.EDT) {
					lateinit var document: Document
					WriteCommandAction.runWriteCommandAction(project) {
						val parent = VfsUtil.createDirectories(path.parent.pathString)
						val existing = parent.findChild(path.name)
						if (existing != null && !overwrite) {
							mcpFail("File already exists: ${file.filePath}. Specify overwrite=true to overwrite it.")
						}
						val createdFile = parent.findOrCreateFile(path.name)
						document = FileDocumentManager.getInstance().getDocument(createdFile)
							?: mcpFail("Cannot get document for: ${file.filePath}")
						document.setText(file.content)
					}
					FileDocumentManager.getInstance().saveDocument(document)
				}
				results.add("Created ${file.filePath}")
			} catch (e: IOException) {
				mcpFail("Cannot create file ${file.filePath}: ${e.message}")
			}
		}
		return results.joinToString("\n")
	}
}
