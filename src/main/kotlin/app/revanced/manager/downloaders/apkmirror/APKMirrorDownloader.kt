@file:Suppress("Unused")

package app.revanced.manager.downloaders.apkmirror

import android.content.res.Resources
import android.net.Uri
import android.os.Build
import app.revanced.manager.downloader.DownloadUrl
import app.revanced.manager.downloader.Downloader
import app.revanced.manager.downloader.download
import app.revanced.manager.downloader.webview.runWebView
import app.revanced.manager.downloaders.R
import app.revanced.manager.downloaders.shared.DeviceConfiguration
import app.revanced.manager.downloaders.shared.Merger
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val bundleExtensions = setOf("apkm", "xapk", "apks")

@OptIn(ExperimentalPathApi::class)
val ApkMirrorDownloader = Downloader(R.string.apkmirror) {
    get { packageName, version ->
        runWebView("APKMirror") {
            download { url, _, userAgent ->
                finish(
                    DownloadUrl(
                        url,
                        mapOf("User-Agent" to userAgent)
                    )
                )
            }

            Uri.Builder()
                .scheme("https")
                .authority("www.apkmirror.com")
                .appendQueryParameter("post_type", "app_release")
                .appendQueryParameter("searchtype", "apk")
                .appendQueryParameter("s", version?.let { "$packageName $it" } ?: packageName)
                .appendQueryParameter("bundles%5B%5D" /* bundles[] */, "apk_files")
                .toString()
        } to version
    }

    download { downloadUrl, outputStream ->
        val fileName = URI(downloadUrl.url).path.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', "").lowercase()

        when {
            extension == "apk" -> {
                val (inputStream, size) = downloadUrl.toDownloadResult()
                inputStream.use {
                    if (size != null) reportSize(size)
                    it.copyTo(outputStream)
                }
            }

            extension in bundleExtensions -> {
                val workingDir = Files.createTempDirectory("apkmirror_dl")
                try {
                    val bundle = workingDir.resolve("bundle.$extension")
                    downloadUrl.toDownloadResult().first.use { input ->
                        bundle.outputStream().use { input.copyTo(it) }
                    }

                    val splitsDir = workingDir.resolve("splits").createDirectories()
                    if (extractApks(bundle, splitsDir) == 0) error("$fileName does not contain any APK files")

                    val mergedApk = workingDir.resolve("merged.apk")
                    Merger.merge(splitsDir, deviceConfiguration()).use { it.writeTo(mergedApk) }

                    reportSize(mergedApk.fileSize())
                    mergedApk.inputStream().use { it.copyTo(outputStream) }
                } finally {
                    workingDir.deleteRecursively()
                }
            }

            else -> error("Unsupported file type: $fileName. Select an APK or an APK bundle (.apkm) on APKMirror.")
        }
    }
}

/**
 * Extracts the APK files inside [bundle] into [into] and returns how many were extracted.
 */
private fun extractApks(bundle: Path, into: Path) = ZipFile(bundle.toFile()).use { zip ->
    zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
        .onEach { entry ->
            zip.getInputStream(entry).use { Files.copy(it, into.resolve(entry.name.substringAfterLast('/'))) }
        }
        .count()
}

private fun deviceConfiguration(): DeviceConfiguration {
    val resources = Resources.getSystem()
    val locales = resources.configuration.locales

    return DeviceConfiguration(
        abis = Build.SUPPORTED_ABIS.toList(),
        densityDpi = resources.displayMetrics.densityDpi,
        languages = List(locales.size()) { locales[it].language }.toSet(),
    )
}
