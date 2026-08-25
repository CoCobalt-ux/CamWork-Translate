package com.github.ahatem.qtranslate.core.updater

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsDistributionDetectorTest {
    private val tempDir = Files.createTempDirectory("camwork-update-distribution").toFile()

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `complete Inno uninstaller pair identifies installed copy`() {
        val launcher = file("installed/CamWork Translate.exe")
        file("installed/unins000.exe")
        file("installed/unins000.dat")

        assertEquals(
            ReleaseAssets.WindowsDistribution.INSTALLED,
            WindowsDistributionDetector.detect(launcher.absolutePath)
        )
    }

    @Test
    fun `Windows app image without uninstaller is portable`() {
        val launcher = file("portable/CamWork Translate.exe")

        assertEquals(
            ReleaseAssets.WindowsDistribution.PORTABLE,
            WindowsDistributionDetector.detect(launcher.absolutePath)
        )
    }

    @Test
    fun `single suspicious uninstaller file is not enough`() {
        val launcher = file("incomplete/CamWork Translate.exe")
        file("incomplete/unins000.exe")

        assertEquals(
            ReleaseAssets.WindowsDistribution.PORTABLE,
            WindowsDistributionDetector.detect(launcher.absolutePath)
        )
    }

    @Test
    fun `missing or invalid launcher safely falls back to portable`() {
        assertEquals(
            ReleaseAssets.WindowsDistribution.PORTABLE,
            WindowsDistributionDetector.detect(null)
        )
        assertEquals(
            ReleaseAssets.WindowsDistribution.PORTABLE,
            WindowsDistributionDetector.detect(File(tempDir, "missing.exe").absolutePath)
        )
    }

    private fun file(relativePath: String): File = File(tempDir, relativePath).apply {
        parentFile.mkdirs()
        createNewFile()
    }
}
