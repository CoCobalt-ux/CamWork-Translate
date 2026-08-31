package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.ocr.OCR
import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Локальный OCR macOS без сети и без API-ключа — Vision framework через маленький Swift-помощник,
 * который собирается и подписывается вместе с приложением (см. `packaging/macos`).
 *
 * В отличие от [WindowsOcrService], здесь нет PowerShell под рукой: Vision не вызывается напрямую
 * через JNA, потому что `VNRecognizeTextRequest` — Objective-C/Swift API без C-совместимых точек
 * входа. Помощник получает готовый файл изображения (тот же путь, что и Windows-версия) и печатает
 * распознанные строки в stdout; stdout и stderr не объединяются, поэтому в отличие от Windows
 * маркер-разделитель не нужен.
 */
internal class MacVisionOcrService(private val helperPath: String) : OCR {
    override val key: String = "google-ocr"
    override val name: String = "Vision OCR (локальный)"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        withContext(Dispatchers.IO) {
            if (request.image.bytes.isEmpty()) {
                return@withContext Err(ServiceError.InvalidInputError("Изображение для OCR пустое."))
            }

            val extension = request.image.format
                .lowercase()
                .takeIf { it in SUPPORTED_EXTENSIONS }
                ?: "png"
            val imagePath = Files.createTempFile("camwork-ocr-", ".$extension")
            try {
                Files.write(imagePath, request.image.bytes)
                recognize(
                    imagePath = imagePath.toAbsolutePath().toString(),
                    languageTag = request.language.tag.takeUnless { it == "auto" }
                )
            } finally {
                Files.deleteIfExists(imagePath)
            }
        }

    private fun recognize(imagePath: String, languageTag: String?): Result<OCRResponse, ServiceError> {
        val arguments = buildList {
            add(helperPath)
            add("recognize")
            add(imagePath)
            languageTag?.let(::add)
        }
        val process = ProcessBuilder(arguments).start()

        val standardOutput = ByteArrayOutputStream()
        val standardError = ByteArrayOutputStream()
        val outputReader = Thread(
            { process.inputStream.use { stream -> stream.copyTo(standardOutput) } },
            "camwork-vision-ocr-stdout"
        ).apply { isDaemon = true; start() }
        val errorReader = Thread(
            { process.errorStream.use { stream -> stream.copyTo(standardError) } },
            "camwork-vision-ocr-stderr"
        ).apply { isDaemon = true; start() }

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputReader.join(1_000)
            errorReader.join(1_000)
            return Err(ServiceError.TimeoutError("Локальный Vision OCR не завершился вовремя."))
        }
        outputReader.join(2_000)
        errorReader.join(2_000)

        if (process.exitValue() != 0) {
            val message = String(standardError.toByteArray(), StandardCharsets.UTF_8).trim()
            return Err(
                ServiceError.UnknownError(
                    message.take(MAX_ERROR_LENGTH)
                        .ifBlank { "Локальный Vision OCR завершился с кодом ${process.exitValue()}." }
                )
            )
        }

        val text = String(standardOutput.toByteArray(), StandardCharsets.UTF_8).trim()
        return Ok(OCRResponse(text = text))
    }

    internal companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 15L
        private const val MAX_ERROR_LENGTH = 400
        private const val HELPER_NAME = "camwork-vision-ocr"
        private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "tiff", "gif", "bmp")

        fun isSupported(): Boolean = resolveHelperPath() != null

        /** Создаёт сервис, только если помощник действительно найден рядом с приложением. */
        fun createIfSupported(): MacVisionOcrService? =
            resolveHelperPath()?.let { path -> MacVisionOcrService(path) }

        /**
         * Помощник лежит в `Contents/MacOS` рядом с нативным launcher'ом, который jpackage
         * создаёт при упаковке. `jpackage.app-path` — системное свойство, которое сам launcher
         * выставляет при старте; вне упакованного `.app` (обычный `gradlew run`) его нет, и тогда
         * сервис остаётся недоступен — это единственный контекст, где помощника и не может быть.
         */
        private fun resolveHelperPath(): String? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)) {
                return null
            }
            val launcherPath = System.getProperty("jpackage.app-path") ?: return null
            val helper = File(File(launcherPath).parentFile, HELPER_NAME)
            return helper.absolutePath.takeIf { helper.canExecute() }
        }
    }
}
