@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.util.PsiTreeUtil
import dev.xdark.ijmcp.util.ResolvedFile
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

class DocumentationToolset : McpToolset {

    @Serializable
    data class AddDocResult(
        val success: Boolean,
        val targetName: String,
        val replaced: Boolean,
        val message: String,
    )

    @McpTool
    @McpDescription(
        """
        |Adds or replaces documentation on a class, method, or field.
        |
        |Provide the documentation content as plain markdown text (no comment delimiters).
        |The tool will format it as /// markdown doc comments for Java, or /** */ KDoc for Kotlin.
        |
        |Example documentation parameter:
        |  "Returns a greeting for the given user.\n\n@param name the user's name\n@return the greeting string"
        |
        |To document the class itself, omit memberName.
        |To document a method or field, specify memberName.
        |If the target already has a doc comment, it will be replaced.
        |
        |If there are overloaded methods with the same name, the tool will return an error
        |listing each overload with its index and parameter types. Re-call with memberIndex
        |to pick the correct overload.
    """
    )
    suspend fun add_documentation(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Documentation content as plain markdown (no /// or /** */ delimiters)") documentation: String,
        @McpDescription("Simple name of the target class (optional if file has one class)") className: String = "",
        @McpDescription("Name of the method or field to document (omit to document the class itself)") memberName: String = "",
        @McpDescription("Index of the overloaded method to document (from the overload list error)") memberIndex: Int = -1,
    ): AddDocResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val isKotlin = readAction { resolved.psiFile is KtFile }

        return if (isKotlin) {
            addKotlinDoc(resolved, documentation, className, memberName, memberIndex)
        } else {
            addJavaDoc(resolved, documentation, className, memberName, memberIndex)
        }
    }

    private fun findJavaClassByName(classes: Array<PsiClass>, name: String): PsiClass? {
        for (cls in classes) {
            if (cls.name == name) return cls
            val inner = findJavaClassByName(cls.innerClasses, name)
            if (inner != null) return inner
        }
        return null
    }

    private fun collectJavaClassNames(classes: Array<PsiClass>): List<String> {
        val names = mutableListOf<String>()
        for (cls in classes) {
            cls.name?.let { names.add(it) }
            names.addAll(collectJavaClassNames(cls.innerClasses))
        }
        return names
    }

    private fun findKotlinClassByName(declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>, name: String): KtClassOrObject? {
        for (decl in declarations) {
            if (decl is KtClassOrObject) {
                if (decl.name == name) return decl
                val body = decl.body ?: continue
                val inner = findKotlinClassByName(body.declarations, name)
                if (inner != null) return inner
            }
        }
        return null
    }

    private fun collectKotlinClassNames(declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>): List<String> {
        val names = mutableListOf<String>()
        for (decl in declarations) {
            if (decl is KtClassOrObject) {
                decl.name?.let { names.add(it) }
                val body = decl.body ?: continue
                names.addAll(collectKotlinClassNames(body.declarations))
            }
        }
        return names
    }

    private fun resolveKotlinMember(
        declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
        memberName: String,
        memberIndex: Int,
        context: String,
    ): PsiElement {
        val functions = declarations.filter { it is KtNamedFunction && it.name == memberName }
        val properties = declarations.filter { it is KtProperty && it.name == memberName }

        if (functions.size > 1 && memberIndex < 0) {
            val overloads = functions.mapIndexed { i, f ->
                val params = (f as KtNamedFunction).valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }
                "  $i: $memberName($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$memberName' $context. Specify memberIndex:\n$overloads")
        }

