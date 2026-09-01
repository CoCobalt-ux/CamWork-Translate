package com.github.ahatem.qtranslate.plugins.google

import com.github.ahatem.qtranslate.api.ocr.OCRRequest
import com.github.ahatem.qtranslate.api.ocr.OCRBounds
import com.github.ahatem.qtranslate.api.ocr.OCRResponse
import com.github.ahatem.qtranslate.api.ocr.OCRTextRegion
import com.github.ahatem.qtranslate.api.ocr.LocalOCR
import com.github.ahatem.qtranslate.api.ocr.PositionedOCR
import com.github.ahatem.qtranslate.api.ocr.PositionedOCRResponse
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.fold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Локальный OCR Windows 10/11 без сетевого API и пользовательского ключа. */
internal class WindowsOcrService : LocalOCR, PositionedOCR {
    override val key: String = "google-ocr"
    override val name: String = "Windows OCR (локальный)"
    override val version: String = "1.0.0"
    override val iconPath: String = "assets/google-translate-icon.svg"
    override val supportedLanguages: SupportedLanguages = SupportedLanguages.All

    override suspend fun extractText(request: OCRRequest): Result<OCRResponse, ServiceError> =
        extractPositionedText(request).fold(
            success = { response ->
                Ok(OCRResponse(text = response.regions.joinToString("\n") { region -> region.text }))
            },
            failure = ::Err
        )

    override suspend fun extractPositionedText(
        request: OCRRequest
    ): Result<PositionedOCRResponse, ServiceError> =
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
                recognizePositioned(
                    imagePath = imagePath.toAbsolutePath().toString(),
                    languageTag = request.language.tag.takeUnless { it == "auto" } ?: DEFAULT_OCR_LANGUAGE
                )
            } finally {
                Files.deleteIfExists(imagePath)
            }
        }

    private fun recognizePositioned(
        imagePath: String,
        languageTag: String
    ): Result<PositionedOCRResponse, ServiceError> {
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
        return Ok(PositionedOCRResponse(regions = extractPositionedRegions(rawOutput)))
    }

    internal companion object {
        /** Отделяет распознанный текст от служебного вывода PowerShell. */
        fun extractRecognizedText(rawOutput: String): String {
            val positioned = extractPositionedRegions(rawOutput)
            if (positioned.isNotEmpty()) return positioned.joinToString("\n") { region -> region.text }
            val markerIndex = rawOutput.lastIndexOf(LEGACY_OUTPUT_MARKER)
            if (markerIndex < 0) return ""
            return rawOutput.substring(markerIndex + LEGACY_OUTPUT_MARKER.length).trim()
        }

        /** Разбирает безопасный табличный протокол PowerShell без исполнения JSON или текста OCR. */
        fun extractPositionedRegions(rawOutput: String): List<OCRTextRegion> {
            val markerIndex = rawOutput.lastIndexOf(POSITIONED_OUTPUT_MARKER)
            if (markerIndex < 0) return emptyList()
            return rawOutput.substring(markerIndex + POSITIONED_OUTPUT_MARKER.length)
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .mapNotNull { line ->
                    val parts = line.split('\t', limit = 5)
                    if (parts.size != 5) return@mapNotNull null
                    val x = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val y = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val width = parts[2].toIntOrNull() ?: return@mapNotNull null
                    val height = parts[3].toIntOrNull() ?: return@mapNotNull null
                    if (x < 0 || y < 0 || width <= 0 || height <= 0) return@mapNotNull null
                    val text = runCatching {
                        String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8)
                    }.getOrNull()?.trim().orEmpty()
                    if (text.isBlank()) return@mapNotNull null
                    OCRTextRegion(text, OCRBounds(x, y, width, height))
                }
                .toList()
        }

        private const val POSITIONED_OUTPUT_MARKER = "<<<CAMWORK-OCR-LAYOUT>>>"
        private const val LEGACY_OUTPUT_MARKER = "<<<CAMWORK-OCR>>>"
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
            [Console]::WriteLine('<<<CAMWORK-OCR-LAYOUT>>>')
            foreach (${'$'}line in ${'$'}result.Lines) {
                ${'$'}words = @(${'$'}line.Words)
                if (${'$'}words.Count -eq 0) { continue }
                ${'$'}left = [int][Math]::Floor(((${'$'}words | ForEach-Object { ${'$'}_.BoundingRect.X }) | Measure-Object -Minimum).Minimum)
                ${'$'}top = [int][Math]::Floor(((${'$'}words | ForEach-Object { ${'$'}_.BoundingRect.Y }) | Measure-Object -Minimum).Minimum)
                ${'$'}right = [int][Math]::Ceiling(((${'$'}words | ForEach-Object { ${'$'}_.BoundingRect.X + ${'$'}_.BoundingRect.Width }) | Measure-Object -Maximum).Maximum)
                ${'$'}bottom = [int][Math]::Ceiling(((${'$'}words | ForEach-Object { ${'$'}_.BoundingRect.Y + ${'$'}_.BoundingRect.Height }) | Measure-Object -Maximum).Maximum)
                ${'$'}encodedText = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}line.Text))
                [Console]::WriteLine("${'$'}left`t${'$'}top`t$(${'$'}right - ${'$'}left)`t$(${'$'}bottom - ${'$'}top)`t${'$'}encodedText")
            }
        """.trimIndent()

        private val ENCODED_OCR_SCRIPT: String = Base64.getEncoder().encodeToString(
            WINDOWS_OCR_SCRIPT.toByteArray(StandardCharsets.UTF_16LE)
        )
    }
}
