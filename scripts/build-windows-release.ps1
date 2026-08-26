[CmdletBinding()]
param(
    [Parameter()]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string]$Version = '1.2.6',

    [Parameter()]
    [string]$JavaHome = $env:JAVA_HOME,

    [Parameter()]
    [string]$Jdk21Home = '',

    [Parameter()]
    [string]$InnoSetupCompiler = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot 'build'))
$workRoot = Join-Path $buildRoot 'windows-package-work'
$portableDirectory = Join-Path $workRoot 'portable-distribution'
$inputDirectory = Join-Path $workRoot 'package-input'
$runtimeDirectory = Join-Path $workRoot 'runtime'
$outputDirectory = Join-Path $workRoot 'output'
$releaseDirectory = Join-Path $buildRoot 'release'
$archivePath = Join-Path $releaseDirectory "CamWork-Translate-$Version-windows-x64.zip"
$installerPath = Join-Path $releaseDirectory "CamWork-Translate-$Version-Setup-windows-x64.exe"
$installerScriptPath = Join-Path $repoRoot 'packaging\windows\CamWorkTranslate.iss'
$packagedSmokeScriptPath = Join-Path $repoRoot 'scripts\test-windows-package.ps1'
$appConstantsPath = Join-Path $repoRoot 'core\src\main\kotlin\com\github\ahatem\qtranslate\core\shared\AppConstants.kt'

$appConstantsSource = Get-Content -LiteralPath $appConstantsPath -Raw
$appVersionMatch = [regex]::Match(
    $appConstantsSource,
    'const\s+val\s+APP_VERSION\s*=\s*"([^"]+)"'
)
if (-not $appVersionMatch.Success) {
    throw "Не удалось прочитать APP_VERSION из $appConstantsPath"
}
$embeddedAppVersion = $appVersionMatch.Groups[1].Value
if ($embeddedAppVersion -ne $Version) {
    throw "Версия сборки $Version не совпадает с APP_VERSION $embeddedAppVersion"
}
$checksumPath = "$archivePath.sha256"
$installerChecksumPath = "$installerPath.sha256"

function Assert-Executable {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name не найден: $Path"
    }
}

function Remove-BuildPathSafely {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $allowedPrefix = $buildRoot.TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($allowedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Отказ от очистки пути вне build: $resolvedPath"
    }

    if (Test-Path -LiteralPath $resolvedPath) {
        Remove-Item -LiteralPath $resolvedPath -Recurse -Force
    }
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory)]
        [string]$Executable,

        [Parameter(Mandatory)]
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Команда завершилась с кодом ${LASTEXITCODE}: $Executable"
    }
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'Укажите -JavaHome с путём к JDK 17 или задайте переменную JAVA_HOME.'
}

$javaHomeFull = [System.IO.Path]::GetFullPath($JavaHome)
$jlink = Join-Path $javaHomeFull 'bin\jlink.exe'
$jpackage = Join-Path $javaHomeFull 'bin\jpackage.exe'
$gradleWrapper = Join-Path $repoRoot 'gradlew.bat'

Assert-Executable -Path $gradleWrapper -Name 'Gradle Wrapper'
Assert-Executable -Path $jlink -Name 'jlink из JDK 17'
Assert-Executable -Path $jpackage -Name 'jpackage из JDK 17'
Assert-Executable -Path $installerScriptPath -Name 'Сценарий Inno Setup'
Assert-Executable -Path $packagedSmokeScriptPath -Name 'Packaged smoke-test Windows'

