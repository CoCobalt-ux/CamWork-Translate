package com.github.ahatem.qtranslate.ui.swing.shared.icon

import com.github.ahatem.qtranslate.api.core.Logger
import com.github.ahatem.qtranslate.core.plugin.PluginManager
import com.github.ahatem.qtranslate.core.plugin.storage.PluginFingerprintRepository
import com.github.ahatem.qtranslate.core.plugin.storage.PluginKeyValueStore
import com.github.ahatem.qtranslate.core.settings.data.SettingsRepository
import com.github.ahatem.qtranslate.core.shared.logging.LoggerFactory
import com.github.ahatem.qtranslate.core.shared.notification.NotificationBus
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.extras.FlatSVGIcon
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
import javax.swing.SwingUtilities
import javax.swing.UIManager
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

    @Test
    fun svgPreservesDarkVariantAndJarResourceIdentity() {
        val previous = UIManager.getLookAndFeel()
        fun svg(color: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M0 0h24v24H0z" fill="$color"/></svg>""".toByteArray()
        try {
            onEdt { FlatLightLaf.setup() }
            verifyDeletableIcon("svg", svg("#17a64a"), mapOf("plugin-assets/icon_dark.svg" to svg("#a6174a"))) { icon, path, loader ->
                val vector = icon as FlatSVGIcon
                assertEquals(path, vector.name)
                assertEquals(loader.getResource(path)!!.toURI(), vector.classLoader.getResource(path)!!.toURI())
                assertEquals(Color(0x17a64a).rgb, centerPixel(icon))
                onEdt { FlatDarkLaf.setup() }
                assertEquals(Color(0xa6174a).rgb, centerPixel(icon))
                onEdt { FlatLightLaf.setup() }
                assertEquals(Color(0x17a64a).rgb, centerPixel(icon))
            }
        } finally {
            onEdt { UIManager.setLookAndFeel(previous) }
        }
    }

    private fun verifyDeletableIcon(
        extension: String,
        bytes: ByteArray,
        extraResources: Map<String, ByteArray> = emptyMap(),
        beforeDelete: (Icon, String, URLClassLoader) -> Unit = { _, _, _ -> }
    ) {
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
                extraResources.forEach { (name, content) ->
                    archive.putNextEntry(JarEntry(name))
                    archive.write(content)
                    archive.closeEntry()
                }
            }
            // Проверяем настоящий IconManager без регистрации исполняемого тестового плагина.
            val create = IconManager::class.java.getDeclaredMethod(
                "createIcon", String::class.java, Integer.TYPE, Integer.TYPE, ClassLoader::class.java
            ).apply { isAccessible = true }
            val icon = URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
                (create.invoke(icons, path, 24, 24, loader) as Icon).also {
                    assertTrue(it.iconWidth > 0 && it.iconHeight > 0)
                    beforeDelete(it, path, loader)
                }
            }
            Files.delete(jar.toPath())
            assertFalse(jar.exists(), "После загрузки иконки и закрытия loader JAR должен удаляться")
            assertEquals(Color(0x17a64a).rgb, centerPixel(icon), "Иконка должна рисовать исходный ресурс после удаления JAR")
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    private fun centerPixel(icon: Icon): Int {
        val canvas = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        canvas.createGraphics().let { graphics ->
            try {
                icon.paintIcon(null, graphics, 0, 0)
            } finally {
                graphics.dispose()
            }
        }
        return canvas.getRGB(canvas.width / 2, canvas.height / 2)
    }

    private fun onEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait { action() }
    }
}
