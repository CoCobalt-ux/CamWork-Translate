[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArchivePath,

    [int]$TimeoutSeconds = 45
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$archive = [System.IO.Path]::GetFullPath($ArchivePath)
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    throw "Не найден Windows ZIP для smoke-test: $archive"
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$smokeRoot = Join-Path $tempBase ("camwork-package-smoke-{0}-{1}" -f $PID, [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $smokeRoot | Out-Null
$completed = $false
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS

try {
    Expand-Archive -LiteralPath $archive -DestinationPath $smokeRoot
    $appRoot = Join-Path $smokeRoot 'CamWork Translate'
    $launcher = Join-Path $appRoot 'CamWork Translate.exe'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "В Windows ZIP отсутствует launcher: $launcher"
    }

    $smokeOption = '-Dcamwork.packagedSmokeTest=true'
    $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        $smokeOption
    }
    else {
        "$previousJavaToolOptions $smokeOption"
    }

    $process = Start-Process `
        -FilePath $launcher `
        -WorkingDirectory $appRoot `
        -WindowStyle Hidden `
        -PassThru

    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
        throw "Packaged smoke-test не завершился за $TimeoutSeconds секунд"
    }
    if ($process.ExitCode -ne 0) {
        throw "Packaged smoke-test завершился с кодом $($process.ExitCode)"
    }

    $marker = Join-Path $appRoot 'logs\packaged-smoke-ok.txt'
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf)) {
        throw "Packaged smoke-test не создал подтверждающий marker"
    }
    $markerText = (Get-Content -LiteralPath $marker -Raw).Trim()
    if (-not $markerText.StartsWith('CAMWORK_PACKAGED_SMOKE_OK ')) {
        throw "Packaged smoke-test создал некорректный marker"
    }

    Write-Output $markerText
    $completed = $true
}
finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions

    if ($completed) {
        $resolvedSmokeRoot = [System.IO.Path]::GetFullPath($smokeRoot)
        $isSafeTempChild =
            $resolvedSmokeRoot.StartsWith($tempBase, [System.StringComparison]::OrdinalIgnoreCase) -and
            ([System.IO.Path]::GetFileName($resolvedSmokeRoot)).StartsWith('camwork-package-smoke-')
        if (-not $isSafeTempChild) {
            throw "Отказ от очистки неожиданного пути smoke-test: $resolvedSmokeRoot"
        }
        Remove-Item -LiteralPath $resolvedSmokeRoot -Recurse -Force
    }
    elseif (Test-Path -LiteralPath $smokeRoot) {
        Write-Warning "Диагностические файлы неуспешного smoke-test сохранены: $smokeRoot"
    }
}
