package ng1ok.yggdrasilmanager.data.hwbp

import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Read installed native library names through [packageManager] without starting the target app. */
internal class PackageLibraryNames(private val packageManager: PackageManager) {
    /** Return distinct library basenames from [packageName]'s base APK, splits, and extracted directory. */
    @Suppress("DEPRECATION")
    fun load(packageName: String): List<String> {
        val info = packageManager.getApplicationInfo(packageName, 0)
        val names = mutableSetOf<String>()
        // extractNativeLibs=false leaves libraries inside APKs; ABI splits can hold
        // all native code even when the base APK has no lib/ entries.
        val apkPaths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        apkPaths.distinct().forEach { path ->
            ZipFile(path).use { apk ->
                apk.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .mapNotNull { entry -> APK_LIBRARY.matchEntire(entry.name)?.groupValues?.get(1) }
                    .forEach(names::add)
            }
        }
        info.nativeLibraryDir?.let { path ->
            val directory = File(path)
            if (directory.exists()) {
                val files = directory.listFiles()
                    ?: throw IOException("Unable to read native library directory: $path")
                files.filter { it.isFile && LIBRARY_NAME.matches(it.name) }
                    .forEach { names += it.name }
            }
        }
        return names.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
    }

    private companion object {
        val LIBRARY_NAME = Regex("[^/]+\\.so(?:\\.[^/]+)?")
        val APK_LIBRARY = Regex("lib/[^/]+/(${LIBRARY_NAME.pattern})")
    }
}
