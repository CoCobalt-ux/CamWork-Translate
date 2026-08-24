package com.github.ahatem.qtranslate.ui.swing.main

import java.awt.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.swing.ImageIcon
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.math.abs

class CamWorkBrandLabelTest {

    @Test
    fun `логотип не исчезает после перерисовки и изменения ширины`() {
        val original = ImageIcon(assertNotNull(javaClass.classLoader.getResource(CamWorkBrand.HORIZONTAL_RESOURCE)))
        val source = ImageIcon(
            original.image.getScaledInstance(
                CamWorkBrand.HORIZONTAL_WIDTH,
                CamWorkBrand.HORIZONTAL_HEIGHT,
                Image.SCALE_SMOOTH
            )
        )
        val label = CamWorkBrandLabel(source)

        listOf(0, 320, 480, 900).forEach { width ->
            label.setSize(width, label.preferredSize.height)
            label.updateUI()
            label.doLayout()

            assertSame(source, label.icon)
            assertEquals("", label.text)
            assertTrue(label.isVisible)
            assertTrue(label.minimumSize.width >= CamWorkBrand.HORIZONTAL_WIDTH)
            assertEquals(label.minimumSize, label.preferredSize)
            assertEquals(label.preferredSize, label.maximumSize)
            assertEquals(
                source.iconHeight + label.insets.top + label.insets.bottom,
                label.preferredSize.height
            )
        }
    }

    @Test
    fun `логотип уменьшен примерно на тридцать процентов без искажения пропорций`() {
        val sourceRatio = 2094.0 / 385.0
        val displayRatio = CamWorkBrand.HORIZONTAL_WIDTH.toDouble() / CamWorkBrand.HORIZONTAL_HEIGHT

        assertEquals(152, CamWorkBrand.HORIZONTAL_WIDTH)
        assertEquals(28, CamWorkBrand.HORIZONTAL_HEIGHT)
        assertTrue(abs(displayRatio - sourceRatio) / sourceRatio < 0.005)
    }

    @Test
    fun `логотип получает верхний воздух и остаётся оптически центрированным на 100 и 200 процентах`() {
        val logical = CamWorkBrand.insets { it }
        val doubled = CamWorkBrand.insets { it * 2 }

        assertEquals(12, logical.left)
        assertEquals(6, logical.right)
        assertEquals(8, logical.top)
        assertEquals(6, logical.bottom)

        assertEquals(24, doubled.left)
        assertEquals(12, doubled.right)
        assertEquals(16, doubled.top)
        assertEquals(12, doubled.bottom)

        // Центр изображения на 1 logical px ниже центра доступной высоты — это компенсирует
        // визуальный вес светлой планеты и не даёт lockup снова прилипнуть к верхней границе.
        assertEquals(1, opticalOffset(logical, CamWorkBrand.HORIZONTAL_HEIGHT))
        assertEquals(2, opticalOffset(doubled, CamWorkBrand.HORIZONTAL_HEIGHT * 2))
    }

    private fun opticalOffset(insets: java.awt.Insets, iconHeight: Int): Int {
        val componentHeight = insets.top + iconHeight + insets.bottom
        val iconCenter = insets.top + iconHeight / 2
        return iconCenter - componentHeight / 2
    }

    @Test
    fun `в ресурсах лежит финальный прозрачный продуктовый lockup`() {
        val stream = javaClass.classLoader.getResourceAsStream(CamWorkBrand.HORIZONTAL_RESOURCE)
        val bytes = assertNotNull(stream).use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02X".format(it) }
        val image = ImageIO.read(bytes.inputStream())

        assertEquals("0DCE7D6B3583B28242403A7E797DB462BE9A45B0CC337DBE403F3024DEFACEED", hash)
        assertEquals(2094, image.width)
        assertEquals(385, image.height)
        assertTrue(image.colorModel.hasAlpha())
    }

    @Test
    fun `релизные png и ico содержат официальную планету для Windows DPI`() {
        val markBytes = assertNotNull(
            javaClass.classLoader.getResourceAsStream("icons/app/camwork-mark.svg")
        ).use { it.readBytes() }
        val markHash = MessageDigest.getInstance("SHA-256")
            .digest(markBytes)
            .joinToString("") { "%02X".format(it) }
        assertEquals("6CEE51F2CCB5D3AE371D3B1584735021D4B1B9F6DD8F9E04FACB50D88C720C1E", markHash)

        listOf(16, 20, 24, 32, 48, 64, 128, 256, 512, 1024).forEach { size ->
            val path = "icons/app/icon-$size.png"
            val image = assertNotNull(javaClass.classLoader.getResourceAsStream(path)).use(ImageIO::read)
            assertEquals(size, image.width, path)
            assertEquals(size, image.height, path)
        }

        val ico = assertNotNull(javaClass.classLoader.getResourceAsStream("icons/app/icon.ico"))
            .use { it.readBytes() }
        val header = ByteBuffer.wrap(ico, 0, 6).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, header.short.toInt())
        assertEquals(1, header.short.toInt())
        assertTrue(header.short.toInt() >= 6, "ICO должен содержать несколько DPI-размеров")
    }
}
