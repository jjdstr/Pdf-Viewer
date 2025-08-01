package com.rajat.pdfviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rajat.pdfviewer.util.CacheManager
import com.rajat.pdfviewer.util.CacheStrategy
import com.rajat.pdfviewer.util.FileUtils.getCachedFileName
import com.rajat.pdfviewer.util.FileUtils.isValidPdf
import com.rajat.pdfviewer.util.FileUtils.writeFile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException


class PdfDownloader(
    private val coroutineScope: CoroutineScope,
    private val headers: HeaderData,
    private val url: String,
    private val cacheStrategy: CacheStrategy,
    private val listener: StatusListener,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    interface StatusListener {
        fun getContext(): Context
        fun onDownloadStart()
        fun onDownloadProgress(currentBytes: Long, totalBytes: Long)
        fun onDownloadSuccess(downloadedFile: File)
        fun onDownloadError(error: Throwable)
    }

    fun start() {
        if (activeDownloads.contains(url)) {
            Log.d(TAG, "Download already in progress for URL: $url")
            // Optionally, notify the listener that a download is already in progress
            return
        }
        activeDownloads.add(url)
        try {
            coroutineScope.launch(Dispatchers.IO) {
                // Validate URL scheme before proceeding
                if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                    withContext(Dispatchers.Main) {
                        listener.onDownloadError(
                            IllegalArgumentException("Invalid URL scheme: $url. Expected HTTP or HTTPS.")
                        )
                    }
                    return@launch
                }
                checkAndDownload(url)
            }
        } finally {
            activeDownloads.remove(url)
        }
    }

    companion object {
        private const val TAG = "PdfDownloader"
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY = 2000L

        private val activeDownloads = mutableSetOf<String>()

        private var defaultHttpClient: OkHttpClient? = null

        @JvmStatic
        fun defaultHttpClient(): OkHttpClient {
            return defaultHttpClient ?: OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build().also { defaultHttpClient = it }
        }
    }

    private suspend fun checkAndDownload(downloadUrl: String) {
        val cachedFileName = getCachedFileName(downloadUrl)

        if (cacheStrategy != CacheStrategy.DISABLE_CACHE) {
            CacheManager.clearCacheDir(listener.getContext())
        }

        val cacheDir = File(
            listener.getContext().cacheDir,
            "___pdf___cache___/$cachedFileName"
        ).apply { mkdirs() }

        val pdfFile = File(cacheDir, cachedFileName)

        if (cacheStrategy != CacheStrategy.DISABLE_CACHE && pdfFile.exists() && isValidPdf(pdfFile)) {
            withContext(Dispatchers.Main) {
                listener.onDownloadSuccess(pdfFile)
            }
        } else {
            retryDownload(downloadUrl, pdfFile)
        }
    }

    private suspend fun retryDownload(downloadUrl: String, pdfFile: File) {
        Log.d(TAG, "Retrying download for: $downloadUrl")
        withContext(Dispatchers.Main) {
            listener.onDownloadStart()
        }
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                downloadFile(downloadUrl, pdfFile)
                return
            } catch (e: IOException) {
                if (isInvalidFileError(e)) {
                    withContext(Dispatchers.Main) {
                        listener.onDownloadError(InvalidPdfException(e.message ?: "Invalid PDF"))
                    }
                    return
                }

                attempt++
                Log.e(TAG, "Attempt $attempt failed: $downloadUrl", e)

                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY)
                } else {
                    withContext(Dispatchers.Main) {
                        listener.onDownloadError(
                            DownloadFailedException(
                                "Failed after $MAX_RETRIES attempts",
                                e
                            )
                        )
                    }
                }
            }
        }
    }

    private fun isInvalidFileError(error: IOException): Boolean {
        val message = error.message ?: return false
        return message.contains("Invalid content type", ignoreCase = true) ||
                message.contains("Downloaded file is not a valid PDF", ignoreCase = true)
    }

    private suspend fun downloadFile(downloadUrl: String, pdfFile: File) =
        withContext(Dispatchers.IO) {
            val tempFile = File.createTempFile("download_", ".tmp", pdfFile.parentFile)

            try {
                if (pdfFile.exists() && !isValidPdf(pdfFile)) {
                    pdfFile.delete()
                }

                val response = makeNetworkRequest(downloadUrl)
                validateResponse(response)

                response.body?.use { body ->
                    body.byteStream().use { inputStream ->
                        writeFile(inputStream, tempFile, body.contentLength()) { progress ->
                            Handler(Looper.getMainLooper()).post {
                                listener.onDownloadProgress(progress, body.contentLength())
                            }
                        }
                    }
                } ?: throw IOException("Empty response body received for PDF")

                val renamed = tempFile.renameTo(pdfFile)
                if (!renamed) {
                    tempFile.delete()
                    throw IOException("Failed to rename temp file to final PDF path")
                }

                if (!isValidPdf(pdfFile)) {
                    pdfFile.delete()
                    throw InvalidPdfException("Downloaded file is not a valid PDF")
                }

                Log.d(TAG, "Downloaded PDF to: ${pdfFile.absolutePath}")

                withContext(Dispatchers.Main) {
                    listener.onDownloadSuccess(pdfFile)
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }

    private fun makeNetworkRequest(downloadUrl: String): Response {
        val requestBuilder = Request.Builder().url(downloadUrl)
        headers.headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }

        return httpClient.newCall(requestBuilder.build()).execute()
    }

    private fun validateResponse(response: Response) {
        if (!response.isSuccessful) {
            throw DownloadFailedException("Failed to download PDF, HTTP Status: ${response.code}")
        }

        val contentType = response.header("Content-Type", "")
        if (contentType?.containsAny(
                listOf("application/pdf", "application/octet-stream"),
                ignoreCase = true
            ) == false
        ) {
            throw InvalidPdfException("Invalid content type: $contentType. Expected PDF.")
        }
    }
}

private fun String?.containsAny(listOf: List<String>, ignoreCase: Boolean): Boolean {
    listOf.forEach { if (this?.contains(it, ignoreCase) == true) return true }
    return false
}

class DownloadFailedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class InvalidPdfException(message: String) : IOException(message)
