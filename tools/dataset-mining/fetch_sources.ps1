$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$projectRoot = Split-Path -Parent $projectRoot
$rawRoot = Join-Path $projectRoot "raw-datasets"

New-Item -ItemType Directory -Path $rawRoot -Force | Out-Null

function Clone-IfMissing {
  param(
    [string]$Name,
    [string]$Url
  )

  $target = Join-Path $rawRoot $Name
  if (Test-Path $target) {
    Write-Host "skip existing $Name -> $target"
    return
  }

  Write-Host "cloning $Name from $Url"
  git -c http.sslBackend=openssl clone --depth 1 $Url $target
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to clone $Name from $Url"
  }
}

Clone-IfMissing -Name "CrossWOZ" -Url "https://github.com/thu-coai/CrossWOZ.git"
Clone-IfMissing -Name "CPED" -Url "https://github.com/scutcyr/CPED.git"
Clone-IfMissing -Name "NaturalConvDataSet" -Url "https://github.com/naturalconv/NaturalConvDataSet.git"

Write-Host ""
Write-Host "Raw datasets are stored under $rawRoot"
Write-Host "This directory is ignored by git. Do not commit raw external corpora."
Write-Host "NaturalConv's full data is hosted separately; see SOURCES.md and the NaturalConv README/license before downloading the full archive."
