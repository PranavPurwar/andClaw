@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.data

import android.content.Context
import android.os.Build
import com.coderred.andclaw.BuildConfig
import java.io.File
import java.util.Locale

data class BugReportBundle(
    val generatedAtEpochMs: Long,
    val metadata: BugReportMetadata,
    val gatewayErrorMessage: String? = null,
    val processErrorMessage: String? = null,
    val sessionErrors: List<BugReportSessionErrorEntry> = emptyList(),
    val gatewayLogs: List<String> = emptyList(),
    val attachments: List<BugReportTextAttachment> = emptyList(),
)

data class BugReportTextAttachment(
    val entryName: String,
    val content: String,
)

data class BugReportMetadata(
    val packageName: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidSdkInt: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val locale: String,
)

data class BugReportSessionErrorEntry(
    val timestamp: String,
    val role: String,
    val model: String?,
    val stopReason: String?,
    val errorMessage: String?,
    val tokenUsage: Int,
)

object BugReportBundleBuilder {
    fun collectSupplementalRuntimeAttachments(rootfsDir: File): List<BugReportTextAttachment> {
        return SUPPLEMENTAL_RUNTIME_LOG_FILES.mapNotNull { spec ->
            val file = resolveSupplementalRuntimeFile(rootfsDir, spec) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null

            BugReportTextAttachment(
                entryName = spec.zipEntryName,
                content = file.readText(),
            )
        }
    }

    fun collectSupplementalRuntimeLogLines(rootfsDir: File): List<String> {
        val lines = mutableListOf<String>()

        SUPPLEMENTAL_RUNTIME_LOG_FILES.forEach { spec ->
            val file = resolveSupplementalRuntimeFile(rootfsDir, spec) ?: return@forEach
            if (!file.isFile) return@forEach

            lines += "[andClaw][RuntimeFile] ${spec.displayPath}"
            file.useLines { sequence ->
                sequence
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .take(MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE)
                    .forEach(lines::add)
            }
        }

        return lines
    }

    fun sanitizeGatewayLogLines(gatewayLogLines: List<String>): List<String> {
        val sanitizedLines = gatewayLogLines
            .asSequence()
            .map { it.sanitizeGatewayLogLine() }
            .filter { it.isNotBlank() }
            .toList()

        val cappedLines = sanitizedLines.take(MAX_GATEWAY_LOG_LINES)
        val preservedProrootLines = sanitizedLines
            .drop(MAX_GATEWAY_LOG_LINES)
            .filter { it.contains(PROROOT_PRESERVE_KEYWORD, ignoreCase = true) }

        return if (preservedProrootLines.isEmpty()) {
            cappedLines
        } else {
            cappedLines + preservedProrootLines
        }
    }

    fun build(
        sessionEntries: List<SessionLogEntry>,
        gatewayLogLines: List<String>,
        metadata: BugReportMetadata,
        gatewayErrorMessage: String? = null,
        processErrorMessage: String? = null,
        attachments: List<BugReportTextAttachment> = emptyList(),
        generatedAtEpochMs: Long = System.currentTimeMillis(),
    ): BugReportBundle {
        return BugReportBundle(
            generatedAtEpochMs = generatedAtEpochMs,
            metadata = metadata,
            gatewayErrorMessage = gatewayErrorMessage.normalizeError(),
            processErrorMessage = processErrorMessage.normalizeError(),
            sessionErrors = sessionEntries
                .asSequence()
                .filter { it.isSessionError() }
                .map { entry ->
                    BugReportSessionErrorEntry(
                        timestamp = entry.timestamp,
                        role = entry.role,
                        model = entry.model,
                        stopReason = entry.stopReason,
                        errorMessage = entry.errorMessage,
                        tokenUsage = entry.tokenUsage,
                    )
                }
                .toList(),
            gatewayLogs = sanitizeGatewayLogLines(gatewayLogLines),
            attachments = attachments,
        )
    }

    fun collectMetadata(context: Context): BugReportMetadata {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return BugReportMetadata(
            packageName = context.packageName,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = versionCode,
            androidSdkInt = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL.orEmpty(),
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            locale = Locale.getDefault().toLanguageTag(),
        )
    }
}

