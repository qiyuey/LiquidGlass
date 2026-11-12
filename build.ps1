param(
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Platform,
    [string]$BuildTools
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$versions = ConvertFrom-StringData (Get-Content "$PSScriptRoot/toolchain.env" -Raw)
if (!$Platform) { $Platform = $versions.COMPILE_SDK }
if (!$BuildTools) { $BuildTools = $versions.BUILD_TOOLS }
if (!$JavaHome -or !(Test-Path "$JavaHome/bin/javac.exe")) {
    $JavaHome = 'C:/Program Files/Java/jdk-27.0.1'
}
if (!(Test-Path "$JavaHome/bin/javac.exe")) { throw 'Pass -JavaHome pointing to JDK 27.' }
$jdkVersion = & "$JavaHome/bin/javac.exe" -version 2>&1
if ("$jdkVersion" -notmatch '^javac 27(?:\.|$)') { throw "JDK 27 is required, found $jdkVersion" }
$tools = Join-Path $PSScriptRoot 'tools'
$out = Join-Path $PSScriptRoot ("build/" + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
foreach ($directory in "$out/classes", "$out/dex", $tools) {
    if (!(Test-Path $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
}
$android = Join-Path $SdkRoot "platforms/$Platform/android.jar"
$bt = Join-Path $SdkRoot "build-tools/$BuildTools"
$api = Join-Path $tools "libxposed-api-$($versions.XPOSED_API).jar"
$d8 = Join-Path $tools "r8-$($versions.R8_VERSION).jar"
foreach ($p in $android, "$bt/aapt2.exe", "$bt/lib/d8.jar", "$bt/lib/apksigner.jar") {
    if (!(Test-Path -LiteralPath $p)) { throw "Missing Android tool: $p" }
}
function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed ($LASTEXITCODE)" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (!(Test-Path $d8)) {
    try {
        Invoke-WebRequest "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$($versions.R8_VERSION)/r8-$($versions.R8_VERSION).jar" -OutFile $d8
    } catch {
        Invoke-WebRequest "https://maven.aliyun.com/repository/google/com/android/tools/r8/$($versions.R8_VERSION)/r8-$($versions.R8_VERSION).jar" -OutFile $d8
    }
}
$expectedD8 = $versions.R8_SHA256
if ((Get-FileHash $d8 -Algorithm SHA256).Hash -ne $expectedD8) { throw 'R8 checksum mismatch.' }
if (!(Test-Path $api)) {
    $aar = Join-Path $tools "libxposed-api-$($versions.XPOSED_API).aar"
    Invoke-WebRequest "https://repo.maven.apache.org/maven2/io/github/libxposed/api/$($versions.XPOSED_API)/api-$($versions.XPOSED_API).aar" -OutFile $aar
    $zip = [IO.Compression.ZipFile]::OpenRead($aar)
    try { [IO.Compression.ZipFileExtensions]::ExtractToFile($zip.GetEntry('classes.jar'), $api, $true) }
    finally { $zip.Dispose() }
}
$sources = @(Get-ChildItem "$PSScriptRoot/src" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Run "$JavaHome/bin/javac.exe" (@('-encoding', 'UTF-8', '--release', $versions.JAVA_RELEASE, '-classpath', "$android;$api", '-d', "$out/classes") + $sources)
Run "$JavaHome/bin/jar.exe" @('cf', "$out/classes.jar", '-C', "$out/classes", '.')
Run "$JavaHome/bin/java.exe" @('-cp', $d8, 'com.android.tools.r8.D8', '--release', '--lib', $android, '--min-api', '26', '--output', "$out/dex", "$out/classes.jar")
Run "$bt/aapt2.exe" @('compile', '--dir', "$PSScriptRoot/res", '-o', "$out/res.zip")
Run "$bt/aapt2.exe" @('link', '-o', "$out/unsigned.apk", '-I', $android, '--manifest', "$PSScriptRoot/AndroidManifest.xml", "$out/res.zip")
$zip = [IO.Compression.ZipFile]::Open("$out/unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out/dex/classes.dex", 'classes.dex') | Out-Null
    Get-ChildItem "$PSScriptRoot/META-INF" -File -Recurse | ForEach-Object {
        $entry = [IO.Path]::GetRelativePath($PSScriptRoot, $_.FullName).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entry) | Out-Null
    }
} finally { $zip.Dispose() }
Run "$bt/zipalign.exe" @('-p', '4', "$out/unsigned.apk", "$out/aligned.apk")
$ks = $env:KEYSTORE
if (!$ks) {
    $ks = Join-Path $PSScriptRoot 'debug.keystore'
    if (!(Test-Path $ks)) {
        Run "$JavaHome/bin/keytool.exe" @('-genkeypair', '-keystore', $ks, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US')
    }
    $sign = @('--ks', $ks, '--ks-pass', 'pass:android', '--key-pass', 'pass:android')
} else {
    if (!$env:KEYSTORE_PASS) { throw 'KEYSTORE_PASS is required with KEYSTORE.' }
    $sign = @('--ks', $ks, '--ks-pass', 'env:KEYSTORE_PASS')
    if ($env:KEY_ALIAS) { $sign += @('--ks-key-alias', $env:KEY_ALIAS) }
    if ($env:KEY_PASS) { $sign += @('--key-pass', 'env:KEY_PASS') }
}
[xml]$manifest = Get-Content "$PSScriptRoot/AndroidManifest.xml"
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$final = Join-Path $PSScriptRoot "LiquidGlass-v$version.apk"
Run "$JavaHome/bin/java.exe" (@('--enable-native-access=ALL-UNNAMED', '-cp', "$bt/lib/apksigner.jar", 'com.android.apksigner.ApkSignerTool', 'sign') + $sign + @('--out', $final, "$out/aligned.apk"))
Run "$JavaHome/bin/java.exe" @('--enable-native-access=ALL-UNNAMED', '-cp', "$bt/lib/apksigner.jar", 'com.android.apksigner.ApkSignerTool', 'verify', '--verbose', $final)
Get-Item $final | Select-Object FullName, Length
