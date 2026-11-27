package ng1ok.yggdrasilmanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import ng1ok.yggdrasilmanager.data.hwbp.PackageLibraryNames
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageLibraryNamesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun collectsAllAbisAndSplitsAlongsideExtractedLibraries() {
        val base = apk("base.apk", "lib/arm64-v8a/libbase.so", "lib/armeabi-v7a/libbase.so",
            "assets/libasset.so", "lib/arm64-v8a/nested/libnested.so", "lib/arm64-v8a/readme.txt",
            "lib/arm64-v8a/libdirectory.so/")
        val split = apk("split_config.arm64.apk", "lib/arm64-v8a/libsplit.so", "lib/x86_64/libother.so")
        val extracted = temporaryFolder.newFolder("lib")
        File(extracted, "libextracted.so").writeText("elf")
        File(extracted, "libbase.so").writeText("elf")
        File(extracted, "libversioned.so.1").writeText("elf")
        File(extracted, "readme.txt").writeText("ignored")
        File(extracted, "libdirectory.so").mkdir()

        val names = source(base, listOf(split), extracted).load("com.example.target")

        assertEquals(listOf("libbase.so", "libextracted.so", "libother.so", "libsplit.so", "libversioned.so.1"), names)
    }

    @Test
    fun librariesRemainAvailableWhenExtractionIsDisabled() {
        val base = apk("base.apk", "classes.dex")
        val split = apk("split_config.arm64.apk", "lib/arm64-v8a/libunextracted.so")

        val names = source(base, listOf(split), File(temporaryFolder.root, "missing-lib-dir"))
            .load("com.example.target")

        assertEquals(listOf("libunextracted.so"), names)
    }

    @Test
    fun appWithoutNativeCodeReturnsAnEmptyList() {
        assertEquals(emptyList<String>(), source(apk("base.apk", "classes.dex"))
            .load("com.example.target"))
    }

    @Test(expected = IOException::class)
    fun unreadableApkIsReportedInsteadOfReturningAnIncompleteList() {
        source(apk("base.apk"), listOf(File(temporaryFolder.root, "missing-split.apk")))
            .load("com.example.target")
    }

    private fun source(base: File, splits: List<File> = emptyList(), extracted: File? = null): PackageLibraryNames {
        val info = mockk<ApplicationInfo>().apply {
            sourceDir = base.path
            splitSourceDirs = splits.map { it.path }.toTypedArray()
            nativeLibraryDir = extracted?.path
        }
        val packageManager = mockk<PackageManager>()
        every { packageManager.getApplicationInfo("com.example.target", 0) } returns info
        return PackageLibraryNames(packageManager)
    }

    private fun apk(name: String, vararg entries: String): File = temporaryFolder.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry))
                zip.closeEntry()
            }
        }
    }
}