fun SessionLogEntry.isSessionError(): Boolean {
    return this.stopReason == "error" || this.errorMessage != null
}

private fun String?.normalizeError(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

private fun String.sanitizeGatewayLogLine(): String {
    var sanitized = this
    sanitized = JSON_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>${match.groupValues[3]}"
    }
    sanitized = AUTH_HEADER_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = KEY_VALUE_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
    }
    sanitized = BEARER_REGEX.replace(sanitized, "Bearer <redacted>")
    sanitized = QUERY_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = RAW_KEY_REGEX.replace(sanitized, "<redacted>")
    return sanitized.take(MAX_GATEWAY_LOG_LINE_LENGTH)
}

private const val MAX_GATEWAY_LOG_LINES = 400
private const val MAX_GATEWAY_LOG_LINE_LENGTH = 500
private const val MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE = 200
private const val PROROOT_PRESERVE_KEYWORD = "proroot"

private enum class SupplementalRuntimeBase { ROOTFS, ROOTFS_PARENT }

private data class SupplementalRuntimeLogSpec(
    val base: SupplementalRuntimeBase,
    val sourceRelativePath: String,
    val zipEntryName: String,
) {
    val displayPath: String
        get() = when (base) {
            SupplementalRuntimeBase.ROOTFS -> "/$sourceRelativePath"
            SupplementalRuntimeBase.ROOTFS_PARENT -> "../$sourceRelativePath"
        }
}

private fun resolveSupplementalRuntimeFile(
    rootfsDir: File,
    spec: SupplementalRuntimeLogSpec,
): File? {
    val baseDir = when (spec.base) {
        SupplementalRuntimeBase.ROOTFS -> rootfsDir
        SupplementalRuntimeBase.ROOTFS_PARENT -> rootfsDir.parentFile ?: return null
    }
    return File(baseDir, spec.sourceRelativePath)
}

private val SUPPLEMENTAL_RUNTIME_LOG_FILES = listOf(
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "tmp/proroot-sigsys-last.txt",
        zipEntryName = "runtime/proroot-sigsys-last.txt",
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS_PARENT,
        sourceRelativePath = "proroot-sigbus-maps.txt",
        zipEntryName = "runtime/proroot-sigbus-maps.txt",
    ),
)
private const val SECRET_KEY_PATTERN =
    "TELEGRAM_BOT_TOKEN|DISCORD_BOT_TOKEN|OPENROUTER_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|GOOGLE_API_KEY|GEMINI_API_KEY|COPILOT_GITHUB_TOKEN|GH_TOKEN|GITHUB_TOKEN|ZAI_API_KEY|Z_AI_API_KEY|KIMI_API_KEY|KIMICODE_API_KEY|MINIMAX_API_KEY|BRAVE_API_KEY|BRAVE_SEARCH_API_KEY|API_KEY|API-KEY|AUTHORIZATION|PASSWORD|SECRET|TOKEN|ACCESS_TOKEN|REFRESH_TOKEN|ID_TOKEN|X_API_KEY|X-API-KEY"
private val JSON_SECRET_REGEX = Regex(
    "(?i)(\"(?:$SECRET_KEY_PATTERN)\"\\s*:\\s*\")([^\"]+)(\")"
)
private val AUTH_HEADER_REGEX = Regex(
    "(?i)(\\bauthorization\\b\\s*[:=]\\s*)[^,;\\r\\n]+"
)
private val KEY_VALUE_SECRET_REGEX = Regex(
    "(?i)\\b($SECRET_KEY_PATTERN)\\b\\s*([=:])\\s*[^\\s,;]+"
)
private val BEARER_REGEX = Regex("(?i)bearer\\s+[a-z0-9._\\-]+")
private val QUERY_SECRET_REGEX = Regex("(?i)([?&](?:token|api_key|key)=)[^&\\s]+")
private val RAW_KEY_REGEX = Regex("\\b(?:sk|or|rk)-[A-Za-z0-9_\\-]{8,}\\b")
