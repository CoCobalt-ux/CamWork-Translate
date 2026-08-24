#!/usr/bin/env bash

set -Eeuo pipefail
IFS=$'\n\t'

usage() {
    cat <<'EOF'
Сборка CamWork Translate для macOS (запускается только на macOS).

Использование:
  ./scripts/build-macos-release.sh [параметры]

Параметры:
  --version VERSION              Версия релиза; по умолчанию берётся из AppConstants.kt.
  --java-home PATH               JDK 17 с jlink/jpackage; по умолчанию JAVA_HOME.
  --arch auto|x64|arm64          Архитектура JDK и результата; по умолчанию auto.
  --format app|dmg|pkg|all       Контейнеры; app всегда создаёт также ZIP с .app.
  --signing-identity NAME        Developer ID Application; либо MACOS_SIGNING_IDENTITY.
  --installer-identity NAME      Developer ID Installer для PKG; либо MACOS_INSTALLER_IDENTITY.
  --signing-keychain PATH        Необязательный keychain; либо MACOS_SIGNING_KEYCHAIN.
  --notary-profile NAME          Профиль notarytool в Keychain; либо MACOS_NOTARY_PROFILE.
  --notarize                     Отправить созданные DMG/PKG на notarization и прикрепить ticket.
  --help                         Показать справку.

Пример локальной сборки без Developer ID:
  ./scripts/build-macos-release.sh --format dmg

Пример подписанного релиза:
  MACOS_SIGNING_IDENTITY='Developer ID Application: COMPANY (TEAMID)' \
  MACOS_NOTARY_PROFILE='camwork-notary' \
  ./scripts/build-macos-release.sh --format dmg --notarize
EOF
}

fail() {
    printf 'Ошибка: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "не найдена команда: $1"
}

