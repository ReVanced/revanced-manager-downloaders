package app.revanced.manager.downloaders.play.store

import android.os.Parcelable
import android.util.Log
import app.revanced.manager.downloader.*
import app.revanced.manager.downloaders.shared.Merger
import app.revanced.manager.downloaders.R
import app.revanced.manager.downloaders.play.store.data.Credentials
import app.revanced.manager.downloaders.play.store.data.Http
import app.revanced.manager.downloaders.play.store.data.loadData
import app.revanced.manager.downloaders.play.store.data.saveData
import app.revanced.manager.downloaders.play.store.ui.AuthFragment
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import io.ktor.client.request.url
import kotlinx.parcelize.Parcelize
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

private val allowedFileTypes = arrayOf(PlayFile.Type.BASE, PlayFile.Type.SPLIT)
const val LOG_TAG = "PlayStorePlugin"

@Parcelize
class GPlayApp(
    val files: List<PlayFile>
) : Parcelable

@Suppress("Unused")
@OptIn(ExperimentalPathApi::class)
val playStoreDownloader = Downloader(R.string.play_store) {
    val dataFile = dataDir.resolve("play_store.json")

    get { packageName, version ->
        var data: Pair<Credentials, Properties>? = null
        try {
            data = loadData(dataFile.inputStream())
        } catch (_: Exception) {}

        if (data == null) @Suppress("DEPRECATION") try {
            val result = requestStartFragment<AuthFragment>(null)!!
            val creds = result.getParcelableExtra<Credentials>(AuthFragment.CREDENTIALS_KEY)!!
            val props = result.getSerializableExtra(AuthFragment.PROPERTIES_KEY) as Properties

            saveData(dataFile.outputStream(), creds, props)
            data = creds to props
        } catch (e: UserInteractionException.Activity.NotCompleted) {
            if (e.resultCode == AuthFragment.RESULT_FAILED) throw Exception(
                "Login failed: ${
                    e.intent?.getStringExtra(
                        AuthFragment.FAILURE_MESSAGE_KEY
                    )
                }"
            )
            throw e
        }

        val (credentials, deviceProps) = data
        val authData = credentials.toAuthData(deviceProps)

        val app = try {
            AppDetailsHelper(authData).using(Http).getAppByPackageName(packageName)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Got exception while trying to get app", e)
            null
        }?.takeUnless { version != null && it.versionName != version } ?: return@get null
        if (!app.isFree) return@get null

        GPlayApp(
            app.fileList.filterNot { it.url.isBlank() }.ifEmpty {
                PurchaseHelper(authData).using(Http)
                    .purchase(
                        app.packageName,
                        app.versionCode,
                        app.offerType
                    )
            }
        ) to app.versionName
    }

    download { app, outputStream ->
        val apkDir = Files.createTempDirectory("play_dl")
        try {
            if (app.files.isEmpty()) error("No valid files to download")
            app.files.forEach { file ->
                if (file.type !in allowedFileTypes) error("${file.name} could not be downloaded because it has an unsupported type: ${file.type.name}")
                apkDir.resolve(file.name).outputStream(StandardOpenOption.CREATE_NEW)
                    .use { stream ->
                        Http.download(stream) {
                            url(file.url)
                        }
                    }
            }

            val apkFiles = apkDir.listDirectoryEntries()
            if (apkFiles.size == 1) {
                Files.copy(apkFiles.first(), outputStream)
            } else {
                // The Play Store only serves the splits this device needs, so there is nothing to filter here.
                val mergedApk = Files.createTempFile("play_dl", ".apk")
                try {
                    Merger.merge(apkDir).use { it.writeTo(mergedApk) }

                    reportSize(mergedApk.fileSize())
                    Files.copy(mergedApk, outputStream)
                } finally {
                    mergedApk.deleteIfExists()
                }
            }
        } finally {
            apkDir.deleteRecursively()
        }
    }
}