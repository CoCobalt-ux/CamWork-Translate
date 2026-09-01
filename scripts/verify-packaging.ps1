[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Assert-Condition {
    param(
        [Parameter(Mandatory)]
        [bool]$Condition,

        [Parameter(Mandatory)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Read-RequiredFile {
    param(
        [Parameter(Mandatory)]
        [string]$RelativePath
    )

    $path = Join-Path $repoRoot $RelativePath
    Assert-Condition -Condition (Test-Path -LiteralPath $path -PathType Leaf) `
        -Message "Не найден обязательный файл упаковки: $RelativePath"
    return Get-Content -LiteralPath $path -Raw
}

function Get-TextSection {
    param(
        [Parameter(Mandatory)]
        [string]$Text,

        [Parameter(Mandatory)]
        [string]$StartMarker,

        [Parameter(Mandatory)]
        [string]$EndMarker
    )

    $start = $Text.IndexOf($StartMarker, [System.StringComparison]::Ordinal)
    Assert-Condition -Condition ($start -ge 0) `
        -Message "Не найдено начало проверяемого раздела: $StartMarker"
    $end = $Text.IndexOf($EndMarker, $start + $StartMarker.Length, [System.StringComparison]::Ordinal)
    Assert-Condition -Condition ($end -gt $start) `
        -Message "Не найден конец проверяемого раздела: $EndMarker"
    return $Text.Substring($start, $end - $start)
}

$macScript = Read-RequiredFile -RelativePath 'scripts/build-macos-release.sh'
$bootstrap = Read-RequiredFile -RelativePath 'packaging/macos/CamWorkBootstrap.c'
$entitlementsPath = Join-Path $repoRoot 'packaging/macos/entitlements.plist'
$entitlementsSource = Read-RequiredFile -RelativePath 'packaging/macos/entitlements.plist'
$notice = Read-RequiredFile -RelativePath 'NOTICE'
$license = Read-RequiredFile -RelativePath 'LICENSE'
$windowsScript = Read-RequiredFile -RelativePath 'scripts/build-windows-release.ps1'
$windowsInstaller = Read-RequiredFile -RelativePath 'packaging/windows/CamWorkTranslate.iss'
$releaseWorkflow = Read-RequiredFile -RelativePath '.github/workflows/release.yml'
$macQaWorkflow = Read-RequiredFile -RelativePath '.github/workflows/macos-qa.yml'
$ciWorkflow = Read-RequiredFile -RelativePath '.github/workflows/ci.yml'
$pluginSmokeWorkflow = Read-RequiredFile -RelativePath '.github/workflows/plugin-smoke.yml'

try {
    [xml]$entitlements = $entitlementsSource
}
catch {
    throw "Некорректный XML entitlements.plist: $($_.Exception.Message)"
}

$entitlementKeys = @(
    $entitlements.SelectNodes('/plist/dict/key') |
        ForEach-Object { $_.InnerText }
)
$requiredEntitlements = @(
    'com.apple.security.cs.allow-jit',
    'com.apple.security.cs.allow-unsigned-executable-memory',
    'com.apple.security.cs.disable-library-validation'
)
foreach ($key in $requiredEntitlements) {
    Assert-Condition -Condition ($entitlementKeys -contains $key) `
        -Message "В entitlements.plist отсутствует $key"
}
Assert-Condition -Condition ($entitlementKeys -notcontains 'com.apple.security.get-task-allow') `
    -Message 'Production entitlements не должны содержать get-task-allow.'

Assert-Condition -Condition ($macScript.Contains('[[ "$(uname -s)" == "Darwin" ]]')) `
    -Message 'macOS-сборщик обязан запрещать кросс-сборку на другой ОС.'
Assert-Condition -Condition ($macScript.Contains('club.camwork.translate')) `
    -Message 'В macOS-сборщике отсутствует стабильный bundle identifier.'
Assert-Condition -Condition (
    $macScript.Contains('submit "$artifact"') -and
    $macScript.Contains('xcrun notarytool "${NOTARY_ARGUMENTS[@]}"')
) `
    -Message 'В macOS-сборщике отсутствует этап notarization.'
Assert-Condition -Condition ($macScript.Contains('xcrun stapler staple')) `
    -Message 'В macOS-сборщике отсутствует прикрепление notarization ticket.'
Assert-Condition -Condition ($macScript.Contains('shasum -a 256')) `
    -Message 'В macOS-сборщике отсутствует SHA-256.'
Assert-Condition -Condition ($macScript.Contains('CamWork Translate.bin.cfg')) `
    -Message 'Переименованный jpackage launcher должен иметь одноимённый .cfg.'

Assert-Condition -Condition ($bootstrap.Contains('Library/Application Support')) `
    -Message 'Bootstrap должен хранить пользовательские данные в Application Support.'
Assert-Condition -Condition ($bootstrap.Contains('JAVA_TOOL_OPTIONS')) `
    -Message 'Bootstrap должен передавать отдельный каталог данных JVM.'
Assert-Condition -Condition ($bootstrap.Contains('CamWork Translate.bin')) `
    -Message 'Bootstrap должен запускать оригинальный jpackage launcher.'
Assert-Condition -Condition ($bootstrap.Contains('CAMWORK_PACKAGED_SMOKE_TEST')) `
    -Message 'Bootstrap должен отключать TCC-диалоги только для packaged smoke.'
Assert-Condition -Condition ($bootstrap.Contains('AXIsProcessTrustedWithOptions')) `
    -Message 'Bootstrap должен запрашивать и повторно проверять Accessibility.'
Assert-Condition -Condition ($bootstrap.Contains('CGPreflightListenEventAccess')) `
    -Message 'Bootstrap должен проверять Input Monitoring.'
Assert-Condition -Condition ($bootstrap.Contains('CGRequestListenEventAccess')) `
    -Message 'Bootstrap должен инициировать системный запрос Input Monitoring.'
Assert-Condition -Condition ($bootstrap.Contains('__builtin_available(macOS 10.15, *)')) `
    -Message 'Input Monitoring API должен быть защищён проверкой версии macOS.'
Assert-Condition -Condition (
    $bootstrap.Contains('Cmd+Q') -and
    $bootstrap.Contains('не позволяет приложению выдать разрешения автоматически') -and
    $bootstrap.Contains('будут проверены повторно')
) `
    -Message 'Подсказка TCC должна требовать полный перезапуск без обещания автоматической выдачи.'

Assert-Condition -Condition ($notice.Contains('Copyright (c) 2026 ITWORKSYSTEMS LTD / CamWork')) `
    -Message 'NOTICE не содержит правообладателя доработок CamWork.'
Assert-Condition -Condition ($notice.Contains('Copyright (c) 2023 Ahmed Hatem')) `
    -Message 'NOTICE не содержит исходную MIT-атрибуцию Ahmed Hatem.'
Assert-Condition -Condition ($notice.Contains('LICENSE')) `
    -Message 'NOTICE не ссылается на обязательный текст MIT.'
Assert-Condition -Condition ($license.Contains('Copyright (c) 2023 Ahmed Hatem')) `
    -Message 'LICENSE не содержит исходное уведомление Ahmed Hatem.'
Assert-Condition -Condition ($license.Contains('Modifications Copyright (c) 2026 ITWORKSYSTEMS LTD / CamWork')) `
    -Message 'LICENSE не содержит уведомление CamWork о правах на доработки.'

Assert-Condition -Condition ($windowsScript.Contains("'--vendor', 'ITWORKSYSTEMS LTD / CamWork'")) `
    -Message 'Windows jpackage metadata не содержит корректного разработчика.'
Assert-Condition -Condition ($windowsInstaller.Contains('#define AppPublisher "ITWORKSYSTEMS LTD / CamWork"')) `
    -Message 'Windows installer metadata не содержит корректного издателя.'

$productionMacJob = Get-TextSection `
    -Text $releaseWorkflow `
    -StartMarker "  macos-package:" `
    -EndMarker "  release:"
$macDownloadStep = Get-TextSection `
    -Text $releaseWorkflow `
    -StartMarker "      - name: Download macOS packages" `
    -EndMarker "      - name: Add Windows package checksums"
$unsignedMacReleaseStep = Get-TextSection `
    -Text $releaseWorkflow `
    -StartMarker "      - name: Create GitHub Release with unsigned internal macOS artifacts" `
    -EndMarker "      - name: Create GitHub Release with signed macOS artifacts"

Assert-Condition -Condition (-not [regex]::IsMatch($productionMacJob, '(?m)^\s{4}if:\s.*MACOS_STABLE_ENABLED')) `
    -Message 'macOS job должен запускаться для каждого полного релиза, независимо от подписи.'
$credentialStep = $productionMacJob.IndexOf('      - name: Require production Apple credentials')
$checkoutStep = $productionMacJob.IndexOf('      - name: Checkout')
Assert-Condition -Condition ($credentialStep -ge 0 -and $checkoutStep -gt $credentialStep) `
    -Message 'Условная проверка Apple-секретов должна оставаться первым шагом macOS job.'
Assert-Condition -Condition ($productionMacJob.Contains("if: `${{ vars.MACOS_STABLE_ENABLED == 'true' }}")) `
    -Message 'Подпись macOS должна включаться только через MACOS_STABLE_ENABLED=true.'

$requiredAppleSecrets = @(
    'MACOS_CERTIFICATE_P12_BASE64',
    'MACOS_CERTIFICATE_PASSWORD',
    'MACOS_SIGNING_IDENTITY',
    'MACOS_NOTARY_APPLE_ID',
    'MACOS_NOTARY_TEAM_ID',
    'MACOS_NOTARY_PASSWORD'
)
foreach ($secret in $requiredAppleSecrets) {
    Assert-Condition -Condition ($productionMacJob.Contains("secrets.$secret")) `
        -Message "Production macOS job не проверяет обязательный секрет $secret"
}
Assert-Condition -Condition (-not $productionMacJob.Contains('has-signing')) `
    -Message 'Production macOS job не должен иметь условный fallback при отсутствии подписи.'
Assert-Condition -Condition (
    $productionMacJob.Contains('--signing-identity') -and
    $productionMacJob.Contains('--signing-keychain') -and
    $productionMacJob.Contains('--notary-profile') -and
    $productionMacJob.Contains('--notarize')
) `
    -Message 'Подписанный macOS-режим обязан передавать все параметры подписи и notarization.'
Assert-Condition -Condition (
    $productionMacJob.Contains('Build unsigned internal native app and DMG') -and
    $productionMacJob.Contains("if: `${{ vars.MACOS_STABLE_ENABLED != 'true' }}") -and
    $productionMacJob.Contains('Verify unsigned DMG installation guide') -and
    $productionMacJob.Contains('READ-ME-FIRST.txt') -and
    $productionMacJob.Contains("grep -F 'Open Anyway'")
) `
    -Message 'Unsigned macOS-режим должен собираться явно и проверять инструкцию Open Anyway внутри DMG.'
Assert-Condition -Condition (
    $macScript.Contains('submit "$APP_ZIP"') -and
    $macScript.Contains('stapler staple "$APP_IMAGE"') -and
    $macScript.Contains('stapler validate "$APP_IMAGE"')
) `
    -Message 'Production macOS должен нотариализовать ZIP и пересобирать его из stapled .app.'
Assert-Condition -Condition (
    $productionMacJob.Contains('CAMWORK_PACKAGED_SMOKE_TEST=1') -and
    $productionMacJob.Contains('-Dcamwork.packagedSmokeTest=true') -and
    $productionMacJob.Contains('HOME="$smoke_home"') -and
    $productionMacJob.Contains('Library/Application Support/CamWork Translate') -and
    $productionMacJob.Contains('CAMWORK_PACKAGED_SMOKE_OK')
) `
    -Message 'Production macOS job должен запускать packaged smoke в изолированном HOME/appData.'
Assert-Condition -Condition (
    $productionMacJob.Contains('Self-test the local Vision OCR helper') -and
    $productionMacJob.Contains('camwork-vision-ocr') -and
    $productionMacJob.Contains('"$helper" selftest')
) `
    -Message 'Каждая релизная macOS-сборка должна проходить нативный Vision OCR self-test.'

Assert-Condition -Condition ($releaseWorkflow.Contains("needs.macos-package.result == 'success'")) `
    -Message 'Полный релиз должен требовать успешные macOS-сборки.'
Assert-Condition -Condition ($releaseWorkflow.Contains("needs.windows-package.result == 'success'")) `
    -Message 'Публикация релиза должна требовать успешную Windows-сборку.'
Assert-Condition -Condition (
    [regex]::IsMatch($releaseWorkflow, '(?ms)^permissions:\s+contents: read\s*$') -and
    ([regex]::Matches($releaseWorkflow, '(?m)^\s{6}contents: write\s*$').Count -eq 1)
) `
    -Message 'Только финальный release job должен иметь contents: write.'
Assert-Condition -Condition (-not $macDownloadStep.Contains('MACOS_STABLE_ENABLED')) `
    -Message 'macOS artifacts должны скачиваться для любого полного релиза.'
Assert-Condition -Condition ($unsignedMacReleaseStep.Contains('macos-arm64.dmg') -and $unsignedMacReleaseStep.Contains('macos-x64.dmg')) `
    -Message 'Unsigned Release должен содержать DMG для Apple Silicon и Intel.'
Assert-Condition -Condition ($unsignedMacReleaseStep.Contains('READ-ME-FIRST.txt')) `
    -Message 'Unsigned Release должен публиковать инструкции установки macOS.'
Assert-Condition -Condition ($unsignedMacReleaseStep.Contains("vars.MACOS_STABLE_ENABLED != 'true'")) `
    -Message 'Unsigned Release должен срабатывать только без production-подписи macOS.'
Assert-Condition -Condition (
    ([regex]::Matches($releaseWorkflow, 'fail_on_unmatched_files: true').Count -eq 2)
) `
    -Message 'Оба шага публикации должны падать при отсутствии хотя бы одного релизного файла.'
Assert-Condition -Condition (
    $releaseWorkflow.Contains('signed_macos = os.environ.get("MACOS_STABLE_ENABLED") == "true"') -and
    $releaseWorkflow.Contains('macos-arm64.dmg') -and
    $releaseWorkflow.Contains('macos-x64.dmg') -and
    $releaseWorkflow.Contains('Внутренняя unsigned-сборка')
) `
    -Message 'Release body должен всегда содержать обе macOS-ссылки и честно указывать режим подписи.'
Assert-Condition -Condition (
    $releaseWorkflow.Contains('Normalize checksum names for GitHub Release') -and
    $releaseWorkflow.Contains('published_name = Path(relative_name.strip()).name') -and
    $releaseWorkflow.Contains('Duplicate GitHub Release asset name')
) `
    -Message 'SHA256SUMS должен содержать плоские уникальные имена опубликованных GitHub assets.'
Assert-Condition -Condition ($releaseWorkflow.Contains('sha256sum -c --ignore-missing SHA256SUMS.txt')) `
    -Message 'Инструкция релиза должна проверять скачанное подмножество assets без ложных ошибок отсутствия.'

Assert-Condition -Condition ($macQaWorkflow.Contains('workflow_dispatch:')) `
    -Message 'macOS QA workflow должен запускаться вручную.'
Assert-Condition -Condition (-not $macQaWorkflow.Contains('softprops/action-gh-release')) `
    -Message 'macOS QA workflow не должен создавать GitHub Release.'
Assert-Condition -Condition (-not $macQaWorkflow.Contains('secrets.')) `
    -Message 'Неподписанный macOS QA workflow не должен использовать production-секреты.'
Assert-Condition -Condition (
    $macQaWorkflow.Contains('- arch: arm64') -and
    $macQaWorkflow.Contains('- arch: x64')
) `
    -Message 'macOS QA workflow должен собирать arm64 и x64.'
Assert-Condition -Condition (
    $macQaWorkflow.Contains('QA-ONLY-${{ matrix.arch }}.txt') -and
    $macQaWorkflow.Contains('READ-ME-FIRST.txt') -and
    $macQaWorkflow.Contains('hdiutil attach "$dmg" -readonly -nobrowse') -and
    $macQaWorkflow.Contains("grep -F 'Open Anyway'") -and
    $macQaWorkflow.Contains('camwork-translate-macos-QA-') -and
    $macQaWorkflow.Contains('retention-days: 14')
) `
    -Message 'macOS QA artifacts должны быть явно маркированы и храниться 14 дней.'
Assert-Condition -Condition (
    $macScript.Contains('QA_INSTALL_RU.txt') -and
    $macScript.Contains('$DMG_STAGE_DIRECTORY/READ-ME-FIRST.txt') -and
    $macScript.Contains('macos-$ARCHITECTURE-READ-ME-FIRST.txt')
) `
    -Message 'Инструкция Open Anyway должна попадать рядом с QA-DMG и внутрь него.'
Assert-Condition -Condition (
    $macQaWorkflow.Contains('CAMWORK_PACKAGED_SMOKE_TEST=1') -and
    $macQaWorkflow.Contains('-Dcamwork.packagedSmokeTest=true') -and
    $macQaWorkflow.Contains('HOME="$smoke_home"') -and
    $macQaWorkflow.Contains('Library/Application Support/CamWork Translate') -and
    $macQaWorkflow.Contains('CAMWORK_PACKAGED_SMOKE_OK')
) `
    -Message 'macOS QA workflow должен запускать packaged smoke в изолированном HOME/appData.'
Assert-Condition -Condition (-not $macQaWorkflow.Contains('--notarize')) `
    -Message 'macOS QA workflow не должен маскироваться под нотариализованный production-пакет.'
Assert-Condition -Condition (-not $releaseWorkflow.Contains("`t") -and -not $macQaWorkflow.Contains("`t")) `
    -Message 'Workflow YAML не должен содержать табуляцию.'

foreach ($workflow in @($releaseWorkflow, $macQaWorkflow, $ciWorkflow, $pluginSmokeWorkflow)) {
    $floatingActions = [regex]::Matches(
        $workflow,
        '(?m)^\s*uses:\s+[^@\s]+@(?![0-9a-f]{40}(?:\s|#|$))\S+'
    )
    Assert-Condition -Condition ($floatingActions.Count -eq 0) `
        -Message "GitHub Actions должны быть закреплены на commit SHA: $(($floatingActions | ForEach-Object { $_.Value }) -join ', ')"
}

$bashCandidates = @(
    (Get-Command bash -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
    'C:\Program Files\Git\bin\bash.exe'
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Leaf) }
$bash = $bashCandidates | Select-Object -First 1
if ($null -ne $bash) {
    & $bash -n (Join-Path $repoRoot 'scripts/build-macos-release.sh')
    if ($LASTEXITCODE -ne 0) {
        throw "bash -n завершился с кодом $LASTEXITCODE"
    }
}
else {
    Write-Warning 'bash не найден; синтаксическая проверка build-macos-release.sh пропущена.'
}

Write-Output "Проверка упаковки пройдена: $entitlementsPath"
