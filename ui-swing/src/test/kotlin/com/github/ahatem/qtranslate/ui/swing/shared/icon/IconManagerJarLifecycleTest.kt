package com.github.ahatem.qtranslate.ui.swing.shared.icon

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.imageio.ImageIO
import javax.swing.Icon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IconManagerJarLifecycleTest {
    @Test
    fun svgIconDoesNotKeepPluginJarLocked() {
        verifyDeletableIcon("svg", """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0h24v24H0z" fill="#17a64a"/></svg>""".toByteArray())
    }

    @Test
    fun pngIconDoesNotKeepPluginJarLocked() {
        val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { graphics ->
            try {
                graphics.color = Color(0x17a64a)
                graphics.fillRect(0, 0, 24, 24)
            } finally {
                graphics.dispose()
            }
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        verifyDeletableIcon("png", bytes)
    }

    private fun verifyDeletableIcon(extension: String, bytes: ByteArray) {
        val root = Files.createTempDirectory("camwork-plugin-icon-lifecycle").toFile()
        val store = PluginKeyValueStore(root)
        try {
            val logger = object : Logger {
                override fun debug(message: String) = Unit
                override fun info(message: String) = Unit
                override fun warn(message: String) = Unit
                override fun error(message: String, error: Throwable?) = Unit
            }
            val loggers = object : LoggerFactory {
                override fun getLogger(name: String): Logger = logger
            }
            val manager = PluginManager(
                root, SettingsRepository(root, Json, logger),
                PluginFingerprintRepository(root, Json), store, loggers, NotificationBus()
            )
            val icons = IconManager(manager)
            val jar = root.resolve("plugin.jar")
            val path = "plugin-assets/icon." + extension
            JarOutputStream(jar.outputStream()).use { archive ->
                archive.putNextEntry(JarEntry(path))
                archive.write(bytes)
                archive.closeEntry()
            }
            // Проверяем настоящий IconManager без регистрации исполняемого тестового плагина.
            val create = IconManager::class.java.getDeclaredMethod(
                "createIcon", String::class.java, Integer.TYPE, Integer.TYPE, ClassLoader::class.java
            ).apply { isAccessible = true }
            val icon = URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
                (create.invoke(icons, path, 24, 24, loader) as Icon).also {
                    assertTrue(it.iconWidth > 0 && it.iconHeight > 0)
                }
            }
            Files.delete(jar.toPath())
            assertFalse(jar.exists(), "После загрузки иконки и закрытия loader JAR должен удаляться")
            val canvas = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
            canvas.createGraphics().let { graphics ->
                try {
                    icon.paintIcon(null, graphics, 0, 0)
                } finally {
                    graphics.dispose()
                }
            }
            assertEquals(Color(0x17a64a).rgb, canvas.getRGB(canvas.width / 2, canvas.height / 2), "Иконка должна рисовать исходный ресурс после удаления JAR")
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }
}
