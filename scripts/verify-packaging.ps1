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
$windowsOnlyReleaseStep = Get-TextSection `
    -Text $releaseWorkflow `
    -StartMarker "      - name: Create GitHub Release without native macOS artifacts" `
    -EndMarker "      - name: Create GitHub Release with signed macOS artifacts"

Assert-Condition -Condition ($productionMacJob.Contains("vars.MACOS_STABLE_ENABLED == 'true'")) `
    -Message 'Production macOS job должен включаться только через MACOS_STABLE_ENABLED=true.'
$credentialStep = $productionMacJob.IndexOf('      - name: Require production Apple credentials')
$checkoutStep = $productionMacJob.IndexOf('      - name: Checkout')
Assert-Condition -Condition ($credentialStep -ge 0 -and $checkoutStep -gt $credentialStep) `
    -Message 'Проверка Apple-секретов должна быть первым шагом production macOS job.'

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
Assert-Condition -Condition (-not $productionMacJob.Contains('ad-hoc')) `
    -Message 'Production macOS job не должен собирать ad-hoc артефакты.'
Assert-Condition -Condition (
    $productionMacJob.Contains('--signing-identity') -and
    $productionMacJob.Contains('--signing-keychain') -and
    $productionMacJob.Contains('--notary-profile') -and
    $productionMacJob.Contains('--notarize')
) `
    -Message 'Production macOS job обязан всегда подписывать и нотариализовать пакет.'
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

Assert-Condition -Condition ($releaseWorkflow.Contains("needs.macos-package.result == 'skipped'")) `
    -Message 'Windows release должен продолжаться, когда production macOS job выключен.'
Assert-Condition -Condition ($releaseWorkflow.Contains("needs.windows-package.result == 'success'")) `
    -Message 'Публикация релиза должна требовать успешную Windows-сборку.'
Assert-Condition -Condition (
    [regex]::IsMatch($releaseWorkflow, '(?ms)^permissions:\s+contents: read\s*$') -and
    ([regex]::Matches($releaseWorkflow, '(?m)^\s{6}contents: write\s*$').Count -eq 1)
) `
    -Message 'Только финальный release job должен иметь contents: write.'
Assert-Condition -Condition ($macDownloadStep.Contains("vars.MACOS_STABLE_ENABLED == 'true'")) `
    -Message 'Загрузка macOS artifacts должна быть условной.'
Assert-Condition -Condition (-not $windowsOnlyReleaseStep.Contains('build/release/CamWork-Translate-${{ steps.version.outputs.version }}-macos-')) `
    -Message 'Windows-only Release не должен содержать пути нативных macOS-файлов.'
Assert-Condition -Condition ($windowsOnlyReleaseStep.Contains("vars.MACOS_STABLE_ENABLED != 'true'")) `
    -Message 'Windows-only Release должен срабатывать при выключенном production macOS.'
Assert-Condition -Condition (
    ([regex]::Matches($releaseWorkflow, 'fail_on_unmatched_files: true').Count -eq 2)
) `
    -Message 'Оба шага публикации должны падать при отсутствии хотя бы одного релизного файла.'
Assert-Condition -Condition (
    $releaseWorkflow.Contains('if os.environ.get("MACOS_STABLE_ENABLED") == "true":') -and
    $releaseWorkflow.Contains('macos-arm64.dmg') -and
    $releaseWorkflow.Contains('macos-x64.dmg')
) `
    -Message 'Ссылки macOS в release body должны добавляться только при включённом production macOS.'
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
    $macQaWorkflow.Contains('camwork-translate-macos-QA-') -and
    $macQaWorkflow.Contains('retention-days: 3')
) `
    -Message 'macOS QA artifacts должны быть явно маркированы и храниться только 3 дня.'
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
