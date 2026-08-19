package dev.xdark.ijmcp.tools.refactor

import com.intellij.mcpserver.mcpFail
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import dev.xdark.ijmcp.util.*
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

data class AddMemberResult(
	val added: Boolean,
	val className: String,
	val memberText: String,
	val message: String,
)

suspend fun findJavaClass(resolved: ResolvedFile, className: String): PsiClass {
	return readAction {
		val classOwner = resolved.psiFile as? PsiClassOwner
			?: mcpFail("File is not a class file")
		val classes = classOwner.classes
		if (classes.isEmpty()) mcpFail("No classes found in file")

		if (className.isNotEmpty()) {
			findJavaClassByName(classes, className)
				?: mcpFail("Class '$className' not found in file. Available: ${collectJavaClassNames(classes)}")
		} else {
			if (classes.size > 1) {
				mcpFail("File contains multiple classes: ${classes.mapNotNull { it.name }}. Specify className.")
			}
			classes[0]
		}
	}
}

suspend fun findKotlinClass(resolved: ResolvedFile, className: String): KtClassOrObject {
	return readAction {
		val ktFile = resolved.psiFile as? KtFile
			?: mcpFail("File is not a Kotlin file")
		val declarations = ktFile.declarations.filterIsInstance<KtClassOrObject>()
		if (declarations.isEmpty()) mcpFail("No classes found in file")

		if (className.isNotEmpty()) {
			findKotlinClassByName(ktFile.declarations, className)
				?: mcpFail("Class '$className' not found in file. Available: ${collectKotlinClassNames(ktFile.declarations)}")
		} else {
			if (declarations.size > 1) {
				mcpFail("File contains multiple classes: ${declarations.mapNotNull { it.name }}. Specify className.")
			}
			declarations[0]
		}
	}
}
