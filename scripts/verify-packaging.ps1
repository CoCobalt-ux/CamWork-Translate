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

$macScript = Read-RequiredFile -RelativePath 'scripts/build-macos-release.sh'
$bootstrap = Read-RequiredFile -RelativePath 'packaging/macos/CamWorkBootstrap.c'
$entitlementsPath = Join-Path $repoRoot 'packaging/macos/entitlements.plist'
$entitlementsSource = Read-RequiredFile -RelativePath 'packaging/macos/entitlements.plist'
$notice = Read-RequiredFile -RelativePath 'NOTICE'
$license = Read-RequiredFile -RelativePath 'LICENSE'
$windowsScript = Read-RequiredFile -RelativePath 'scripts/build-windows-release.ps1'
$windowsInstaller = Read-RequiredFile -RelativePath 'packaging/windows/CamWorkTranslate.iss'

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
