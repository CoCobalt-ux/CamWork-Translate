# Сборка CamWork Translate для macOS

## Текущий статус

В репозитории подготовлен воспроизводимый нативный контур сборки `.app`, DMG и
опционального PKG. Java Runtime, штатные плагины, языки, темы, лицензии и брендовые
ресурсы включаются в пакет; пользователю не нужно отдельно устанавливать Java.

Нативный пакет сам по себе не означает функциональный паритет с Windows. Перед
выдачей моделям требуется QA на реальных Mac:

| Возможность | Состояние macOS |
|---|---|
| Основное Swing-окно и сетевые переводчики | Платформенно независимы по архитектуре, но нужен smoke-тест на обеих архитектурах. |
| Настраиваемый глобальный hotkey и замена выделения | Код использует `Command+C` на macOS; нужны разрешения Accessibility и Input Monitoring, затем проверка в Chrome/Telegram. |
| Различение опубликованного текста и поля ввода | На macOS/Linux используется переносимый best-effort по clipboard: editable-маркер → `EDITABLE`, HTML → `READ_ONLY`, обычный текст → `UNKNOWN` и безопасная мини-кнопка. Нативного macOS Accessibility detector пока нет, поэтому это не полный AX-паритет Windows. |
| OCR/снимок экрана | Требует разрешения Screen & System Audio Recording и отдельного smoke-теста. |
| Встроенное обновление | Текущий selector знает Windows ZIP и общий переносимый ZIP, но ещё не выбирает нативный macOS DMG. До отдельной доработки обновления устанавливаются вручную. |

То есть сборочный контур готов, но текущий macOS-артефакт следует считать
**QA/beta**, пока не пройдены эти проверки и best-effort-классификация не
подтверждена на целевых Chrome/Telegram и используемых моделями сайтах.

## Почему нужен отдельный macOS launcher

Подписанный `.app` нельзя изменять после code signing. В то же время приложению
нужны изменяемые настройки и обновляемые плагины. Нативный bootstrap из
`packaging/macos/CamWorkBootstrap.c` до запуска JVM:

1. создаёт `~/Library/Application Support/CamWork Translate`;
2. один раз на каждую версию копирует туда штатные плагины, языки, темы и лицензии;
3. передаёт этот путь приложению через `JAVA_TOOL_OPTIONS`;
4. запускает оригинальный launcher, созданный `jpackage`.

Содержимое `.app` после подписи остаётся неизменным. Дополнительные пользовательские
плагины и настройки при обновлении не удаляются.

## Требования

- Mac с актуально поддерживаемой версией macOS;
- JDK 17 с `jlink` и `jpackage`; компиляторный JDK 21 разрешается Gradle toolchain автоматически;
- Xcode Command Line Tools (`xcode-select --install`);
- для публичного релиза — участие компании в Apple Developer Program и сертификат
  `Developer ID Application`;
- для подписанного PKG дополнительно нужен `Developer ID Installer`;
- для notarization — сохранённый в Keychain профиль `notarytool`.

Нативный macOS-пакет нельзя собрать на Windows. `jpackage` создаёт платформенный
пакет только на целевой ОС.

## Архитектуры x64 и arm64

Собираются два независимых артефакта:

- `macos-x64` — Intel Mac; на Apple Silicon возможна сборка x64 JDK под Rosetta 2;
- `macos-arm64` — Apple Silicon.

Сценарий проверяет архитектуру реально запущенного JDK и не позволяет случайно
назвать arm64-сборку x64 или наоборот. Простое объединение двух каталогов `runtime`
через `lipo` не создаёт корректный universal Java bundle, поэтому единый universal
артефакт намеренно не заявлен.

## Локальная QA-сборка

На Mac из корня репозитория:

```bash
export JAVA_HOME="/path/to/jdk-17/Contents/Home"
./scripts/build-macos-release.sh --arch auto --format dmg
```

Без Developer ID сценарий применяет ad-hoc подпись. Она подходит только для
локального QA и не убирает предупреждения Gatekeeper у других пользователей.

Статические инварианты упаковки и синтаксис shell-сценария можно проверить на
Windows до передачи исходников на Mac:

```powershell
.\scripts\verify-packaging.ps1
```

Результаты создаются в `build/release`:

- `CamWork-Translate-VERSION-macos-ARCH.app.zip`;
- `CamWork-Translate-VERSION-macos-ARCH.dmg`;
- отдельный `.sha256` для каждого файла.

Для PKG вместо DMG:

```bash
./scripts/build-macos-release.sh --arch arm64 --format pkg
```

`--format all` создаёт ZIP с `.app`, DMG и PKG.

## Developer ID и notarization

Имена сертификатов и профиль задаются только локально или через секреты CI. В
репозиторий нельзя добавлять `.p12`, пароль, Apple ID app-specific password или
ключ App Store Connect.

Релизный GitHub Actions workflow намеренно не публикует неподписанный DMG. Для него
нужно один раз создать секреты репозитория (ниже перечислены только имена секретов,
без выдуманных значений):

- `MACOS_CERTIFICATE_P12_BASE64` — Developer ID Application в P12, закодированный base64;
- `MACOS_CERTIFICATE_PASSWORD` — пароль P12;
- `MACOS_SIGNING_IDENTITY` — полное имя `Developer ID Application: …`;
- `MACOS_NOTARY_APPLE_ID`, `MACOS_NOTARY_TEAM_ID`, `MACOS_NOTARY_PASSWORD` — Apple ID,
  Team ID и app-specific password для `notarytool`.

Если хотя бы одного секрета нет, macOS job останавливается до публикации. Это защищает
от случайной выдачи моделям ad-hoc сборки, заблокированной Gatekeeper.

Пример переменных без секретных значений:

```bash
export MACOS_SIGNING_IDENTITY="Developer ID Application: COMPANY_NAME (TEAM_ID)"
export MACOS_INSTALLER_IDENTITY="Developer ID Installer: COMPANY_NAME (TEAM_ID)"
export MACOS_NOTARY_PROFILE="camwork-notary"
```

Профиль `camwork-notary` заранее создаётся командой `xcrun notarytool
store-credentials`; реальные учётные данные вводятся локально и сохраняются в
Keychain. После этого DMG можно собрать, подписать, отправить Apple и прикрепить
ticket одной командой:

```bash
./scripts/build-macos-release.sh \
  --arch arm64 \
  --format dmg \
  --notarize
```

Для `--format pkg` или `--format all` при включённой подписи обязательно задаётся
`MACOS_INSTALLER_IDENTITY`.

Сценарий выполняет:

- hardened runtime code signing итогового `.app`;
- проверку `codesign --verify --deep --strict`;
- подпись DMG или PKG;
- `xcrun notarytool submit --wait`;
- `xcrun stapler staple` и `stapler validate`;
- SHA-256 уже после notarization.

Entitlements находятся в `packaging/macos/entitlements.plist`. Исключения JIT,
unsigned executable memory и library validation нужны текущему JVM/JNA-плагинному
стеку. Перед релизом их следует подтвердить тестом notarization и по возможности
сузить при дальнейшем отказе от динамически загружаемых native-компонентов.

## Установка и разрешения на тестовом Mac

1. Открыть DMG и перетащить `CamWork Translate.app` в `Applications`.
2. Запустить приложение.
3. В **System Settings → Privacy & Security** разрешить приложению:
   - **Accessibility** — для вставки перевода в другое приложение;
   - **Input Monitoring** — для глобального hotkey;
   - **Screen & System Audio Recording** — только если используется OCR экрана.
4. Перезапустить приложение после изменения разрешений.
5. Проверить основное окно, Google/Bing/DeepL, двунаправленную замену в Telegram и
   Chrome, поведение выделения в обычном тексте и в поле ввода, TTS и OCR.

## Официальные справочные материалы

- [Oracle: jpackage не поддерживает кросс-сборку](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)
- [Oracle: подпись macOS-пакетов jpackage](https://docs.oracle.com/en/java/javase/17/jpackage/support-application-features.html)
- [Apple: Developer ID](https://developer.apple.com/support/developer-id/)
- [Apple: notarization macOS software](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution)
- [Apple: разрешение Accessibility](https://support.apple.com/guide/mac-help/allow-accessibility-apps-to-access-your-mac-mh43185/mac)
- [Apple: Input Monitoring](https://support.apple.com/guide/mac-help/control-access-to-input-monitoring-on-mac-mchl4cedafb6/mac)
