package com.github.ahatem.qtranslate.ui.swing.imagetranslation

import com.formdev.flatlaf.FlatClientProperties
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

internal data class ImageTranslationStrings(
    val title: String,
    val processing: String,
    val completed: String,
    val partial: String,
    val targetLanguage: String,
    val original: String,
    val translation: String,
    val copyText: String,
    val copyImage: String,
    val savePng: String,
    val recapture: String,
    val close: String,
    val noSpatialOcr: String,
    val noText: String,
    val translationFailed: String
)

/** Отдельное окно результата по схеме «крупный кадр + компактная панель действий». */
internal class ImageTranslationWindow(
    owner: JFrame,
    private val strings: ImageTranslationStrings,
    private val targetLanguageName: String,
    private val onRecapture: () -> Unit,
    private val onClose: () -> Unit
) : JDialog(owner, strings.title, false) {
    private val canvas = ImageCanvas()
    private val statusLabel = JLabel(strings.processing)
    private val progress = JProgressBar()
    private val compareButton = JButton(strings.original)
    private val copyTextButton = JButton(strings.copyText)
    private val copyImageButton = JButton(strings.copyImage)
    private val saveButton = JButton(strings.savePng)
    private var originalImage: BufferedImage? = null
    private var translatedImage: BufferedImage? = null
    private var translatedText: String = ""
    private var showingTranslation = true

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(760, 500)
        preferredSize = Dimension(1120, 720)
        contentPane = JPanel(BorderLayout(14, 14)).apply {
            border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
            add(buildPreview(), BorderLayout.CENTER)
            add(buildActions(), BorderLayout.EAST)
        }
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(event: java.awt.event.WindowEvent?) = onClose()
        })
        pack()
        setLocationRelativeTo(owner)
    }

    fun showLoading(image: BufferedImage) {
        originalImage = image
        translatedImage = null
        translatedText = ""
        canvas.image = image
        statusLabel.text = strings.processing
        progress.isVisible = true
        progress.isIndeterminate = true
        setResultActionsEnabled(false)
        isVisible = true
        toFront()
    }

    fun showProgress(completed: Int, total: Int) {
        progress.isVisible = true
        progress.isIndeterminate = false
        progress.maximum = total.coerceAtLeast(1)
        progress.value = completed.coerceIn(0, progress.maximum)
        statusLabel.text = "${strings.processing} $completed/$total"
    }

    fun showResult(
        image: BufferedImage,
        text: String,
        failedRegions: Int,
        providers: Set<String>
    ) {
        translatedImage = image
        translatedText = text
        showingTranslation = true
        canvas.image = image
        compareButton.text = strings.original
        statusLabel.text = buildString {
            append(if (failedRegions == 0) strings.completed else strings.partial)
            if (providers.isNotEmpty()) append(" · ${providers.joinToString()}")
        }
        progress.isVisible = false
        setResultActionsEnabled(true)
    }

    fun showFailure(message: String) {
        statusLabel.text = message
        progress.isVisible = false
        compareButton.isEnabled = false
        copyTextButton.isEnabled = false
        copyImageButton.isEnabled = originalImage != null
        saveButton.isEnabled = originalImage != null
    }

    private fun buildPreview() = JPanel(BorderLayout(0, 8)).apply {
        add(JPanel(BorderLayout(8, 0)).apply {
            add(statusLabel.apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
            add(progress.apply { preferredSize = Dimension(180, 6) }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JScrollPane(canvas).apply {
            border = BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor") ?: Color(70, 90, 94)
            )
        }, BorderLayout.CENTER)
    }

    private fun buildActions() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        preferredSize = Dimension(230, 1)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") ?: Color(70, 90, 94)),
            BorderFactory.createEmptyBorder(16, 14, 16, 14)
        )
        add(JLabel(strings.targetLanguage).apply { font = font.deriveFont(Font.BOLD) })
        add(Box.createVerticalStrut(6))
        add(JLabel(targetLanguageName).apply {
            horizontalAlignment = SwingConstants.LEFT
            putClientProperty(FlatClientProperties.STYLE, "font: +2")
        })
        add(Box.createVerticalStrut(18))
        add(fullWidth(compareButton.apply { addActionListener { toggleComparison() } }))
        add(Box.createVerticalStrut(8))
        add(fullWidth(copyTextButton.apply { addActionListener { copyText() } }))
        add(Box.createVerticalStrut(8))
        add(fullWidth(copyImageButton.apply { addActionListener { copyImage() } }))
        add(Box.createVerticalStrut(8))
        add(fullWidth(saveButton.apply { addActionListener { saveImage() } }))
        add(Box.createVerticalGlue())
        add(fullWidth(JButton(strings.recapture).apply { addActionListener { onRecapture() } }))
        add(Box.createVerticalStrut(8))
        add(fullWidth(JButton(strings.close).apply { addActionListener { onClose() } }))
    }

    private fun fullWidth(button: JButton): JButton = button.apply {
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(34))
    }

    private fun toggleComparison() {
        val original = originalImage ?: return
        val translated = translatedImage ?: return
        showingTranslation = !showingTranslation
        canvas.image = if (showingTranslation) translated else original
        compareButton.text = if (showingTranslation) strings.original else strings.translation
    }

    private fun setResultActionsEnabled(enabled: Boolean) {
        compareButton.isEnabled = enabled
        copyTextButton.isEnabled = enabled
        copyImageButton.isEnabled = enabled
        saveButton.isEnabled = enabled
    }

    private fun copyText() {
        if (translatedText.isBlank()) return
        toolkit.systemClipboard.setContents(StringSelection(translatedText), null)
    }

    private fun copyImage() {
        val image = translatedImage ?: originalImage ?: return
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (!isDataFlavorSupported(flavor)) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                return image
            }
        }
        toolkit.systemClipboard.setContents(transferable, null)
    }

    private fun saveImage() {
        val image = translatedImage ?: originalImage ?: return
        val chooser = JFileChooser().apply {
            dialogTitle = strings.savePng
            fileFilter = FileNameExtensionFilter("PNG (*.png)", "png")
            selectedFile = File("camwork-translated.png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val selected = chooser.selectedFile
        val output = if (selected.name.endsWith(".png", ignoreCase = true)) {
            selected
        } else {
            File("${selected.absolutePath}.png")
        }
        ImageIO.write(image, "png", output)
    }

    private class ImageCanvas : JPanel() {
        var image: BufferedImage? = null
            set(value) {
                field = value
                revalidate()
                repaint()
            }

        init {
            background = Color(11, 19, 22)
            preferredSize = Dimension(760, 560)
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            val source = image ?: return
            val scale = minOf(
                width.toDouble() / source.width,
                height.toDouble() / source.height,
                1.0
            ).coerceAtLeast(0.01)
            val drawWidth = (source.width * scale).toInt()
            val drawHeight = (source.height * scale).toInt()
            val x = (width - drawWidth) / 2
            val y = (height - drawHeight) / 2
            (graphics as Graphics2D).apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source, x, y, drawWidth, drawHeight, null)
            }
        }
    }
}
