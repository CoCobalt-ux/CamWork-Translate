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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Локальный OCR Windows 10/11 без сетевого API и пользовательского ключа. */
internal class WindowsOcrService : OCR {
    override val key: String = "google-ocr"
    override val name: String = "Windows OCR (локальный)"
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
                    languageTag = request.language.tag.takeUnless { it == "auto" } ?: DEFAULT_OCR_LANGUAGE
                )
            } finally {
                Files.deleteIfExists(imagePath)
            }
        }

    private fun recognize(
        imagePath: String,
        languageTag: String
    ): Result<OCRResponse, ServiceError> {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-EncodedCommand",
            ENCODED_OCR_SCRIPT
        )
            .redirectErrorStream(true)
            .apply {
                environment()[IMAGE_PATH_ENV] = imagePath
                environment()[LANGUAGE_ENV] = languageTag
            }
            .start()

        val output = ByteArrayOutputStream()
        val outputReader = Thread(
            { process.inputStream.use { stream -> stream.copyTo(output) } },
            "camwork-windows-ocr-output"
        ).apply {
            isDaemon = true
            start()
        }

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputReader.join(1_000)
            return Err(ServiceError.TimeoutError("Локальный Windows OCR не завершился вовремя."))
        }
        outputReader.join(2_000)

        val rawOutput = String(output.toByteArray(), StandardCharsets.UTF_8)
        if (process.exitValue() != 0) {
            return Err(
                ServiceError.UnknownError(
                    rawOutput.trim().take(MAX_ERROR_LENGTH)
                        .ifBlank { "Локальный Windows OCR завершился с кодом ${process.exitValue()}." }
                )
            )
        }
        // stdout и stderr объединены, поэтому распознанным считается только текст после маркера:
        // предупреждение PowerShell не должно попасть в перевод.
        return Ok(OCRResponse(text = extractRecognizedText(rawOutput)))
    }

    internal companion object {
        /** Отделяет распознанный текст от служебного вывода PowerShell. */
        fun extractRecognizedText(rawOutput: String): String {
            val markerIndex = rawOutput.lastIndexOf(OUTPUT_MARKER)
            if (markerIndex < 0) return ""
            return rawOutput.substring(markerIndex + OUTPUT_MARKER.length).trim()
        }

        private const val OUTPUT_MARKER = "<<<CAMWORK-OCR>>>"
        private const val IMAGE_PATH_ENV = "CAMWORK_OCR_IMAGE"
        private const val LANGUAGE_ENV = "CAMWORK_OCR_LANGUAGE"
        private const val DEFAULT_OCR_LANGUAGE = "en-US"
        private const val PROCESS_TIMEOUT_SECONDS = 20L
        private const val MAX_ERROR_LENGTH = 400
        private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff")

        fun isSupported(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        private val WINDOWS_OCR_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}ProgressPreference = 'SilentlyContinue'
            [Console]::OutputEncoding = [Text.UTF8Encoding]::new(${'$'}false)
            Add-Type -AssemblyName System.Runtime.WindowsRuntime
            [void][Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
            [void][Windows.Storage.FileAccessMode, Windows.Storage, ContentType=WindowsRuntime]
            [void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
            [void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
            [void][Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime]

            ${'$'}asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
                Where-Object {
                    ${'$'}_.Name -eq 'AsTask' -and
                    ${'$'}_.GetGenericArguments().Count -eq 1 -and
                    ${'$'}_.GetParameters().Count -eq 1 -and
                    ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
                } |
                Select-Object -First 1
            if (${'$'}null -eq ${'$'}asTask) { throw 'WinRT AsTask недоступен в этой версии PowerShell.' }

            function Await(${'$'}operation, [Type]${'$'}resultType) {
                ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}resultType).Invoke(${'$'}null, @(${'$'}operation))
                ${'$'}task.Wait()
                return ${'$'}task.Result
            }

            ${'$'}file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync(${'$'}env:CAMWORK_OCR_IMAGE)) ([Windows.Storage.StorageFile])
            ${'$'}stream = Await (${'$'}file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
            ${'$'}decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync(${'$'}stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
            ${'$'}bitmap = Await (${'$'}decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
            ${'$'}language = [Windows.Globalization.Language]::new(${'$'}env:CAMWORK_OCR_LANGUAGE)
            ${'$'}engine = if ([Windows.Media.Ocr.OcrEngine]::IsLanguageSupported(${'$'}language)) {
                [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage(${'$'}language)
            } else {
                [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
            }
            if (${'$'}null -eq ${'$'}engine) { throw 'В Windows не установлен пакет языка для OCR.' }
            ${'$'}result = Await (${'$'}engine.RecognizeAsync(${'$'}bitmap)) ([Windows.Media.Ocr.OcrResult])
            # OcrResult.Text склеивает весь снимок в одну строку и ломает структуру чата,
            # поэтому строки собираются вручную.
            ${'$'}lines = @(${'$'}result.Lines | ForEach-Object { ${'$'}_.Text })
            [Console]::Write('<<<CAMWORK-OCR>>>')
            [Console]::Write((${'$'}lines -join [char]10))
        """.trimIndent()

        private val ENCODED_OCR_SCRIPT: String = Base64.getEncoder().encodeToString(
            WINDOWS_OCR_SCRIPT.toByteArray(StandardCharsets.UTF_16LE)
        )
    }
}
