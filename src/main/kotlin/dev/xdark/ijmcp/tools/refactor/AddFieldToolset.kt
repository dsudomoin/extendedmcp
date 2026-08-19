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
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import dev.xdark.ijmcp.util.ResolvedFile
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

class AddFieldToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Adds a field (Java) or property (Kotlin) to a class via PSI.
        |
        |For Java, provide the full field declaration, e.g.:
        |  "private final String name;"
        |  "private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(\"MyClass\");"
        |Fully qualified names will be automatically shortened and imports added.
        |
        |For Kotlin, provide the full property declaration, e.g.:
        |  "val name: String = \"\""
        |  "private lateinit var service: MyService"
        |
        |Specify which class to add to using className (simple name of a class in the file).
        |If the file contains a single class, className can be omitted.
    """
	)
	suspend fun add_field(
		@McpDescription("Path relative to the project root") filePath: String,
		@McpDescription("The full field/property source text") fieldText: String,
		@McpDescription("Simple name of the target class (optional if file has one class)") className: String = "",
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, filePath)

		val isKotlin = resolved.psiFile is KtFile

		return if (isKotlin) {
			addKotlinProperty(resolved, fieldText, className).message
		} else {
			addJavaField(resolved, fieldText, className).message
		}
	}

	private suspend fun addJavaField(
		resolved: ResolvedFile,
		fieldText: String,
		className: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findJavaClass(resolved, className)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = PsiElementFactory.getInstance(project)
				val field = factory.createFieldFromText(fieldText, targetClass)
				val existingFields = targetClass.fields
				val added = if (existingFields.isNotEmpty()) {
					targetClass.addAfter(field, existingFields.last())
				} else {
					val lBrace = targetClass.lBrace
					if (lBrace != null) {
						targetClass.addAfter(field, lBrace)
					} else {
						targetClass.add(field)
					}
				}
				JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			className = resolvedClassName,
			memberText = fieldText.trim(),
			message = "Field added to $resolvedClassName"
		)
	}

	private suspend fun addKotlinProperty(
		resolved: ResolvedFile,
		fieldText: String,
		className: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findKotlinClass(resolved, className)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = KtPsiFactory(project, markGenerated = true)
				val property = factory.createProperty(fieldText)

				val body = targetClass.body
				if (body != null) {
					val existingProperties = body.properties
					if (existingProperties.isNotEmpty()) {
						body.addAfter(factory.createNewLine(), existingProperties.last())
						body.addAfter(property, existingProperties.last())
					} else {
						val lBrace = body.lBrace
						if (lBrace != null) {
							body.addAfter(factory.createNewLine(), lBrace)
							body.addAfter(property, lBrace)
						} else {
							body.add(property)
						}
					}
				} else {
					val emptyBody = factory.createEmptyClassBody()
					val addedBody = targetClass.add(emptyBody) as KtClassBody
					val lBrace = addedBody.lBrace
					if (lBrace != null) {
						addedBody.addAfter(factory.createNewLine(), lBrace)
						addedBody.addAfter(property, lBrace)
					} else {
						addedBody.add(property)
					}
				}
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			className = resolvedClassName,
			memberText = fieldText.trim(),
			message = "Property added to $resolvedClassName"
		)
	}
}
