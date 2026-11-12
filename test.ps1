param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (!$JavaHome -or !(Test-Path "$JavaHome/bin/javac.exe")) { $JavaHome = 'C:/Program Files/Java/jdk-27.0.1' }
$out = Join-Path $PSScriptRoot ("build/tests-" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $out | Out-Null
$src = Join-Path $PSScriptRoot 'src/top/qiyuey/liquidglass'
& "$JavaHome/bin/javac.exe" -encoding UTF-8 --release 27 -d $out "$src/GlassStyle.java" "$src/WeWorkTabPolicy.java" "$src/Spring.java" "$PSScriptRoot/tests/CoreTests.java"
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& "$JavaHome/bin/java.exe" -cp $out top.qiyuey.liquidglass.CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