safe_clean_directory() {
    local target="$1"
    case "$target" in
        "$BUILD_ROOT"/*) rm -rf -- "$target" ;;
        *) fail "отказ от очистки пути вне build: $target" ;;
    esac
}

write_sha256() {
    local artifact="$1"
    local checksum_file="${artifact}.sha256"
    local hash
    hash="$(shasum -a 256 "$artifact" | awk '{print $1}')"
    printf '%s  %s\n' "$hash" "$(basename "$artifact")" > "$checksum_file"
}

render_icon() {
    local size="$1"
    local name="$2"
    sips --resampleHeightWidth "$size" "$size" "$ICON_SOURCE" \
        --out "$ICONSET_DIRECTORY/$name" >/dev/null
}

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIRECTORY/.." && pwd -P)"
BUILD_ROOT="$REPO_ROOT/build"
RELEASE_DIRECTORY="$BUILD_ROOT/release"

VERSION=""
JAVA_HOME_INPUT="${JAVA_HOME:-}"
REQUESTED_ARCH="auto"
PACKAGE_FORMAT="dmg"
SIGNING_IDENTITY="${MACOS_SIGNING_IDENTITY:-}"
INSTALLER_IDENTITY="${MACOS_INSTALLER_IDENTITY:-}"
SIGNING_KEYCHAIN="${MACOS_SIGNING_KEYCHAIN:-}"
NOTARY_PROFILE="${MACOS_NOTARY_PROFILE:-}"
NOTARIZE=false

while (($# > 0)); do
    case "$1" in
        --version)
            (($# >= 2)) || fail "для --version требуется значение"
            VERSION="$2"
            shift 2
            ;;
        --java-home)
            (($# >= 2)) || fail "для --java-home требуется значение"
            JAVA_HOME_INPUT="$2"
            shift 2
            ;;
        --arch)
            (($# >= 2)) || fail "для --arch требуется значение"
            REQUESTED_ARCH="$2"
            shift 2
            ;;
        --format)
            (($# >= 2)) || fail "для --format требуется значение"
            PACKAGE_FORMAT="$2"
            shift 2
            ;;
        --signing-identity)
            (($# >= 2)) || fail "для --signing-identity требуется значение"
            SIGNING_IDENTITY="$2"
            shift 2
            ;;
        --installer-identity)
            (($# >= 2)) || fail "для --installer-identity требуется значение"
            INSTALLER_IDENTITY="$2"
            shift 2
            ;;
        --signing-keychain)
            (($# >= 2)) || fail "для --signing-keychain требуется значение"
            SIGNING_KEYCHAIN="$2"
            shift 2
            ;;
        --notary-profile)
            (($# >= 2)) || fail "для --notary-profile требуется значение"
            NOTARY_PROFILE="$2"
            shift 2
            ;;
        --notarize)
            NOTARIZE=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "неизвестный параметр: $1"
            ;;
    esac
done

[[ "$(uname -s)" == "Darwin" ]] || fail \
    "нативные .app/.dmg/.pkg собираются только на macOS; кросс-сборка с Windows не поддерживается"
[[ "$REQUESTED_ARCH" =~ ^(auto|x64|arm64)$ ]] || fail \
    "--arch должен быть auto, x64 или arm64"
[[ "$PACKAGE_FORMAT" =~ ^(app|dmg|pkg|all)$ ]] || fail \
    "--format должен быть app, dmg, pkg или all"

APP_CONSTANTS="$REPO_ROOT/core/src/main/kotlin/com/github/ahatem/qtranslate/core/shared/AppConstants.kt"
[[ -f "$APP_CONSTANTS" ]] || fail "не найден AppConstants.kt"
EMBEDDED_VERSION="$(sed -nE 's/.*const[[:space:]]+val[[:space:]]+APP_VERSION[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$APP_CONSTANTS" | head -n 1)"
[[ -n "$EMBEDDED_VERSION" ]] || fail "не удалось прочитать APP_VERSION"

if [[ -z "$VERSION" ]]; then
    VERSION="$EMBEDDED_VERSION"
fi
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] || fail \
    "версия должна соответствовать SemVer: $VERSION"
PACKAGE_VERSION="${VERSION%%[-+]*}"
if [[ "$EMBEDDED_VERSION" != "$VERSION" && "$EMBEDDED_VERSION" != "$PACKAGE_VERSION" ]]; then
    fail "версия сборки $VERSION не совпадает с APP_VERSION $EMBEDDED_VERSION"
fi

[[ -n "$JAVA_HOME_INPUT" ]] || fail "задайте JAVA_HOME или используйте --java-home"
JAVA_HOME_FULL="$(cd -- "$JAVA_HOME_INPUT" 2>/dev/null && pwd -P)" || fail \
    "JDK не найден: $JAVA_HOME_INPUT"
JAVA="$JAVA_HOME_FULL/bin/java"
JLINK="$JAVA_HOME_FULL/bin/jlink"
JPACKAGE="$JAVA_HOME_FULL/bin/jpackage"
for executable in "$JAVA" "$JLINK" "$JPACKAGE"; do
    [[ -x "$executable" ]] || fail "не найден исполняемый файл JDK: $executable"
done

JAVA_MAJOR="$("$JAVA" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n 1)"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || fail "не удалось определить версию JDK"
((JAVA_MAJOR == 17)) || fail "для воспроизводимой упаковки нужен JDK 17, найден JDK $JAVA_MAJOR"

JAVA_ARCH_RAW="$("$JAVA" -XshowSettings:properties -version 2>&1 | \
    sed -nE 's/^[[:space:]]*os\.arch[[:space:]]*=[[:space:]]*([^[:space:]]+).*/\1/p' | head -n 1)"
case "$JAVA_ARCH_RAW" in
    aarch64|arm64) DETECTED_ARCH="arm64" ;;
    amd64|x86_64) DETECTED_ARCH="x64" ;;
    *) fail "неподдерживаемая архитектура JDK: $JAVA_ARCH_RAW" ;;
esac
if [[ "$REQUESTED_ARCH" != "auto" && "$REQUESTED_ARCH" != "$DETECTED_ARCH" ]]; then
    fail "запрошена $REQUESTED_ARCH, но JDK имеет архитектуру $DETECTED_ARCH"
fi
ARCHITECTURE="$DETECTED_ARCH"

# Без явного deployment target clang берёт версию системы, на которой идёт сборка. На раннере
# GitHub это macOS 15, поэтому launcher отказывался стартовать на любом Mac постарше, а Finder
# сообщал, что нужна более новая ОС. Планка выбрана по минимуму, который поддерживает JDK 17:
# для Apple Silicon это Big Sur (первая ОС для этих машин), для Intel — High Sierra.
if [[ "$ARCHITECTURE" == "arm64" ]]; then
    MACOS_MINIMUM_VERSION="11.0"
else
    MACOS_MINIMUM_VERSION="10.13"
fi

require_command awk
require_command clang
require_command codesign
require_command ditto
require_command file
require_command hdiutil
require_command iconutil
require_command otool
require_command plutil
require_command shasum
require_command sips
require_command xcode-select
xcode-select -p >/dev/null 2>&1 || fail "установите Xcode Command Line Tools"

