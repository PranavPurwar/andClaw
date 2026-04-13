package com.coderred.andclaw.ui.screen.dashboard

import com.coderred.andclaw.data.GatewayState
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.proroot.BundleUpdateFailureState

internal object RuntimeRecoverableFailureDetector {
    const val FAILURE_TYPE = "RUNTIME_OPENCLAW_EXEC"

    fun detect(
        state: GatewayState,
        logs: List<String>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): BundleUpdateFailureState? {
        if (state.status != GatewayStatus.ERROR) return null

        val scopedLogs = logs
            .takeLast(200)
            .let(::logsSinceLastStartMarker)
            .takeLast(80)

        val hasOpenClawPermissionDenied = scopedLogs.any { line ->
            line.contains("openclaw", ignoreCase = true) &&
                line.contains("permission denied", ignoreCase = true)
        } || (
            state.errorMessage?.contains("openclaw", ignoreCase = true) == true &&
                state.errorMessage.contains("permission denied", ignoreCase = true)
            )

        val hasMissingOpenClawModule = scopedLogs.any { line ->
            (line.contains("ERR_MODULE_NOT_FOUND", ignoreCase = true) &&
                line.contains("/usr/local/lib/node_modules/openclaw", ignoreCase = true)) ||
                line.contains("Cannot find module '/usr/local/lib/node_modules/openclaw", ignoreCase = true)
        } || (
            state.errorMessage?.contains("ERR_MODULE_NOT_FOUND", ignoreCase = true) == true &&
                state.errorMessage.contains("/usr/local/lib/node_modules/openclaw", ignoreCase = true)
            ) || (
            state.errorMessage?.contains("Cannot find module '/usr/local/lib/node_modules/openclaw", ignoreCase = true) == true
            )

        val hasExit127 = state.errorMessage?.contains("exit: 127", ignoreCase = true) == true ||
            scopedLogs.any { line ->
                line.contains("exit code: 127", ignoreCase = true) ||
                    line.contains("exit: 127", ignoreCase = true)
            }
        val hasOpenClawSignal = scopedLogs.any { it.contains("openclaw", ignoreCase = true) } ||
            state.errorMessage?.contains("openclaw", ignoreCase = true) == true

        if (!hasOpenClawPermissionDenied && !(hasExit127 && hasOpenClawSignal) && !hasMissingOpenClawModule) return null

        val detailMessage = scopedLogs.lastOrNull { line ->
            line.contains("openclaw", ignoreCase = true) &&
                line.contains("permission denied", ignoreCase = true)
        } ?: scopedLogs.lastOrNull { line ->
            (line.contains("ERR_MODULE_NOT_FOUND", ignoreCase = true) &&
                line.contains("/usr/local/lib/node_modules/openclaw", ignoreCase = true)) ||
                line.contains("Cannot find module '/usr/local/lib/node_modules/openclaw", ignoreCase = true)
        } ?: state.errorMessage?.takeIf { it.isNotBlank() } ?: scopedLogs.lastOrNull()

        return BundleUpdateFailureState(
            failCountForCurrentVersion = 1,
            lastFailAtEpochMs = nowEpochMs,
            lastFailVersion = null,
            lastError = detailMessage,
            lastFailureType = FAILURE_TYPE,
            manualRetryUsed = false,
            inCooldown = false,
            cooldownRemainingMs = 0L,
        )
    }

    fun stabilizeWithPrevious(
        previous: BundleUpdateFailureState?,
        current: BundleUpdateFailureState?,
    ): BundleUpdateFailureState? {
        if (current == null) return null
        return if (previous?.lastFailureType == FAILURE_TYPE &&
            previous.lastError == current.lastError
        ) {
            previous
        } else {
            current
        }
    }

    private fun logsSinceLastStartMarker(logs: List<String>): List<String> {
        if (logs.isEmpty()) return logs
        val lastStartIndex = logs.indexOfLast { line ->
            line.contains("[andClaw] Starting gateway...", ignoreCase = true) ||
                line.contains("[andClaw] Gateway process started", ignoreCase = true)
        }
        if (lastStartIndex < 0) return logs
        return logs.subList(lastStartIndex, logs.size)
    }
}
