// Локальный OCR для macOS без сети и без API-ключа — Vision framework, встроенный в систему.
//
// Собирается clang-тулчейном Xcode Command Line Tools (`swiftc`) вместе с остальной macOS-сборкой
// и кладётся рядом с нативным launcher'ом в Contents/MacOS. Не требует Screen Recording — работает
// с уже готовым файлом изображения, который приложение получает из своего обычного снимка области
// экрана (Robot/ScreenCapturePanel), точно так же, как WindowsOcrService получает готовый файл на
// стороне Windows.
//
// Использование:
//   camwork-vision-ocr recognize <путь к изображению> [языковой тег BCP-47 или "auto"]
//   camwork-vision-ocr selftest
//
// Обычный вывод — распознанные строки, по одной на строку stdout. Диагностика и ошибки — только
// в stderr, поэтому Kotlin-стороне не нужен маркер-разделитель, который потребовался для
// PowerShell-скрипта на Windows (там stdout и stderr объединены самим ProcessBuilder).

import CoreGraphics
import CoreText
import Foundation
import ImageIO
import Vision

func fail(_ message: String, code: Int32 = 1) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

@available(macOS 10.15, *)
func loadImage(path: String) -> CGImage? {
    let url = URL(fileURLWithPath: path)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(source, 0, nil)
}

@available(macOS 10.15, *)
func recognizeText(cgImage: CGImage, languageTag: String?) -> Result<[String], String> {
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.usesLanguageCorrection = true

    if let tag = languageTag, tag != "auto", !tag.isEmpty {
        request.recognitionLanguages = [tag]
    }

    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    do {
        try handler.perform([request])
    } catch {
        // Помимо настоящих сбоев Vision сюда попадает и неподдержанный языковой тег — Kotlin-
        // сторона превращает это в обычную ServiceError, а не в падение процесса.
        return .failure("Vision request failed: \(error.localizedDescription)")
    }

    let lines = (request.results ?? []).compactMap { observation -> String? in
        observation.topCandidates(1).first?.string
    }
    return .success(lines)
}

/// Рисует известную строку в offscreen-битмап и прогоняет её через тот же путь распознавания,
/// что и обычный запрос. Позволяет CI проверить настоящий Vision-пайплайн на реальном железе
/// (Intel и Apple Silicon), а не только факт компиляции.
@available(macOS 10.15, *)
func renderSelfTestImage() -> CGImage? {
    let width = 520
    let height = 140
    guard let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { return nil }

    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    context.fill(CGRect(x: 0, y: 0, width: width, height: height))
    context.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))

    let font = CTFontCreateWithName("Helvetica-Bold" as CFString, 34, nil)
    let attributedString = CFAttributedStringCreate(
        nil,
        "CAMWORK OCR TEST" as CFString,
        [kCTFontAttributeName: font] as CFDictionary
    )
    guard let attributedString else { return nil }
    let line = CTLineCreateWithAttributedString(attributedString)
    context.textPosition = CGPoint(x: 16, y: 60)
    CTLineDraw(line, context)

    return context.makeImage()
}

@available(macOS 10.15, *)
func runSelfTest() {
    guard let image = renderSelfTestImage() else {
        fail("selftest: не удалось отрендерить тестовое изображение")
    }
    switch recognizeText(cgImage: image, languageTag: "en-US") {
    case .success(let lines):
        let combined = lines.joined(separator: " ").uppercased()
        if combined.contains("CAMWORK") {
            print("SELFTEST_OK: \(lines.joined(separator: " | "))")
            exit(0)
        } else {
            fail("selftest: распознанный текст не содержит ожидаемого слова: '\(combined)'")
        }
    case .failure(let message):
        fail("selftest: \(message)")
    }
}

@available(macOS 10.15, *)
func runRecognize(imagePath: String, languageTag: String?) {
    guard let image = loadImage(path: imagePath) else {
        fail("Не удалось декодировать изображение: \(imagePath)")
    }
    switch recognizeText(cgImage: image, languageTag: languageTag) {
    case .success(let lines):
        lines.forEach { print($0) }
        exit(0)
    case .failure(let message):
        fail(message)
    }
}

guard #available(macOS 10.15, *) else {
    fail("Локальный OCR требует macOS 10.15 (Catalina) или новее.", code: 2)
}

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    fail("Использование: camwork-vision-ocr recognize <изображение> [язык] | camwork-vision-ocr selftest")
}

switch arguments[1] {
case "selftest":
    runSelfTest()
case "recognize":
    guard arguments.count >= 3 else { fail("recognize требует путь к изображению") }
    let languageTag = arguments.count >= 4 ? arguments[3] : nil
    runRecognize(imagePath: arguments[2], languageTag: languageTag)
default:
    fail("Неизвестный режим: \(arguments[1])")
}
