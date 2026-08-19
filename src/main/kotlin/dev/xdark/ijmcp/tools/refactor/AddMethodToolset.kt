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

class AddMethodToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Adds a method to a Java or Kotlin class via PSI.
        |
        |For Java, provide the full method source, e.g.:
        |  "public void greet(String name) { System.out.println(name); }"
        |You can use fully qualified names in the method text — they will be automatically shortened and imports added.
        |
        |For Kotlin, provide the full function declaration, e.g.:
        |  "fun greet(name: String) { println(name) }"
        |
        |Specify which class to add to using className (simple name of a class in the file).
        |If the file contains a single class, className can be omitted.
    """
	)
	suspend fun add_method(
		@McpDescription("Path relative to the project root") filePath: String,
		@McpDescription("The full method/function source text") methodText: String,
		@McpDescription("Simple name of the target class (optional if file has one class)") className: String = "",
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, filePath)

		val isKotlin = resolved.psiFile is KtFile

		return if (isKotlin) {
			addKotlinMethod(resolved, methodText, className).message
		} else {
			addJavaMethod(resolved, methodText, className).message
		}
	}

	private suspend fun addJavaMethod(
		resolved: ResolvedFile,
		methodText: String,
		className: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findJavaClass(resolved, className)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = PsiElementFactory.getInstance(project)
				val method = factory.createMethodFromText(methodText, targetClass)
				val added = targetClass.add(method)
				JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			className = resolvedClassName,
			memberText = methodText.lines().first().trim(),
			message = "Method added to $resolvedClassName"
		)
	}

	private suspend fun addKotlinMethod(
		resolved: ResolvedFile,
		methodText: String,
		className: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findKotlinClass(resolved, className)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = KtPsiFactory(project, markGenerated = true)
				val function = factory.createFunction(methodText)

				val body = targetClass.body
				if (body != null) {
					val rBrace = body.rBrace
					if (rBrace != null) {
						body.addBefore(factory.createNewLine(), rBrace)
						body.addBefore(function, rBrace)
					} else {
						body.add(function)
					}
				} else {
					val emptyBody = factory.createEmptyClassBody()
					val addedBody = targetClass.add(emptyBody) as KtClassBody
					val rBrace = addedBody.rBrace
					if (rBrace != null) {
						addedBody.addBefore(factory.createNewLine(), rBrace)
						addedBody.addBefore(function, rBrace)
					} else {
						addedBody.add(function)
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
			memberText = methodText.lines().first().trim(),
			message = "Function added to $resolvedClassName"
		)
	}
}
