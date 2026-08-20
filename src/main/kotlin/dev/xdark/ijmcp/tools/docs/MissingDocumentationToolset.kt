@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.docs

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil
import dev.xdark.ijmcp.util.ResolvedFile
import dev.xdark.ijmcp.util.resolveFilesByPatterns
import dev.xdark.ijmcp.util.resolvePsi
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

data class UndocumentedElement(
	val kind: String,
	val name: String,
	val line: Int,
	val signature: String,
	val className: String = "",
)

data class FileMissingDocs(
	val filePath: String,
	val undocumented: List<UndocumentedElement>,
	val total: Int,
	val documented: Int,
)

class MissingDocumentationToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Reports classes, methods, fields, and top-level declarations missing documentation.
        |
        |Returns undocumented elements with kind, name, line number, and signature.
        |By default only non-private elements are reported.
        |
        |filePaths accepts a list of file paths or glob patterns (e.g. "src/**/*.java").
        |Files with no undocumented elements are omitted from the results.
    """
	)
	suspend fun missing_documentation(
		@McpDescription("List of file paths or glob patterns (e.g. 'src/**/*.java')") filePaths: List<String>,
		@McpDescription("Include private members (default false)") includePrivate: Boolean = false,
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFilesByPatterns(project, filePaths, extensions = setOf("java", "kt"))

		if (resolved.files.isEmpty()) {
			return "No matching files found"
		}

		val psiFiles = resolved.resolvePsi(project)
		val allFileResults = mutableListOf<FileMissingDocs>()

		for (entry in psiFiles) {
			val resolvedFile = ResolvedFile(entry.psiFile.virtualFile, entry.psiFile, entry.document)
			val isKotlin = entry.psiFile is KtFile

			val fileResult = if (isKotlin) {
				findMissingKotlinDocs(resolvedFile, entry.relativePath, includePrivate)
			} else {
				findMissingJavaDocs(resolvedFile, entry.relativePath, includePrivate)
			}

			if (fileResult.undocumented.isNotEmpty()) {
				allFileResults.add(fileResult)
			}
		}

		val totalUndocumented = allFileResults.sumOf { it.undocumented.size }
		if (totalUndocumented == 0) {
			return "All elements are documented (${psiFiles.size} files checked)"
		}

		return buildString {
			for (fileResult in allFileResults) {
				append(fileResult.undocumented.size).append(" undocumented elements in ").append(fileResult.filePath)
					.append(" (").append(fileResult.total).append(" total, ").append(fileResult.documented)
					.appendLine(" documented):")
				appendLine()
				for (elem in fileResult.undocumented) {
					append("  ").append(elem.kind).append(' ')
					if (elem.className.isNotEmpty()) append(elem.className).append('.')
					append(elem.signature).append(" [line ").append(elem.line).appendLine("]")
				}
				appendLine()
			}
			append("Total: ").append(totalUndocumented).append(" undocumented across ").append(allFileResults.size)
				.append(" files (").append(psiFiles.size).append(" files checked)")
		}
	}

	private fun docLineOf(element: PsiElement, document: Document): Int {
		val offset = element.navigationElement.textOffset
		if (offset < 0 || offset >= document.textLength) return 0
		return document.getLineNumber(offset) + 1
	}

	private suspend fun findMissingJavaDocs(
		resolved: ResolvedFile,
		filePath: String,
		includePrivate: Boolean,
	): FileMissingDocs {
		return readAction {
			val document = resolved.document
			val classOwner = resolved.psiFile as? PsiClassOwner
				?: mcpFail("File is not a Java class file")
			val classes = classOwner.classes
			if (classes.isEmpty()) mcpFail("No classes found in file")

			val undocumented = mutableListOf<UndocumentedElement>()
			var total = 0
			var documented = 0

			fun processClass(cls: PsiClass, parentName: String) {
				if (!includePrivate && cls.hasModifierProperty(PsiModifier.PRIVATE)) return
				if (cls.name?.startsWith("$") == true) return

				total++
				if (cls.docComment != null) {
					documented++
				} else {
					val kind = when {
						cls.isInterface -> "interface"
						cls.isEnum -> "enum"
						cls.isAnnotationType -> "annotation"
						else -> "class"
					}
					undocumented.add(
						UndocumentedElement(
							kind = kind,
							name = cls.name ?: "<anonymous>",
							line = docLineOf(cls, document),
							signature = cls.qualifiedName ?: cls.name ?: "",
							className = parentName,
						)
					)
				}

				for (field in cls.fields) {
					if (!includePrivate && field.hasModifierProperty(PsiModifier.PRIVATE)) continue
					if (field.name.startsWith("$") || field.name == "INSTANCE") continue

					total++
					if (field.docComment != null) {
						documented++
					} else {
						val typeName = try {
							field.type.presentableText
						} catch (_: Exception) {
							"?"
						}
						undocumented.add(
							UndocumentedElement(
								kind = "field",
								name = field.name,
								line = docLineOf(field, document),
								signature = "$typeName ${field.name}",
								className = cls.name ?: "",
							)
						)
					}
				}

				for (method in cls.methods) {
					if (!includePrivate && method.hasModifierProperty(PsiModifier.PRIVATE)) continue
					if (method.name.startsWith("$")) continue

					total++
					if (method.docComment != null) {
						documented++
					} else {
						val params = method.parameterList.parameters.joinToString(", ") { p ->
							val typeName = try {
								p.type.presentableText
							} catch (_: Exception) {
								"?"
							}
							"$typeName ${p.name}"
						}
						val returnType = try {
							method.returnType?.presentableText ?: "void"
						} catch (_: Exception) {
							"?"
						}
						undocumented.add(
							UndocumentedElement(
								kind = if (method.isConstructor) "constructor" else "method",
								name = method.name,
								line = docLineOf(method, document),
								signature = "$returnType ${method.name}($params)",
								className = cls.name ?: "",
							)
						)
					}
				}

				for (inner in cls.innerClasses) {
					processClass(inner, cls.name ?: "")
				}
			}

			for (cls in classes) {
				processClass(cls, "")
			}

			FileMissingDocs(
				filePath = filePath,
				undocumented = undocumented,
				total = total,
				documented = documented,
			)
		}
	}

	private suspend fun findMissingKotlinDocs(
		resolved: ResolvedFile,
		filePath: String,
		includePrivate: Boolean,
	): FileMissingDocs {
		return readAction {
			val document = resolved.document
			val ktFile = resolved.psiFile as? KtFile
				?: mcpFail("File is not a Kotlin file")

			val undocumented = mutableListOf<UndocumentedElement>()
			var total = 0
			var documented = 0

			fun hasDoc(element: PsiElement): Boolean =
				PsiTreeUtil.getChildOfType(element, KDoc::class.java) != null

			fun isPrivate(decl: KtNamedDeclaration): Boolean =
				decl.hasModifier(KtTokens.PRIVATE_KEYWORD)

			fun processDeclarations(
				declarations: List<KtDeclaration>,
				parentClassName: String
			) {
				for (decl in declarations) {
					when (decl) {
						is KtEnumEntry -> continue
						is KtClassOrObject -> {
							if ((decl as? KtObjectDeclaration)?.isCompanion() == true) continue
							if (!includePrivate && isPrivate(decl)) continue

							total++
							if (hasDoc(decl)) {
								documented++
							} else {
								val kind = when {
									decl is KtObjectDeclaration -> "object"
									(decl as? KtClass)?.isInterface() == true -> "interface"
									(decl as? KtClass)?.isEnum() == true -> "enum"
									else -> "class"
								}
								undocumented.add(
									UndocumentedElement(
										kind = kind,
										name = decl.name ?: "<anonymous>",
										line = docLineOf(decl, document),
										signature = "$kind ${decl.name}",
										className = parentClassName,
									)
								)
							}

							val body = decl.body ?: continue
							processDeclarations(body.declarations, decl.name ?: "")
						}

						is KtNamedFunction -> {
							if (!includePrivate && isPrivate(decl)) continue

							total++
							if (hasDoc(decl)) {
								documented++
							} else {
								val params = decl.valueParameters.joinToString(", ") { p ->
									"${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
								}
								val ret = decl.typeReference?.text?.let { ": $it" } ?: ""
								undocumented.add(
									UndocumentedElement(
										kind = "function",
										name = decl.name ?: "<anonymous>",
										line = docLineOf(decl, document),
										signature = "fun ${decl.name}($params)$ret",
										className = parentClassName,
									)
								)
							}
						}

						is KtProperty -> {
							if (!includePrivate && isPrivate(decl)) continue

							total++
							if (hasDoc(decl)) {
								documented++
							} else {
								val keyword = if (decl.isVar) "var" else "val"
								val type = decl.typeReference?.text?.let { ": $it" } ?: ""
								undocumented.add(
									UndocumentedElement(
										kind = "property",
										name = decl.name ?: "<anonymous>",
										line = docLineOf(decl, document),
										signature = "$keyword ${decl.name}$type",
										className = parentClassName,
									)
								)
							}
						}
					}
				}
			}

			processDeclarations(ktFile.declarations, "")

			FileMissingDocs(
				filePath = filePath,
				undocumented = undocumented,
				total = total,
				documented = documented,
			)
		}
	}
}