        if (functions.isNotEmpty()) {
            return functions[if (memberIndex >= 0) memberIndex.coerceIn(functions.indices) else 0]
        }
        if (properties.isNotEmpty()) {
            return properties[0]
        }
        mcpFail("'$memberName' not found $context")
    }

    private fun formatAsMarkdownDoc(documentation: String): String {
        return documentation.lines().joinToString("\n") { line ->
            if (line.isBlank()) "///" else "/// $line"
        }
    }

    private fun formatAsKDoc(documentation: String): String {
        val lines = documentation.lines().joinToString("\n") { line ->
            if (line.isBlank()) " *" else " * $line"
        }
        return "/**\n$lines\n */"
    }

    private suspend fun addJavaDoc(
        resolved: ResolvedFile,
        documentation: String,
        className: String,
        memberName: String,
        memberIndex: Int,
    ): AddDocResult {
        val project = resolved.psiFile.project

        val targetInfo = readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a Java class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            val psiClass = if (className.isNotEmpty()) {
                findJavaClassByName(classes, className)
                    ?: mcpFail("Class '$className' not found. Available: ${collectJavaClassNames(classes)}")
            } else {
                if (classes.size > 1 && memberName.isEmpty()) {
                    mcpFail("File has multiple classes: ${collectJavaClassNames(classes)}. Specify className.")
                }
                classes[0]
            }

            if (memberName.isEmpty()) {
                Triple(psiClass as PsiElement, psiClass.name ?: "<anonymous>", psiClass.docComment != null)
            } else {
                val methods = psiClass.findMethodsByName(memberName, false)
                val field = psiClass.findFieldByName(memberName, false)

                if (methods.size > 1 && memberIndex < 0) {
                    val overloads = methods.mapIndexed { i, m ->
                        val params = m.parameterList.parameters.joinToString(", ") { p ->
                            "${p.type.presentableText} ${p.name}"
                        }
                        "  $i: $memberName($params)"
                    }.joinToString("\n")
                    mcpFail("Multiple overloads found for '$memberName'. Specify memberIndex:\n$overloads")
                }

                if (methods.isNotEmpty()) {
                    val method = methods[if (memberIndex >= 0) memberIndex.coerceIn(methods.indices) else 0]
                    Triple(method as PsiElement, method.name, method.docComment != null)
                } else if (field != null) {
                    Triple(field as PsiElement, field.name ?: memberName, field.docComment != null)
                } else {
                    mcpFail("Member '$memberName' not found in class '${psiClass.name}'")
                }
            }
        }

        val (target, targetName, hadExistingDoc) = targetInfo

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = PsiElementFactory.getInstance(project)
                val docText = formatAsMarkdownDoc(documentation)
                val tempMethod = factory.createMethodFromText("$docText\nvoid _temp_() {}", target)
                val newDoc = (tempMethod as? PsiDocCommentOwner)?.docComment
                    ?: mcpFail("Failed to create markdown doc comment")

                val existingDoc = (target as? PsiDocCommentOwner)?.docComment
                if (existingDoc != null) {
                    existingDoc.replace(newDoc)
                } else {
                    target.addBefore(newDoc, target.firstChild)
                }
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return AddDocResult(
            success = true,
            targetName = targetName,
            replaced = hadExistingDoc,
            message = if (hadExistingDoc) "Replaced documentation on $targetName" else "Added documentation to $targetName"
        )
    }

    private suspend fun addKotlinDoc(
        resolved: ResolvedFile,
        documentation: String,
        className: String,
        memberName: String,
        memberIndex: Int,
    ): AddDocResult {
        val project = resolved.psiFile.project

        val targetInfo = readAction {
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")

            if (className.isNotEmpty()) {
                // Find specific class (including nested), optionally find member in it
                val ktClass = findKotlinClassByName(ktFile.declarations, className)
                    ?: mcpFail("Class '$className' not found. Available: ${collectKotlinClassNames(ktFile.declarations)}")

                if (memberName.isEmpty()) {
                    val existing = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)
                    Triple(ktClass as PsiElement, ktClass.name ?: "<anonymous>", existing != null)
                } else {
                    val body = ktClass.body
                        ?: mcpFail("Class '${ktClass.name}' has no body")
                    val member = resolveKotlinMember(body.declarations, memberName, memberIndex, "in class '${ktClass.name}'")
                    val existing = PsiTreeUtil.getChildOfType(member, KDoc::class.java)
                    Triple(member, (member as? KtNamedDeclaration)?.name ?: memberName, existing != null)
                }
            } else if (memberName.isNotEmpty()) {
                // Search top-level declarations first, then fall back to single-class member
                val topLevelDecls = ktFile.declarations.filter {
                    (it is KtNamedFunction && it.name == memberName) ||
                            (it is KtProperty && it.name == memberName)
                }

                if (topLevelDecls.isNotEmpty()) {
                    val member = resolveKotlinMember(ktFile.declarations, memberName, memberIndex, "at top level")
                    val existing = PsiTreeUtil.getChildOfType(member, KDoc::class.java)
                    Triple(member, (member as? KtNamedDeclaration)?.name ?: memberName, existing != null)
                } else {
                    val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                    if (classes.size == 1) {
                        val ktClass = classes[0]
                        val body = ktClass.body
                            ?: mcpFail("Class '${ktClass.name}' has no body")
                        val member = resolveKotlinMember(body.declarations, memberName, memberIndex, "as top-level or in class '${ktClass.name}'")
                        val existing = PsiTreeUtil.getChildOfType(member, KDoc::class.java)
                        Triple(member, (member as? KtNamedDeclaration)?.name ?: memberName, existing != null)
                    } else {
                        mcpFail("'$memberName' not found as a top-level declaration")
                    }
                }
            } else {
                // No className, no memberName → document the first/only class
                val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                if (classes.isEmpty()) mcpFail("No classes found and no memberName specified")
                if (classes.size > 1) {
                    mcpFail("File has multiple classes: ${classes.mapNotNull { it.name }}. Specify className.")
                }
                val ktClass = classes[0]
                val existing = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)
                Triple(ktClass as PsiElement, ktClass.name ?: "<anonymous>", existing != null)
            }
        }

        val (target, targetName, hadExistingDoc) = targetInfo

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = KtPsiFactory(project, markGenerated = true)
                val kdocText = formatAsKDoc(documentation)
                val tempFunc = factory.createFunction("$kdocText\nfun _temp_() { }")
                val newDoc = PsiTreeUtil.getChildOfType(tempFunc, KDoc::class.java)
                    ?: mcpFail("Failed to parse documentation as KDoc")

                val existingDoc = PsiTreeUtil.getChildOfType(target, KDoc::class.java)
                if (existingDoc != null) {
                    existingDoc.replace(newDoc)
                } else {
                    target.addBefore(newDoc, target.firstChild)
                }
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return AddDocResult(
            success = true,
            targetName = targetName,
            replaced = hadExistingDoc,
            message = if (hadExistingDoc) "Replaced documentation on $targetName" else "Added documentation to $targetName"
        )
    }
}
