# Makes the Sev7n downloads for GitHub Releases: release\Sev7n-<version>-windows.zip
# and release\Sev7n-<version>-mac-linux.zip
#
# Inside is a Sev7n folder with Sev7n.exe and its own Java, so people can unzip it and
# double-click Sev7n.exe without installing anything. The Java inside keeps its tools
# (java.exe, jpackage.exe), so Play in Window and Export Game work from the download too.
#
# Needs a full JDK 21+ (with jpackage), like build.ps1.

$ErrorActionPreference = 'Stop'
$root    = $PSScriptRoot
$release = Join-Path $root 'release'
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { Split-Path (Get-Command javac.exe).Source }

# the version, e.g. "0.5 alpha" -> "0.5-alpha" for the file name and "0.5" for Windows
$versionLine = Select-String -Path (Join-Path $root 'src\sev7n\Sev7n.java') -Pattern 'VERSION = "([^"]+)"'
$version = $versionLine.Matches[0].Groups[1].Value
$fileVersion = $version -replace ' ', '-'
$appVersion = ($version -split ' ')[0]

if (Test-Path $release) { Get-ChildItem $release | Remove-Item -Recurse -Force } # empties it (the folder itself may be open in Explorer)
$build    = Join-Path $release 'build' # not build\, so Sev7n can stay open while this runs
& (Join-Path $root 'build.ps1') -OutDir $build
if ($LASTEXITCODE -ne 0) { exit 1 }

$inputDir = Join-Path $release 'input'
$image    = Join-Path $release 'image'
New-Item -ItemType Directory -Force $inputDir, $image | Out-Null
Copy-Item (Join-Path $build 'sev7n.jar') $inputDir

# java.base/desktop/unsupported: what Sev7n uses (from jdeps); java.logging: LWJGL;
# jdk.jpackage: Export Game with Java included
Write-Host 'Packing Sev7n with Java (takes a minute)...'
& (Join-Path $jdk 'jpackage.exe') --type app-image --name Sev7n --app-version $appVersion `
    --input $inputDir --main-jar sev7n.jar --main-class sev7n.Sev7n `
    --add-modules 'java.base,java.desktop,java.logging,jdk.unsupported,jdk.jpackage' `
    --jlink-options '--strip-debug --no-man-pages --no-header-files' `
    --icon (Join-Path $root 'assets\sev7n.ico') --dest $image
if ($LASTEXITCODE -ne 0) { Write-Host 'jpackage failed.'; exit 1 }

# the guides (F1 in the editor) and the license sit next to Sev7n.exe
$app = Join-Path $image 'Sev7n'
Copy-Item (Join-Path $root 'README.md'), (Join-Path $root 'LICENSE') $app
Copy-Item -Recurse (Join-Path $root 'docs') $app


# ---------------------------------------------------------------- bundled Java
# Beginners shouldn't have to install anything: every download carries Java 21.
# Java is downloaded once into downloads\ (not in git) and checked against its published checksum.
$downloads = Join-Path $root 'downloads'
New-Item -ItemType Directory -Force $downloads | Out-Null

function Get-Checked([string]$url, [string]$file, [string]$sha256) {
    $path = Join-Path $downloads $file
    if (-not (Test-Path $path)) {
        Write-Host "Downloading $file..."
        Invoke-WebRequest $url -UseBasicParsing -OutFile "$path.part"
        Move-Item "$path.part" $path
    }
    $actual = (Get-FileHash $path -Algorithm SHA256).Hash
    if ($actual -ne $sha256.ToUpper()) { Remove-Item $path; throw "Checksum mismatch for $file" }
    return $path
}

# Temurin JDK 21 (a full JDK, so Export Game can package games too)
function Get-Jdk([string]$os, [string]$arch) {
    $info = Invoke-RestMethod "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$arch&image_type=jdk&os=$os&vendor=eclipse"
    $package = $info[0].binary.package
    Get-Checked $package.link $package.name $package.checksum
}

# Windows: Java is already inside, from jpackage
$zip = Join-Path $release "Sev7n-$fileVersion-windows.zip"
# (UnixZip writes proper / paths; Windows PowerShell's own zipping writes \ ones that some unzip tools misread)
& (Join-Path $jdk 'java.exe') (Join-Path $root 'tools\UnixZip.java') $app $zip
if ($LASTEXITCODE -ne 0) { Write-Host 'Making the Windows zip failed.'; exit 1 }
Write-Host ("Made {0} ({1:N0} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))

# macOS and Linux: the same sev7n.jar (it has every platform's native code), plus that system's Java.
# Java travels still packed and is unpacked on the player's computer the first time: its archive
# holds symbolic links that Windows can't make, but the Mac's and Linux's own tar can.
$targets = @(
    @{ Name = 'mac-apple-silicon'; Os = 'mac';   Arch = 'aarch64' }
    @{ Name = 'mac-intel';         Os = 'mac';   Arch = 'x64' }
    @{ Name = 'linux';             Os = 'linux'; Arch = 'x64' }
    @{ Name = 'linux-arm64';       Os = 'linux'; Arch = 'aarch64' }
)
$launcher = "#!/bin/sh`n# Opens Sev7n with the Java that comes with it (unpacked the first time).`n" +
    "cd `"`$(dirname `"`$0`")`"`n" +
    "if [ ! -d java ] && [ -f java.tar.gz ]; then echo 'First start: unpacking Java...'; mkdir java && tar -xzf java.tar.gz -C java --strip-components 1 && rm java.tar.gz; fi`n" +
    "if [ -x java/Contents/Home/bin/java ]; then JAVA=java/Contents/Home/bin/java; elif [ -x java/bin/java ]; then JAVA=java/bin/java; else JAVA=java; fi`n" +
    "if [ `"`$(uname)`" = `"Darwin`" ]; then exec `"`$JAVA`" -XstartOnFirstThread -jar build/sev7n.jar `"`$@`"; else exec `"`$JAVA`" -jar build/sev7n.jar `"`$@`"; fi`n"
foreach ($t in $targets) {
    Write-Host "Making the $($t.Name) download..."
    $unix = Join-Path $release "unix-$($t.Name)\Sev7n"
    New-Item -ItemType Directory -Force (Join-Path $unix 'build') | Out-Null
    Copy-Item (Join-Path $build 'sev7n.jar') (Join-Path $unix 'build')
    Copy-Item (Join-Path $root 'README.md'), (Join-Path $root 'LICENSE') $unix
    Copy-Item -Recurse (Join-Path $root 'docs') $unix
    [IO.File]::WriteAllText((Join-Path $unix 'Sev7n.command'), $launcher) # double-click on a Mac
    [IO.File]::WriteAllText((Join-Path $unix 'sev7n.sh'), $launcher)      # Linux: ./sev7n.sh
    Copy-Item (Get-Jdk $t.Os $t.Arch) (Join-Path $unix 'java.tar.gz')
    $unixZip = Join-Path $release "Sev7n-$fileVersion-$($t.Name).zip"
    & (Join-Path $jdk 'java.exe') (Join-Path $root 'tools\UnixZip.java') $unix $unixZip 'Sev7n.command' 'sev7n.sh'
    if ($LASTEXITCODE -ne 0) { Write-Host "Making the $($t.Name) zip failed."; exit 1 }
    Write-Host ("Made {0} ({1:N0} MB)" -f $unixZip, ((Get-Item $unixZip).Length / 1MB))
    Remove-Item -Recurse -Force (Split-Path $unix)
}

Remove-Item -Recurse -Force $inputDir, $image, $build