if [[ "$PACKAGE_FORMAT" == "pkg" || "$PACKAGE_FORMAT" == "all" ]]; then
    require_command productbuild
    require_command pkgutil
fi
if [[ "$NOTARIZE" == true ]]; then
    [[ -n "$SIGNING_IDENTITY" ]] || fail "для notarization нужен Developer ID Application"
    [[ -n "$NOTARY_PROFILE" ]] || fail "для notarization нужен --notary-profile"
    [[ "$PACKAGE_FORMAT" != "app" ]] || fail "notarization требует контейнер DMG или PKG"
    require_command xcrun
fi
if [[ -n "$SIGNING_IDENTITY" && ( "$PACKAGE_FORMAT" == "pkg" || "$PACKAGE_FORMAT" == "all" ) ]]; then
    [[ -n "$INSTALLER_IDENTITY" ]] || fail \
        "для подписанного PKG нужен Developer ID Installer"
fi

GRADLE_WRAPPER="$REPO_ROOT/gradlew"
[[ -x "$GRADLE_WRAPPER" ]] || fail "Gradle Wrapper не найден или не исполняемый: $GRADLE_WRAPPER"
BOOTSTRAP_SOURCE="$REPO_ROOT/packaging/macos/CamWorkBootstrap.c"
ENTITLEMENTS="$REPO_ROOT/packaging/macos/entitlements.plist"
ICON_SOURCE="$REPO_ROOT/ui-swing/src/main/resources/icons/app/icon-1024.png"
for required_file in "$BOOTSTRAP_SOURCE" "$ENTITLEMENTS" "$ICON_SOURCE" \
    "$REPO_ROOT/LICENSE" "$REPO_ROOT/NOTICE" "$REPO_ROOT/docs/LEGAL_ATTRIBUTION.md"; do
    [[ -f "$required_file" ]] || fail "не найден обязательный файл: $required_file"
done
plutil -lint "$ENTITLEMENTS" >/dev/null

WORK_ROOT="$BUILD_ROOT/macos-package-work-$ARCHITECTURE"
PORTABLE_DIRECTORY="$WORK_ROOT/portable"
INPUT_DIRECTORY="$WORK_ROOT/input"
RUNTIME_DIRECTORY="$WORK_ROOT/runtime"
APP_OUTPUT_DIRECTORY="$WORK_ROOT/app-output"
ICONSET_DIRECTORY="$WORK_ROOT/CamWorkTranslate.iconset"
ICON_PATH="$WORK_ROOT/CamWorkTranslate.icns"
DMG_STAGE_DIRECTORY="$WORK_ROOT/dmg-stage"
mkdir -p "$BUILD_ROOT" "$RELEASE_DIRECTORY"
safe_clean_directory "$WORK_ROOT"
mkdir -p "$PORTABLE_DIRECTORY" "$INPUT_DIRECTORY" "$APP_OUTPUT_DIRECTORY" "$ICONSET_DIRECTORY"

export JAVA_HOME="$JAVA_HOME_FULL"
export PATH="$JAVA_HOME_FULL/bin:$PATH"

printf 'Сборка переносимого дистрибутива %s...\n' "$VERSION"
(
    cd "$REPO_ROOT"
    "$GRADLE_WRAPPER" assemblePortable \
        "-PreleaseVersion=$VERSION" \
        "-Dorg.gradle.java.installations.paths=$JAVA_HOME_FULL" \
        --no-daemon
)

PORTABLE_ARCHIVE="$RELEASE_DIRECTORY/CamWork-Translate-$VERSION.zip"
[[ -f "$PORTABLE_ARCHIVE" ]] || fail "Gradle не создал $PORTABLE_ARCHIVE"
ditto -x -k "$PORTABLE_ARCHIVE" "$PORTABLE_DIRECTORY"
PORTABLE_ROOT="$PORTABLE_DIRECTORY/CamWork Translate"
PORTABLE_JAR="$PORTABLE_ROOT/CamWork-Translate.jar"
[[ -f "$PORTABLE_JAR" ]] || fail "в переносимом архиве отсутствует CamWork-Translate.jar"
ditto "$PORTABLE_JAR" "$INPUT_DIRECTORY/CamWork-Translate.jar"

