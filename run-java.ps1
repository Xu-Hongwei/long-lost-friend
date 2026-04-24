param(
  [ValidateSet("auto", "local", "remote")]
  [string]$Mode = "auto"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot "java-server\src\main\java"
$buildDir = Join-Path $projectRoot "build\classes"

function Clear-LlmEnvironment {
  $keys = @(
    "ARK_API_KEY",
    "ARK_MODEL",
    "ARK_BASE_URL",
    "ARK_TIMEOUT_MS",
    "OPENAI_API_KEY",
    "OPENAI_MODEL",
    "OPENAI_API_BASE",
    "OPENAI_BASE_URL",
    "OPENAI_BASE",
    "OPENAI_TIMEOUT_MS",
    "PLOT_LLM_API_KEY",
    "PLOT_LLM_MODEL",
    "PLOT_LLM_BASE_URL",
    "PLOT_LLM_TIMEOUT_MS",
    "DASHSCOPE_API_KEY",
    "DASHSCOPE_MODEL",
    "DASHSCOPE_BASE_URL",
    "DASHSCOPE_BASE",
    "DASHSCOPE_TIMEOUT_MS"
  )

  foreach ($key in $keys) {
    Remove-Item "Env:$key" -ErrorAction SilentlyContinue
  }
}

function First-NonBlankEnv {
  param([string[]]$keys)

  foreach ($key in $keys) {
    $value = [Environment]::GetEnvironmentVariable($key, "Process")
    if (-not [string]::IsNullOrWhiteSpace($value)) {
      return $value
    }
  }
  return ""
}

function Assert-RemoteEnvironment {
  $chatKey = First-NonBlankEnv @("ARK_API_KEY", "OPENAI_API_KEY")
  $plotKey = First-NonBlankEnv @("PLOT_LLM_API_KEY", "DASHSCOPE_API_KEY", "OPENAI_API_KEY")

  if ([string]::IsNullOrWhiteSpace($chatKey)) {
    throw "Remote mode requires ARK_API_KEY or OPENAI_API_KEY. Use .\runtime\local\run-local.ps1 for local fallback mode."
  }

  if ([string]::IsNullOrWhiteSpace($plotKey)) {
    throw "Remote mode requires PLOT_LLM_API_KEY, DASHSCOPE_API_KEY, or OPENAI_API_KEY for plot/semantic agents. Use .\runtime\local\run-local.ps1 for local fallback mode."
  }
}

if ($Mode -eq "local") {
  Clear-LlmEnvironment
  $env:CAMPUS_PULSE_RUN_MODE = "local"
  Write-Host "Campus Pulse run mode: local fallback only. Remote LLM environment variables are cleared for this process."
} elseif ($Mode -eq "remote") {
  $env:CAMPUS_PULSE_RUN_MODE = "remote"
  Assert-RemoteEnvironment
  Write-Host "Campus Pulse run mode: remote LLM enabled. Environment variables will be read from this process/user/system environment."
} else {
  $env:CAMPUS_PULSE_RUN_MODE = "auto"
  Write-Host "Campus Pulse run mode: auto. Remote LLM is used only when configured; otherwise local fallback is used."
}

function Resolve-JavaExecutable {
  param([string]$name)

  if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\$name"
    if (Test-Path $candidate) {
      return $candidate
    }
  }

  $candidate = Get-ChildItem "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "bin\$name" } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1

  if ($candidate) {
    return $candidate
  }

  throw "Java executable not found: $name. Set JAVA_HOME or install JDK under C:\Program Files\Java."
}

$javac = Resolve-JavaExecutable "javac.exe"
$java = Resolve-JavaExecutable "java.exe"

Remove-Item $buildDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

$sources = Get-ChildItem $sourceRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $sources) {
  throw "No Java source files were found."
}

& $javac -encoding UTF-8 -d $buildDir $sources
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

& $java -cp $buildDir com.campuspulse.CampusPulseServer
exit $LASTEXITCODE
