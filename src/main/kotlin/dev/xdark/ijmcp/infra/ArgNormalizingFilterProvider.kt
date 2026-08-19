package dev.xdark.ijmcp.infra

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterContext
import com.intellij.mcpserver.McpToolInvocationMode
import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ArgNormalizingFilterProvider : McpToolFilterProvider {
	override fun applyFilters(
		context: McpToolFilterContext,
		clientInfo: Implementation?,
		sessionOptions: McpServerService.McpSessionOptions?,
		invocationMode: McpToolInvocationMode,
	) {
		val disabled = ToolFilterState.getInstance().getDisabledSet()
		if (disabled.isEmpty()) return
		// enabled = false turns the tool off, routerOnly = null leaves that flag untouched.
		context.updateState(false, null) { it.descriptor.name in disabled }
	}

	override fun getUpdates(
		clientInfo: Implementation?,
		scope: CoroutineScope,
		sessionOptions: McpServerService.McpSessionOptions?,
		invocationMode: McpToolInvocationMode,
	): Flow<Unit> {
		return updateFlow.asSharedFlow()
	}

	companion object {
		private val updateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

		fun triggerUpdate() {
			updateFlow.tryEmit(Unit)
		}
	}
}
