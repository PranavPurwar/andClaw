@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BugReportZipArtifact(
    val file: File,
    val uri: Uri,
)

object BugReportZipWriter {
    private const val SHARED_DIR_NAME = "shared"
    private const val ZIP_ENTRY_NAME = "bug_report.json"
    private const val FILE_PREFIX = "andclaw-bug-report-"

    fun write(context: Context, bundle: BugReportBundle): BugReportZipArtifact {
        val sharedDir = ensureSharedDir(context)
        val timestamp = formatTimestamp(bundle.generatedAtEpochMs)
        val finalFile = File(sharedDir, "$FILE_PREFIX$timestamp.zip")
        val tempFile = File.createTempFile("$FILE_PREFIX$timestamp-", ".tmp", sharedDir)

        try {
            writeZip(tempFile, bundle)
            val writtenFile = moveAtomicallyBestEffort(tempFile, finalFile)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                writtenFile,
            )
            return BugReportZipArtifact(file = writtenFile, uri = uri)
        } finally {
            if (tempFile.exists() && tempFile != finalFile) {
                tempFile.delete()
            }
        }
    }

    private fun ensureSharedDir(context: Context): File {
        val sharedDir = File(context.cacheDir, SHARED_DIR_NAME)
        if (!sharedDir.exists() && !sharedDir.mkdirs()) {
            throw IOException("Failed to create shared cache dir: ${sharedDir.absolutePath}")
        }
        if (!sharedDir.isDirectory) {
            throw IOException("Shared cache path is not a directory: ${sharedDir.absolutePath}")
        }
        return sharedDir
    }

    private fun writeZip(targetFile: File, bundle: BugReportBundle) {
        val payload = buildDeterministicJson(bundle).toByteArray(StandardCharsets.UTF_8)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(targetFile))).use { zipOutput ->
            val entry = ZipEntry(ZIP_ENTRY_NAME).apply {
                time = bundle.generatedAtEpochMs
            }
            zipOutput.putNextEntry(entry)
            zipOutput.write(payload)
            zipOutput.closeEntry()

            bundle.attachments
                .sortedBy { it.entryName }
                .forEach { attachment ->
                    val attachmentEntry = ZipEntry(attachment.entryName).apply {
                        time = bundle.generatedAtEpochMs
                    }
                    zipOutput.putNextEntry(attachmentEntry)
                    zipOutput.write(attachment.content.toByteArray(StandardCharsets.UTF_8))
                    zipOutput.closeEntry()
                }
        }
    }

    private fun moveAtomicallyBestEffort(tempFile: File, finalFile: File): File {
        if (finalFile.exists() && !finalFile.delete()) {
            throw IOException("Failed to delete existing report file: ${finalFile.absolutePath}")
        }

        if (tempFile.renameTo(finalFile)) {
            return finalFile
        }

        tempFile.copyTo(finalFile, overwrite = true)
        if (!tempFile.delete()) {
            tempFile.deleteOnExit()
        }
        return finalFile
    }

    private fun formatTimestamp(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        formatter.timeZone = TimeZone.getDefault()
        return formatter.format(Date(epochMs))
    }

    private fun buildDeterministicJson(bundle: BugReportBundle): String {
        val out = StringBuilder(1024)
        out.append('{')
        out.append("\"generatedAtEpochMs\":").append(bundle.generatedAtEpochMs).append(',')
        out.append("\"metadata\":")
        appendMetadataJson(out, bundle.metadata)
        out.append(',')
        out.append("\"gatewayErrorMessage\":")
        appendNullableString(out, bundle.gatewayErrorMessage)
        out.append(',')
        out.append("\"processErrorMessage\":")
        appendNullableString(out, bundle.processErrorMessage)
        out.append(',')
        out.append("\"sessionErrors\":[")

        bundle.sessionErrors.forEachIndexed { index, entry ->
            if (index > 0) {
                out.append(',')
            }
            out.append('{')
            out.append("\"timestamp\":")
            appendJsonString(out, entry.timestamp)
            out.append(',')
            out.append("\"role\":")
            appendJsonString(out, entry.role)
            out.append(',')
            out.append("\"model\":")
            appendNullableString(out, entry.model)
            out.append(',')
            out.append("\"stopReason\":")
            appendNullableString(out, entry.stopReason)
            out.append(',')
            out.append("\"errorMessage\":")
            appendNullableString(out, entry.errorMessage)
            out.append(',')
            out.append("\"tokenUsage\":").append(entry.tokenUsage)
            out.append('}')
        }

        out.append("],")
        out.append("\"gatewayLogs\":[")
        bundle.gatewayLogs.forEachIndexed { index, line ->
            if (index > 0) {
                out.append(',')
            }
            appendJsonString(out, line)
        }
        out.append(']')
        out.append('}')
        return out.toString()
    }

    private fun appendMetadataJson(out: StringBuilder, metadata: BugReportMetadata) {
        out.append('{')
        out.append("\"packageName\":")
        appendJsonString(out, metadata.packageName)
        out.append(',')
        out.append("\"appVersionName\":")
        appendJsonString(out, metadata.appVersionName)
        out.append(',')
        out.append("\"appVersionCode\":").append(metadata.appVersionCode).append(',')
        out.append("\"androidSdkInt\":").append(metadata.androidSdkInt).append(',')
        out.append("\"deviceModel\":")
        appendJsonString(out, metadata.deviceModel)
        out.append(',')
        out.append("\"deviceManufacturer\":")
        appendJsonString(out, metadata.deviceManufacturer)
        out.append(',')
        out.append("\"locale\":")
        appendJsonString(out, metadata.locale)
        out.append('}')
    }

    private fun appendNullableString(out: StringBuilder, value: String?) {
        if (value == null) {
            out.append("null")
        } else {
            appendJsonString(out, value)
        }
    }

    private fun appendJsonString(out: StringBuilder, value: String) {
        out.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u")
                        out.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
    }
}