# Штатные ресурсы намеренно собираются отдельно от --input и попадают в бандл уже после jpackage.
# jpackage добавляет в app.classpath каждый JAR, найденный в каталоге --input, включая вложенные.
# Плагины оказывались на classpath самого приложения, и тогда plugin.json первого из них отвечал
# на запрос манифеста для всех остальных: каждый плагин получал чужой идентификатор, после чего
# реестр отклонял их все как дубликаты.
DEFAULTS_STAGE_DIRECTORY="$WORK_ROOT/default-data"
safe_clean_directory "$DEFAULTS_STAGE_DIRECTORY"
mkdir -p "$DEFAULTS_STAGE_DIRECTORY"
for item in "$PORTABLE_ROOT"/*; do
    [[ "$(basename "$item")" == "CamWork-Translate.jar" ]] && continue
    ditto "$item" "$DEFAULTS_STAGE_DIRECTORY/$(basename "$item")"
done
[[ -d "$DEFAULTS_STAGE_DIRECTORY/plugins" ]] || fail "штатные плагины не попали в комплект"

MODULES="java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.xml.crypto,jdk.charsets,jdk.crypto.ec,jdk.localedata,jdk.unsupported,jdk.zipfs"
"$JLINK" \
    --add-modules "$MODULES" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --strip-native-commands \
    --compress=2 \
    --output "$RUNTIME_DIRECTORY"

render_icon 16 icon_16x16.png
render_icon 32 icon_16x16@2x.png
render_icon 32 icon_32x32.png
render_icon 64 icon_32x32@2x.png
render_icon 128 icon_128x128.png
render_icon 256 icon_128x128@2x.png
render_icon 256 icon_256x256.png
render_icon 512 icon_256x256@2x.png
render_icon 512 icon_512x512.png
render_icon 1024 icon_512x512@2x.png
iconutil -c icns "$ICONSET_DIRECTORY" -o "$ICON_PATH"

COPYRIGHT_TEXT="Copyright © 2026 ITWORKSYSTEMS LTD / CamWork — modifications; QTranslate © 2023 Ahmed Hatem (MIT)"
"$JPACKAGE" \
    --type app-image \
    --name "CamWork Translate" \
    --input "$INPUT_DIRECTORY" \
    --main-jar "CamWork-Translate.jar" \
    --main-class "com.github.ahatem.qtranslate.app.MainKt" \
    --app-version "$PACKAGE_VERSION" \
    --vendor "ITWORKSYSTEMS LTD / CamWork" \
    --description "Быстрый настольный переводчик CamWork" \
    --copyright "$COPYRIGHT_TEXT" \
    --icon "$ICON_PATH" \
    --runtime-image "$RUNTIME_DIRECTORY" \
    --dest "$APP_OUTPUT_DIRECTORY" \
    --java-options "-Dfile.encoding=UTF-8" \
    --java-options "-Dapple.awt.application.appearance=system" \
    --mac-package-identifier "club.camwork.translate" \
    --mac-package-name "CamWork" \
    --mac-app-category "public.app-category.utilities"

APP_IMAGE="$APP_OUTPUT_DIRECTORY/CamWork Translate.app"
MACOS_DIRECTORY="$APP_IMAGE/Contents/MacOS"
JPACKAGE_LAUNCHER="$MACOS_DIRECTORY/CamWork Translate"
ORIGINAL_LAUNCHER="$MACOS_DIRECTORY/CamWork Translate.bin"
JPACKAGE_CONFIGURATION="$APP_IMAGE/Contents/app/CamWork Translate.cfg"
ORIGINAL_CONFIGURATION="$APP_IMAGE/Contents/app/CamWork Translate.bin.cfg"
[[ -x "$JPACKAGE_LAUNCHER" ]] || fail "jpackage не создал нативный launcher"
[[ -f "$JPACKAGE_CONFIGURATION" ]] || fail "jpackage не создал конфигурацию launcher"
mv -- "$JPACKAGE_LAUNCHER" "$ORIGINAL_LAUNCHER"
# jpackage ищет .cfg по фактическому имени исполняемого файла. После переименования
# оригинального launcher его конфигурация должна получить то же базовое имя.
mv -- "$JPACKAGE_CONFIGURATION" "$ORIGINAL_CONFIGURATION"
clang \
    -std=c11 \
    -Wall \
    -Wextra \
    -Werror \
    -O2 \
    "-mmacosx-version-min=$MACOS_MINIMUM_VERSION" \
    "-DCAMWORK_VERSION=\"$VERSION\"" \
    "$BOOTSTRAP_SOURCE" \
    -framework ApplicationServices \
    -o "$JPACKAGE_LAUNCHER"
chmod 0755 "$JPACKAGE_LAUNCHER" "$ORIGINAL_LAUNCHER"

# Уже после того, как jpackage составил app.classpath, — поэтому плагины в него не попадают.
BUNDLED_DEFAULTS="$APP_IMAGE/Contents/Resources/default-data"
mkdir -p "$BUNDLED_DEFAULTS"
ditto "$DEFAULTS_STAGE_DIRECTORY" "$BUNDLED_DEFAULTS"
[[ -d "$BUNDLED_DEFAULTS/plugins" ]] || fail "штатные плагины не попали в бандл"

# Тот самый отказ, ради предотвращения которого ресурсы и вынесены из --input: JAR плагина на
# classpath приложения ломает определение идентификатора у всех плагинов сразу.
if grep -q "app.classpath.*plugins/" "$ORIGINAL_CONFIGURATION"; then
    fail "jpackage поместил плагины в app.classpath — они снова окажутся видимы загрузчику приложения"
fi

LEGAL_DIRECTORY="$APP_IMAGE/Contents/Resources/legal"
mkdir -p "$LEGAL_DIRECTORY"
ditto "$REPO_ROOT/LICENSE" "$LEGAL_DIRECTORY/LICENSE"
ditto "$REPO_ROOT/NOTICE" "$LEGAL_DIRECTORY/NOTICE"
ditto "$REPO_ROOT/docs/LEGAL_ATTRIBUTION.md" "$LEGAL_DIRECTORY/LEGAL_ATTRIBUTION.md"
if [[ -d "$REPO_ROOT/THIRD_PARTY_LICENSES" ]]; then
    ditto "$REPO_ROOT/THIRD_PARTY_LICENSES" "$LEGAL_DIRECTORY/THIRD_PARTY_LICENSES"
fi

INFO_PLIST="$APP_IMAGE/Contents/Info.plist"
plutil -lint "$INFO_PLIST" >/dev/null
[[ "$(plutil -extract CFBundleIdentifier raw "$INFO_PLIST")" == "club.camwork.translate" ]] || fail \
    "jpackage создал неверный CFBundleIdentifier"

# LaunchServices решает по этому ключу, показать ли «требуется более новая версия macOS»,
# независимо от того, что записано в самом бинарнике. Заполняется до подписи: правка plist
# после codesign сломала бы её.
plutil -replace LSMinimumSystemVersion -string "$MACOS_MINIMUM_VERSION" "$INFO_PLIST" 2>/dev/null ||
    plutil -insert LSMinimumSystemVersion -string "$MACOS_MINIMUM_VERSION" "$INFO_PLIST"

# Проверка того, что действительно попало в launcher: если планка снова уедет к версии раннера,
# сборка должна падать здесь, а не у пользователя со старым Mac.
# Планка записывается как minos в LC_BUILD_VERSION либо как version в LC_VERSION_MIN_MACOSX —
# какой из них выберет линковщик, зависит от самой версии, поэтому читаются оба.
LAUNCHER_MINIMUM_VERSION="$(otool -l "$JPACKAGE_LAUNCHER" | awk '
    /LC_BUILD_VERSION|LC_VERSION_MIN_MACOSX/ { found = 1; next }
    found && ($1 == "minos" || $1 == "version") { print $2; exit }
')"
[[ "$LAUNCHER_MINIMUM_VERSION" == "$MACOS_MINIMUM_VERSION" ]] || fail \
    "launcher требует macOS $LAUNCHER_MINIMUM_VERSION вместо $MACOS_MINIMUM_VERSION"

if [[ -n "$SIGNING_IDENTITY" ]]; then
    printf 'Подпись .app сертификатом Developer ID...\n'
    CODESIGN_ARGUMENTS=(
        --force
        --deep
        --options runtime
        --timestamp
        --entitlements "$ENTITLEMENTS"
        --sign "$SIGNING_IDENTITY"
    )
    if [[ -n "$SIGNING_KEYCHAIN" ]]; then
        CODESIGN_ARGUMENTS+=(--keychain "$SIGNING_KEYCHAIN")
    fi
    codesign "${CODESIGN_ARGUMENTS[@]}" "$APP_IMAGE"
else
    printf 'Предупреждение: Developer ID не задан; создаётся только ad-hoc подпись для QA.\n' >&2
    codesign --force --deep --sign - "$APP_IMAGE"
fi
codesign --verify --deep --strict --verbose=2 "$APP_IMAGE"

APP_ZIP="$RELEASE_DIRECTORY/CamWork-Translate-$VERSION-macos-$ARCHITECTURE.app.zip"
DMG_PATH="$RELEASE_DIRECTORY/CamWork-Translate-$VERSION-macos-$ARCHITECTURE.dmg"
PKG_PATH="$RELEASE_DIRECTORY/CamWork-Translate-$VERSION-macos-$ARCHITECTURE.pkg"
rm -f -- "$APP_ZIP" "$APP_ZIP.sha256" "$DMG_PATH" "$DMG_PATH.sha256" "$PKG_PATH" "$PKG_PATH.sha256"
ditto -c -k --sequesterRsrc --keepParent "$APP_IMAGE" "$APP_ZIP"

ARTIFACTS=("$APP_ZIP")
NOTARY_TARGETS=()

if [[ "$PACKAGE_FORMAT" == "dmg" || "$PACKAGE_FORMAT" == "all" ]]; then
    mkdir -p "$DMG_STAGE_DIRECTORY"
    ditto "$APP_IMAGE" "$DMG_STAGE_DIRECTORY/CamWork Translate.app"
    ln -s /Applications "$DMG_STAGE_DIRECTORY/Applications"
    hdiutil create \
        -volname "CamWork Translate $VERSION" \
        -srcfolder "$DMG_STAGE_DIRECTORY" \
        -ov \
        -format UDZO \
        "$DMG_PATH" >/dev/null
    if [[ -n "$SIGNING_IDENTITY" ]]; then
        DMG_SIGN_ARGUMENTS=(--force --timestamp --sign "$SIGNING_IDENTITY")
        if [[ -n "$SIGNING_KEYCHAIN" ]]; then
            DMG_SIGN_ARGUMENTS+=(--keychain "$SIGNING_KEYCHAIN")
        fi
        codesign "${DMG_SIGN_ARGUMENTS[@]}" "$DMG_PATH"
        codesign --verify --verbose=2 "$DMG_PATH"
    fi
    ARTIFACTS+=("$DMG_PATH")
    NOTARY_TARGETS+=("$DMG_PATH")
fi

if [[ "$PACKAGE_FORMAT" == "pkg" || "$PACKAGE_FORMAT" == "all" ]]; then
    PRODUCTBUILD_ARGUMENTS=(--component "$APP_IMAGE" /Applications)
    if [[ -n "$INSTALLER_IDENTITY" ]]; then
        PRODUCTBUILD_ARGUMENTS+=(--sign "$INSTALLER_IDENTITY")
        if [[ -n "$SIGNING_KEYCHAIN" ]]; then
            PRODUCTBUILD_ARGUMENTS+=(--keychain "$SIGNING_KEYCHAIN")
        fi
    fi
    PRODUCTBUILD_ARGUMENTS+=("$PKG_PATH")
    productbuild "${PRODUCTBUILD_ARGUMENTS[@]}"
    if [[ -n "$INSTALLER_IDENTITY" ]]; then
        pkgutil --check-signature "$PKG_PATH"
    fi
    ARTIFACTS+=("$PKG_PATH")
    NOTARY_TARGETS+=("$PKG_PATH")
fi

if [[ "$NOTARIZE" == true ]]; then
    for artifact in "${NOTARY_TARGETS[@]}"; do
        printf 'Notarization: %s\n' "$(basename "$artifact")"
        NOTARY_ARGUMENTS=(
            submit "$artifact"
            --keychain-profile "$NOTARY_PROFILE"
            --wait
        )
        if [[ -n "$SIGNING_KEYCHAIN" ]]; then
            NOTARY_ARGUMENTS+=(--keychain "$SIGNING_KEYCHAIN")
        fi
        xcrun notarytool "${NOTARY_ARGUMENTS[@]}"
        xcrun stapler staple "$artifact"
        xcrun stapler validate "$artifact"
    done
fi

for artifact in "${ARTIFACTS[@]}"; do
    write_sha256 "$artifact"
    printf 'Готово: %s\n' "$artifact"
done
printf 'Архитектура: %s\n' "$ARCHITECTURE"
if [[ -z "$SIGNING_IDENTITY" ]]; then
    printf 'Статус: QA-сборка; для передачи пользователям нужны Developer ID и notarization.\n'
elif [[ "$NOTARIZE" != true ]]; then
    printf 'Статус: подписано, но не нотариализовано.\n'
else
    printf 'Статус: подписано и нотариализовано.\n'
fi
