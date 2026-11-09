package com.github.ahatem.qtranslate.ui.swing.imagetranslation

import com.github.ahatem.qtranslate.core.main.domain.usecase.ImageTranslationFailureKind
import com.github.ahatem.qtranslate.core.main.mvi.MainEvent
import com.github.ahatem.qtranslate.core.main.mvi.MainIntent
import com.github.ahatem.qtranslate.core.main.mvi.MainStore
import com.github.ahatem.qtranslate.ui.swing.shared.util.toImageData
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFrame

/** Управляет только одноразовым переводом кадра и не разделяет состояние с LIVE. */
internal class ImageTranslationController(
    private val owner: JFrame,
    private val mainStore: MainStore,
    private val strings: ImageTranslationStrings,
    private val targetLanguageName: () -> String,
    private val onRecapture: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val sequence = AtomicLong(0)
    private var requestId: Long? = null
    private var sourceImage: BufferedImage? = null
    private var window: ImageTranslationWindow? = null

    fun start(image: BufferedImage) {
        closeWindow(cancelRequest = true)
        sourceImage = copy(image)
        val id = sequence.incrementAndGet()
        requestId = id
        val resultWindow = ImageTranslationWindow(
            owner = owner,
            strings = strings,
            targetLanguageName = targetLanguageName(),
            onRecapture = {
                closeWindow(cancelRequest = true)
                onRecapture()
            },
            onClose = {
                closeWindow(cancelRequest = true)
                onClosed()
            }
        )
        window = resultWindow
        resultWindow.showLoading(checkNotNull(sourceImage))
        mainStore.dispatch(MainIntent.TranslateImageInPlace(image.toImageData("png"), id))
    }

    fun handle(event: MainEvent) {
        when (event) {
            is MainEvent.ImageInPlaceTranslationProgress -> {
                if (event.requestId == requestId) window?.showProgress(event.completed, event.total)
            }
            is MainEvent.ImageInPlaceTranslationCompleted -> {
                if (event.requestId != requestId) return
                val source = sourceImage ?: return
                val rendered = TranslatedImageRenderer.render(source, event.regions)
                window?.showResult(
                    image = rendered,
                    text = event.regions.joinToString("\n") { it.translatedText },
                    failedRegions = event.failedRegions,
                    providers = event.providers
                )
            }
            is MainEvent.ImageInPlaceTranslationFailed -> {
                if (event.requestId != requestId) return
                window?.showFailure(event.kind.message(strings))
            }
            else -> Unit
        }
    }

    fun close() = closeWindow(cancelRequest = true)

    private fun closeWindow(cancelRequest: Boolean) {
        if (cancelRequest && requestId != null) {
            mainStore.dispatch(MainIntent.CancelImageInPlaceTranslation)
        }
        requestId = null
        sourceImage = null
        window?.dispose()
        window = null
    }

    private fun ImageTranslationFailureKind.message(strings: ImageTranslationStrings): String = when (this) {
        ImageTranslationFailureKind.OCR_UNAVAILABLE -> strings.noSpatialOcr
        ImageTranslationFailureKind.OCR_FAILED -> strings.noSpatialOcr
        ImageTranslationFailureKind.NO_TEXT -> strings.noText
        ImageTranslationFailureKind.TRANSLATOR_UNAVAILABLE -> strings.translationFailed
    }

    private fun copy(source: BufferedImage): BufferedImage =
        BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB).also { result ->
            result.createGraphics().apply {
                drawImage(source, 0, 0, null)
                dispose()
            }
        }
}
