package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.core.Logger
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginLoaderTest {
    @Test
    fun `directory scan retains malformed jar failure for the plugin manager`() {
        val directory = Files.createTempDirectory("qtranslate-plugin-loader").toFile()
        try {
            val jar = directory.resolve("broken-plugin.jar")
            JarOutputStream(jar.outputStream()).use { }
            val loader = PluginLoader(NoOpLogger)

            val plugins = loader.loadPluginsFromDirectory(directory)

            assertTrue(plugins.isEmpty())
            assertEquals(1, loader.loadFailures.size)
            assertEquals(jar.absolutePath, loader.loadFailures.single().jarPath)
            assertTrue(loader.loadFailures.single().message.contains("Plugin implementation"))
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * The manifest used to be read through a class loader, and class loaders ask their parent
     * first. Any `plugin.json` on the application's own class path therefore answered for every
     * plugin — which is what the macOS bundle did, because jpackage puts every JAR found under
     * its input directory on the class path. Each plugin took on the first one's identity and the
     * registry rejected all of them as duplicates of each other.
     *
     * `src/test/resources/plugin.json` reproduces that class path entry, so this fails against
     * the old lookup and passes against reading the archive directly.
     */
    @Test
    fun `manifest is read from the jar even when the class path offers one`() {
        assertNotNull(
            javaClass.classLoader.getResource("plugin.json"),
            "тест бессмыслен без plugin.json на classpath"
        )

        val directory = Files.createTempDirectory("qtranslate-plugin-manifest").toFile()
        try {
            val jar = directory.resolve("bing-services-plugin.jar")
            JarOutputStream(jar.outputStream()).use { stream ->
                stream.putNextEntry(JarEntry("plugin.json"))
                stream.write(
                    """
                    {
                      "id": "bing-services",
                      "name": "Bing Services",
                      "version": "1.0.0",
                      "author": "CamWork",
                      "description": "Fixture.",
                      "minApiVersion": "1.0.0"
                    }
                    """.trimIndent().toByteArray(Charsets.UTF_8)
                )
                stream.closeEntry()
            }

            val manifest = PluginLoader(NoOpLogger).getManifestFromJar(jar)

            assertEquals("bing-services", manifest?.id)
        } finally {
            directory.deleteRecursively()
        }
    }

    private object NoOpLogger : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }
}
