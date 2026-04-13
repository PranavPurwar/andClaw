package com.coderred.andclaw.proroot.installer

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.coderred.andclaw.proroot.ArchiveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TarInstaller(
    private val context: Context,
    private val executableManifest: ExecutableManifest,
) : AssetInstaller<TarInstallSpec> {
    override suspend fun install(
        spec: TarInstallSpec,
        onProgress: (entries: Int) -> Unit,
    ) {
        installInternal(
            spec = spec,
            onProgress = onProgress,
            onCopyProgress = { _, _ -> },
        )
    }

    suspend fun install(
        spec: TarInstallSpec,
        onProgress: (entries: Int) -> Unit,
        onCopyProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        installInternal(
            spec = spec,
            onProgress = onProgress,
            onCopyProgress = onCopyProgress,
        )
    }

    private suspend fun installInternal(
        spec: TarInstallSpec,
        onProgress: (entries: Int) -> Unit,
        onCopyProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext<Unit>(Dispatchers.IO) {
        val cacheFile = File(spec.cacheDir, spec.assetName)
        val totalBytes = readAssetLength(spec.assetName)
        copyAssetToFile(spec.assetName, cacheFile) { copied ->
            onCopyProgress(copied, totalBytes)
        }
        onCopyProgress(totalBytes.takeIf { it > 0 } ?: cacheFile.length(), totalBytes)

        try {
            ArchiveUtils.extractTarGz(
                tarGzFile = cacheFile,
                destDir = spec.destinationDir,
                stripComponents = spec.stripComponents,
                onProgress = onProgress,
            )
            executableManifest.apply(spec.assetName, spec.permissionRootDir)
        } finally {
            cacheFile.delete()
        }
        Unit
    }

    private fun copyAssetToFile(
        assetName: String,
        destFile: File,
        onChunkCopied: (copiedBytes: Long) -> Unit,
    ): Long {
        destFile.parentFile?.mkdirs()
        var total = 0L
        context.assets.open(assetName).buffered(65536).use { input ->
            destFile.outputStream().buffered(65536).use { output ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    total += read
                    onChunkCopied(total)
                }
                output.flush()
            }
        }
        return total
    }

    private fun readAssetLength(assetName: String): Long {
        val descriptor: AssetFileDescriptor = runCatching { context.assets.openFd(assetName) }.getOrElse { return -1L }
        return descriptor.use {
            if (it.length >= 0) it.length else -1L
        }
    }
}
