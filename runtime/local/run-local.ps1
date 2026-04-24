$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
Set-Location $projectRoot

Write-Host "Building frontend for local fallback mode..."
npm run build:web
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

Write-Host "Starting Java server in local fallback mode..."
& powershell -ExecutionPolicy Bypass -File (Join-Path $projectRoot "run-java.ps1") -Mode local
exit $LASTEXITCODE
