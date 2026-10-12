# Builds Sev7n into build\sev7n.jar: one file containing the engine and its libraries.
# Needs JDK 21+. Downloads the libraries into lib\ the first time (see get-libs.ps1).
# -OutDir builds somewhere else (release.ps1 uses this, so Sev7n can stay open meanwhile).

param([string]$OutDir = (Join-Path $PSScriptRoot 'build'))

$ErrorActionPreference = 'Stop'
$root  = $PSScriptRoot
$build = $OutDir
$jar   = Join-Path $build 'sev7n.jar'
$lib   = Join-Path $root 'lib'

$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command javac.exe).Source }
$javac = Join-Path $jdk 'javac.exe'
$jarTool = Join-Path $jdk 'jar.exe'

# Windows locks the jar while Sev7n is running
if (Test-Path $jar) {
    try { [IO.File]::Open($jar, 'Open', 'ReadWrite', 'None').Close() }
    catch { Write-Host 'Sev7n is still open. Close it, then build again.'; exit 1 }
}

# downloads any library that's missing (quick when they're all there)
& (Join-Path $root 'get-libs.ps1')

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
$classes = Join-Path $build 'classes'
$bundle  = Join-Path $build 'bundle'
New-Item -ItemType Directory -Force $classes, $bundle | Out-Null

# 1. compile
$sources = Get-ChildItem -Recurse (Join-Path $root 'src') -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
$argFile = Join-Path $build 'sources.txt'
[IO.File]::WriteAllLines($argFile, $sources)
& $javac --release 21 -encoding UTF-8 -cp "$lib\*" -d $classes "@$argFile"
if ($LASTEXITCODE -ne 0) { Write-Host "`nBuild failed."; exit 1 }

# 2. unpack every library into one folder (including their native .dll files)
Push-Location $bundle
foreach ($library in Get-ChildItem $lib -Filter *.jar) {
    & $jarTool xf $library.FullName
}
Pop-Location
# drop library metadata that would clash or confuse the merged jar
Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $bundle 'META-INF\MANIFEST.MF')
Get-ChildItem -Recurse $bundle -Include 'module-info.class', '*.SF', '*.RSA', '*.DSA' | Remove-Item -Force

# 3. add our classes and package everything
Copy-Item -Recurse -Force (Join-Path $classes '*') $bundle
$manifest = Join-Path $build 'manifest.txt'
[IO.File]::WriteAllText($manifest, "Main-Class: sev7n.Sev7n`nMulti-Release: true`n")
& $jarTool --create --file $jar --manifest $manifest -C $bundle .
if ($LASTEXITCODE -ne 0) { Write-Host "`nPackaging failed."; exit 1 }

Remove-Item -Recurse -Force $bundle
Write-Host "Built $jar"
