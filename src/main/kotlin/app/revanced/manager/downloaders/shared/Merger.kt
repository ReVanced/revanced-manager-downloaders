package app.revanced.manager.downloaders.shared

import android.util.Log
import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ArscLogger : APKLogger {
    const val TAG = "ARSCLib"

    override fun logMessage(msg: String) {
        Log.i(TAG, msg)
    }

    override fun logError(msg: String, tr: Throwable?) {
        Log.e(TAG, msg, tr)
    }

    override fun logVerbose(msg: String) {
        Log.v(TAG, msg)
    }
}

/**
 * What the device can make use of. Config splits for other ABIs, screen densities or languages
 * are left out of the merge, like the Play Store leaves them out of an install.
 *
 * @param abis The supported ABIs, most preferred first (see [android.os.Build.SUPPORTED_ABIS]).
 * @param densityDpi The screen density.
 * @param languages The ISO 639 language codes of the configured locales.
 */
data class DeviceConfiguration(
    val abis: List<String>,
    val densityDpi: Int,
    val languages: Set<String>,
)

/**
 * The result of a merge. The merged module reads its entries lazily from the split files,
 * so this must be kept open until the APK has been written.
 */
class MergedApk internal constructor(private val bundle: ApkBundle, private val module: ApkModule) : Closeable {
    /**
     * Writes the merged APK to [path].
     * This goes through a file on purpose: ARSCLib's stream writer buffers every entry in memory first.
     */
    fun writeTo(path: Path) = module.writeApk(path.toFile())

    override fun close() = bundle.close()
}

object Merger {
    private const val TAG = "Merger"

    private val abis = setOf("armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64")
    private val densities = mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
    )

    /**
     * Merges the split APKs in [apkDir] into a single APK.
     *
     * @param apkDir A directory containing the base APK and its splits.
     * @param device When given, config splits the device does not need are skipped.
     */
    suspend fun merge(apkDir: Path, device: DeviceConfiguration? = null) = withContext(Dispatchers.Default) {
        val bundle = ApkBundle().apply {
            setAPKLogger(ArscLogger)
            loadApkDirectory(apkDir.toFile())
        }

        try {
            if (device != null) bundle.selectSplitsFor(device)

            MergedApk(bundle, bundle.mergeIntoBase().apply { removeSplitManifestEntries() })
        } catch (t: Throwable) {
            bundle.close()
            throw t
        }
    }

    /**
     * Merges every split into the base module and returns it.
     * Unlike [ApkBundle.mergeModules], this does not copy the base resource table into a new module first,
     * which roughly halves the memory needed for the merge.
     */
    private fun ApkBundle.mergeIntoBase(): ApkModule {
        val base = baseModule ?: error("The bundle does not contain a base APK")

        apkModuleList.filter { it !== base }.forEach(base::merge)

        if (base.hasTableBlock()) base.tableBlock.apply {
            sortPackages()
            refresh()
        }
        base.zipEntryMap.autoSortApkFiles()

        return base
    }

    /**
     * Removes the config splits (ABI, screen density, language) that [device] would never load.
     * The base and feature splits are always kept.
     */
    private fun ApkBundle.selectSplitsFor(device: DeviceConfiguration) {
        // The split name is "config.<qualifier>" for the base module and "<feature>.config.<qualifier>" for a feature module.
        val configSplits = apkModuleList.mapNotNull { module ->
            module.split?.substringAfter("config.", "")?.takeIf { it.isNotEmpty() }?.let { module to it }
        }

        val abiSplits = configSplits.filter { (_, qualifier) -> qualifier.toAbi() in abis }
        val densitySplits = configSplits.filter { (_, qualifier) -> qualifier in densities }
        val languageSplits = configSplits.filter { (_, qualifier) ->
            qualifier.toAbi() !in abis && !qualifier.endsWith("dpi")
        }

        // Keep every ABI split if none matches, so that the installer can report the problem instead.
        val wantedAbis = abiSplits.filter { (_, qualifier) -> qualifier.toAbi() in device.abis }.ifEmpty { abiSplits }

        // Pick the nearest density bucket at or above the device density, like the resource system does.
        val availableDensities = densitySplits.map { (_, qualifier) -> densities.getValue(qualifier) }
        val wantedDensity = availableDensities.filter { it >= device.densityDpi }.minOrNull() ?: availableDensities.maxOrNull()
        val wantedDensities = densitySplits.filter { (_, qualifier) -> densities[qualifier] == wantedDensity }

        // The base APK carries the default language, so no fallback is needed here.
        val wantedLanguages = languageSplits.filter { (_, qualifier) -> qualifier.toLanguage() in device.languages }

        val unwanted = (abiSplits - wantedAbis.toSet()) + (densitySplits - wantedDensities.toSet()) + (languageSplits - wantedLanguages.toSet())
        unwanted.forEach { (module, _) ->
            Log.i(TAG, "Skipping split ${module.moduleName}")
            removeApkModule(module.moduleName)
            module.close()
        }
        Log.i(TAG, "Merging ${apkModuleList.size} of ${apkModuleList.size + unwanted.size} APKs")
    }

    // Split names use "arm64_v8a", ABIs use "arm64-v8a".
    private fun String.toAbi() = replace('_', '-')

    // Language qualifiers look like "en", "pt_BR", "b+sr+Latn".
    private fun String.toLanguage() = removePrefix("b+").split('+', '-', '_').first()

    private fun ApkModule.removeSplitManifestEntries() = androidManifest.apply {
        listOf(AndroidManifest.ID_isSplitRequired, AndroidManifest.ID_extractNativeLibs).forEach {
            applicationElement.removeAttributesWithId(it)
            manifestElement.removeAttributesWithId(it)
        }

        listOf(AndroidManifest.NAME_requiredSplitTypes, AndroidManifest.NAME_splitTypes).forEach { name ->
            manifestElement.removeAttributeIf { it.name == name }
        }

        val pattern = "^com\\.android\\.(stamp|vending)\\.".toRegex()
        applicationElement.removeElementsIf { element ->
            if (element.name != AndroidManifest.TAG_meta_data) return@removeElementsIf false

            val name = element.getAttributes { it.nameId == AndroidManifest.ID_name }
                .asSequence().singleOrNull()?.valueString

            name != null && pattern.containsMatchIn(name)
        }

        refresh()
    }
}
