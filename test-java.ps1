$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainRoot = Join-Path $projectRoot "java-server\src\main\java"
$testRoot = Join-Path $projectRoot "java-server\src\test\java"
$buildDir = Join-Path $projectRoot "build\test-classes"

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

$sources = @()
$sources += Get-ChildItem $mainRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
$sources += Get-ChildItem $testRoot -Recurse -Filter *.java | Select-Object -ExpandProperty FullName

if (-not $sources) {
  throw "No Java source files were found."
}

& $javac -encoding UTF-8 -d $buildDir $sources
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

& $java -cp $buildDir com.campuspulse.SmokeTest
exit $LASTEXITCODE
