# Makes the Sev7n download for GitHub Releases: release\Sev7n-<version>-windows.zip
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

if (Test-Path $release) { Remove-Item -Recurse -Force $release }
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

$zip = Join-Path $release "Sev7n-$fileVersion-windows.zip"
Compress-Archive -Path $app -DestinationPath $zip
Remove-Item -Recurse -Force $inputDir, $image, $build
Write-Host ("Made {0} ({1:N0} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