if ([string]::IsNullOrWhiteSpace($InnoSetupCompiler)) {
    $innoCandidates = @(
        (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe'),
        (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
    )
    $InnoSetupCompiler = $innoCandidates |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($InnoSetupCompiler)) {
    throw 'Inno Setup 6 не найден. Установите его или укажите -InnoSetupCompiler.'
}
$innoSetupFull = [System.IO.Path]::GetFullPath($InnoSetupCompiler)
Assert-Executable -Path $innoSetupFull -Name 'Компилятор Inno Setup 6'

$env:JAVA_HOME = $javaHomeFull
$env:Path = "$(Join-Path $javaHomeFull 'bin');$env:Path"

$toolchainPaths = [System.Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace($Jdk21Home)) {
    $jdk21Full = [System.IO.Path]::GetFullPath($Jdk21Home)
    if (-not (Test-Path -LiteralPath $jdk21Full -PathType Container)) {
        throw "JDK 21 не найден: $jdk21Full"
    }
    $toolchainPaths.Add($jdk21Full)
}
$toolchainPaths.Add($javaHomeFull)

$gradleArguments = @(
    'assemblePortable',
    "-PreleaseVersion=$Version",
    "-Dorg.gradle.java.installations.paths=$($toolchainPaths -join ',')",
    '--no-daemon'
)

Push-Location $repoRoot
try {
    Invoke-NativeCommand -Executable $gradleWrapper -Arguments $gradleArguments
}
finally {
    Pop-Location
}

$portableArchive = Join-Path $releaseDirectory "CamWork-Translate-$Version.zip"
if (-not (Test-Path -LiteralPath $portableArchive -PathType Leaf)) {
    throw "Переносимый архив не создан: $portableArchive"
}

Remove-BuildPathSafely -Path $workRoot
Remove-BuildPathSafely -Path $archivePath
Remove-BuildPathSafely -Path $checksumPath
Remove-BuildPathSafely -Path $installerPath
Remove-BuildPathSafely -Path $installerChecksumPath
New-Item -ItemType Directory -Path $portableDirectory, $inputDirectory, $outputDirectory -Force | Out-Null

Expand-Archive -LiteralPath $portableArchive -DestinationPath $portableDirectory
$portableRoot = Join-Path $portableDirectory 'CamWork Translate'
$portableJar = Join-Path $portableRoot 'CamWork-Translate.jar'
if (-not (Test-Path -LiteralPath $portableJar -PathType Leaf)) {
    throw "В переносимом архиве отсутствует JAR: $portableJar"
}
Copy-Item -LiteralPath $portableJar -Destination (Join-Path $inputDirectory 'CamWork-Translate.jar')

$modules = @(
    'java.base',
    'java.desktop',
    'java.instrument',
    'java.logging',
    'java.management',
    'java.naming',
    'java.net.http',
    'java.prefs',
    'java.security.jgss',
    'java.xml.crypto',
    'jdk.charsets',
    'jdk.crypto.ec',
    'jdk.localedata',
    'jdk.unsupported',
    'jdk.zipfs'
) -join ','

$jlinkArguments = @(
    '--add-modules', $modules,
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--strip-native-commands',
    '--compress=2',
    '--output', $runtimeDirectory
)
Invoke-NativeCommand -Executable $jlink -Arguments $jlinkArguments

$packageVersion = ($Version -split '[-+]')[0]
$iconPath = Join-Path $repoRoot 'ui-swing\src\main\resources\icons\app\icon.ico'
$jpackageArguments = @(
    '--type', 'app-image',
    '--name', 'CamWork Translate',
    '--input', $inputDirectory,
    '--main-jar', 'CamWork-Translate.jar',
    '--main-class', 'com.github.ahatem.qtranslate.app.MainKt',
    '--app-version', $packageVersion,
    '--vendor', 'ITWORKSYSTEMS LTD / CamWork',
    '--description', 'Быстрый переводчик для моделей CamWork',
    '--copyright', 'Copyright (c) 2026 ITWORKSYSTEMS LTD / CamWork - modifications; QTranslate (c) 2023 Ahmed Hatem (MIT)',
    '--icon', $iconPath,
    '--runtime-image', $runtimeDirectory,
    '--dest', $outputDirectory,
    '--java-options', '-Dfile.encoding=UTF-8'
)
Invoke-NativeCommand -Executable $jpackage -Arguments $jpackageArguments

$appImageDirectory = Join-Path $outputDirectory 'CamWork Translate'
$launcherPath = Join-Path $appImageDirectory 'CamWork Translate.exe'
if (-not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
    throw "Нативный запускной файл не создан: $launcherPath"
}

Get-ChildItem -LiteralPath $portableRoot | Where-Object { $_.Name -ne 'CamWork-Translate.jar' } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $appImageDirectory -Recurse -Force
}

Compress-Archive -LiteralPath $appImageDirectory -DestinationPath $archivePath -CompressionLevel Optimal
& $packagedSmokeScriptPath -ArchivePath $archivePath
$hash = Get-FileHash -LiteralPath $archivePath -Algorithm SHA256
[System.IO.File]::WriteAllText($checksumPath, "$($hash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($archivePath))`r`n")

$innoArguments = @(
    "/DAppVersion=$Version",
    "/DPackageVersion=$packageVersion",
    "/DSourceDir=$appImageDirectory",
    "/DOutputDir=$releaseDirectory",
    "/DRepoRoot=$repoRoot",
    $installerScriptPath
)
Invoke-NativeCommand -Executable $innoSetupFull -Arguments $innoArguments

if (-not (Test-Path -LiteralPath $installerPath -PathType Leaf)) {
    throw "Установщик не создан: $installerPath"
}
$installerHash = Get-FileHash -LiteralPath $installerPath -Algorithm SHA256
[System.IO.File]::WriteAllText(
    $installerChecksumPath,
    "$($installerHash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($installerPath))`r`n"
)

$archiveSizeMb = [math]::Round((Get-Item -LiteralPath $archivePath).Length / 1MB, 2)
$installerSizeMb = [math]::Round((Get-Item -LiteralPath $installerPath).Length / 1MB, 2)
Write-Output "Windows-релиз: $archivePath"
Write-Output "Размер: $archiveSizeMb МБ"
Write-Output "SHA-256: $($hash.Hash.ToLowerInvariant())"
Write-Output "Установщик: $installerPath"
Write-Output "Размер установщика: $installerSizeMb МБ"
Write-Output "SHA-256 установщика: $($installerHash.Hash.ToLowerInvariant())"
