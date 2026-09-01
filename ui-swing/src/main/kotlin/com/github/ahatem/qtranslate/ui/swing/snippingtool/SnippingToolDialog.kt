package com.github.ahatem.qtranslate.ui.swing.snippingtool

import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JWindow
import javax.swing.KeyStroke
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Замораживает каждый монитор отдельным окном, чтобы Windows не смешивала DPI
 * нескольких дисплеев внутри одного тяжеловесного Swing-окна.
 */
class SnippingToolDialog(
    private val owner: Frame,
    private val mainStore: MainStore
) {
    private data class ScreenSnapshot(
        val configuration: GraphicsConfiguration,
        val image: BufferedImage
    )

    private data class CaptureSurface(
        val window: JWindow,
        val panel: ScreenCapturePanel,
        val controller: ScreenCaptureController
    )

    private val surfaces = mutableListOf<CaptureSurface>()
    private var closed = false

    init {
        val snapshots = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .screenDevices
            .mapNotNull(::captureScreen)

        snapshots.forEach { snapshot -> surfaces += createSurface(snapshot) }
        surfaces.forEach { surface -> surface.window.isVisible = true }
        focusPointerScreen()
    }

    private fun captureScreen(device: GraphicsDevice): ScreenSnapshot? = runCatching {
        val configuration = device.defaultConfiguration
        ScreenSnapshot(
            configuration = configuration,
            image = Robot(device).createScreenCapture(configuration.bounds)
        )
    }.getOrNull()

    private fun createSurface(snapshot: ScreenSnapshot): CaptureSurface {
        val bounds = snapshot.configuration.bounds
        val window = JWindow(owner, snapshot.configuration).apply {
            isAlwaysOnTop = true
            focusableWindowState = true
            this.bounds = bounds
        }

        lateinit var panel: ScreenCapturePanel
        lateinit var controller: ScreenCaptureController
        val initialState = ScreenCaptureState(screenshot = snapshot.image)

        controller = ScreenCaptureController(initialState) { newState ->
            val buttonsPosition = newState.selection?.let(panel::calculateButtonsPosition)
            val stateWithButtons = newState.copy(buttonsPosition = buttonsPosition)
            panel.render(stateWithButtons)
            controller.updateState(stateWithButtons)
        }

        panel = ScreenCapturePanel(
            onTranslate = ::dispatchOcrAndTranslate,
            onCopyText = ::dispatchOcrAndCopyText,
            onCopyImage = { capturedImage ->
                copyImageToClipboard(capturedImage)
                closeAll()
            },
            onSaveImage = { capturedImage ->
                closeAll()
                saveImageToFile(capturedImage)
            },
            onRecrop = controller::resetToIdle,
            onCancel = ::closeAll
        )

        panel.attachController(controller)
        window.contentPane.add(panel, BorderLayout.CENTER)
        installCloseActions(window, panel)
        panel.render(initialState)
        return CaptureSurface(window, panel, controller)
    }

    private fun installCloseActions(window: JWindow, panel: ScreenCapturePanel) {
        window.rootPane.registerKeyboardAction(
            { closeAll() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        panel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                val state = panel.currentStatePublic ?: return
                val selection = state.selection
                if (state.mode == CaptureMode.SELECTED &&
                    (selection == null || !selection.contains(event.point))
                ) {
                    closeAll()
                }
            }
        })
    }

    private fun focusPointerScreen() {
        val pointer = MouseInfo.getPointerInfo()?.location
        val target = if (pointer == null) {
            surfaces.firstOrNull()
        } else {
            surfaces.firstOrNull { surface -> surface.window.bounds.contains(pointer) }
                ?: surfaces.firstOrNull()
        }
        target?.window?.requestFocus()
    }

    private fun dispatchOcrAndTranslate(image: BufferedImage) {
        mainStore.dispatch(MainIntent.OcrAndTranslateImage(image.toImageData("png")))
        owner.isVisible = true
        owner.state = Frame.NORMAL
        owner.toFront()
        closeAll()
    }

    private fun dispatchOcrAndCopyText(image: BufferedImage) {
        mainStore.dispatch(MainIntent.OcrAndCopyText(image.toImageData("png")))
        closeAll()
    }

    private fun copyImageToClipboard(image: BufferedImage) {
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)

            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
                flavor == DataFlavor.imageFlavor

            @Throws(UnsupportedFlavorException::class)
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
                return image
            }
        }
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
        }
    }

    private fun saveImageToFile(image: BufferedImage) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Save Captured Image"
            fileFilter = FileNameExtensionFilter("PNG Image (*.png)", "png")
            selectedFile = File("screenshot.png")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val selected = chooser.selectedFile
            val file = if (selected.name.endsWith(".png", ignoreCase = true)) {
                selected
            } else {
                File("${selected.absolutePath}.png")
            }
            runCatching { ImageIO.write(image, "PNG", file) }
        }
    }

    private fun closeAll() {
        if (closed) return
        closed = true
        surfaces.forEach { surface -> surface.window.dispose() }
        surfaces.clear()
    }
}
