package com.coderred.andclaw.proroot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

object DownloadUtils {

    /**
     * URL에서 파일을 다운로드한다. 진행률 콜백을 지원한다.
     *
     * @param url 다운로드 URL
     * @param destFile 저장할 파일
     * @param onProgress 진행률 콜백 (다운로드된 바이트, 전체 바이트). totalBytes는 -1일 수 있음.
     */
    suspend fun downloadFile(
        url: String,
        destFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val urlObj = URL(url)
            connection = urlObj.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "andClaw/1.0 (Android)")
            connection.instanceFollowRedirects = true

            connection.connect()

            // HTTP 리다이렉트 수동 처리 (HTTPS→HTTP 전환 등)
            var responseCode = connection.responseCode
            var redirectCount = 0
            var currentConnection: HttpURLConnection = connection!!
            while (responseCode in 301..308 && redirectCount < 5) {
                val redirectUrl = currentConnection.getHeaderField("Location")
                    ?: throw IOException("Missing redirect URL")
                currentConnection.disconnect()
                val newUrl = URL(redirectUrl)
                currentConnection = newUrl.openConnection() as HttpURLConnection
                currentConnection.connectTimeout = 30_000
                currentConnection.readTimeout = 60_000
                currentConnection.setRequestProperty("User-Agent", "andClaw/1.0 (Android)")
                currentConnection.connect()
                responseCode = currentConnection.responseCode
                redirectCount++
                connection = currentConnection
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $responseCode: ${connection.responseMessage}")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            destFile.parentFile?.mkdirs()

            connection.inputStream.buffered(32768).use { input ->
                destFile.outputStream().buffered(32768).use { output ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }

            onProgress(downloadedBytes, downloadedBytes)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 다운로드 크기를 사람이 읽기 쉬운 형식으로 변환
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
