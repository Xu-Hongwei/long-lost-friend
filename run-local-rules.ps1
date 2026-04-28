$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainRoot = Join-Path $projectRoot "java-server\src\main\java"
$runnerSource = Join-Path $projectRoot "java-server\src\test\java\com\campuspulse\LocalRuleRunner.java"
$buildDir = Join-Path $projectRoot "build\local-rule-runner-classes"

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

New-Item -ItemType Directory -Path $buildDir -Force | Out-Null

$sources = @()
$sources += Get-ChildItem $mainRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$sources += $runnerSource

if (-not $sources) {
  throw "No Java source files were found."
}

$previousQuickJudgeForceAll = $env:QUICK_JUDGE_FORCE_ALL
$env:QUICK_JUDGE_FORCE_ALL = ""

try {
  & $javac -encoding UTF-8 -d $buildDir $sources
  if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
  }

  & $java -cp $buildDir com.campuspulse.LocalRuleRunner @args
  exit $LASTEXITCODE
} finally {
  $env:QUICK_JUDGE_FORCE_ALL = $previousQuickJudgeForceAll
}
