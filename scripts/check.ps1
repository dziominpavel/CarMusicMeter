# Полная локальная проверка проекта перед сборкой APK.
# Запуск: powershell -ExecutionPolicy Bypass -File scripts\check.ps1
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "==> Gradle tests and lint"
& .\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> Report golden tests"
$reportOutput = & py scripts\test_report.py 2>&1
if ($LASTEXITCODE -ne 0) {
    $reportOutput | Write-Host
    exit $LASTEXITCODE
}

Write-Host "==> Version and changelog"
& py scripts\check-version.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "==> OpenSpec validation"
& openspec validate --all
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "All checks passed."
