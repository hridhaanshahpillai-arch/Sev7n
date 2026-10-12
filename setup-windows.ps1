# Sets Sev7n up on Windows (for the current user only, no admin needed):
#   Desktop:        "Sev7n" (opens the editor)
#   Start menu:     "Sev7n"
#   .7n / .8s:      double-click opens scripts and Eights graphs in Sev7n, with the Sev7n icon
#
# Run it again any time, e.g. after moving the folder or updating Java:
#   powershell -ExecutionPolicy Bypass -File setup-windows.ps1

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar  = Join-Path $root 'build\sev7n.jar'
$icon = Join-Path $root 'assets\sev7n.ico'

# javaw.exe runs Java apps without a console window
if ($env:JAVA_HOME) {
    $javaw = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
} else {
    $javaw = (Get-Command javaw.exe).Source
}

if (-not (Test-Path $jar)) {
    & cmd /c (Join-Path $root 'build.bat')
}
if (-not (Test-Path $icon)) {
    & (Join-Path (Split-Path $javaw) 'java.exe') -cp (Join-Path $root 'build\classes') sev7n.ui.AppIcon $icon
}

# ---------------------------------------------------------------- shortcuts

$shell = New-Object -ComObject WScript.Shell

function New-Shortcut([string]$path, [string]$target, [string]$arguments, [string]$iconPath, [string]$description) {
    $link = $shell.CreateShortcut($path)
    $link.TargetPath = $target
    $link.Arguments = $arguments
    $link.WorkingDirectory = $root
    $link.Description = $description
    if ($iconPath) { $link.IconLocation = "$iconPath,0" }
    $link.Save()
    Write-Host "Created $path"
}

$desktop   = [Environment]::GetFolderPath('Desktop')
$startMenu = [Environment]::GetFolderPath('Programs')
$launch    = "-jar `"$jar`""

New-Shortcut (Join-Path $desktop 'Sev7n.lnk')        $javaw $launch $icon 'Sev7n game engine'
New-Shortcut (Join-Path $startMenu 'Sev7n.lnk')      $javaw $launch $icon 'Sev7n game engine'

# ---------------------------------------------------------------- .7n file type

function Set-DefaultValue([string]$key, [string]$value) {
    if (-not (Test-Path $key)) { New-Item -Path $key -Force | Out-Null }
    Set-Item -Path $key -Value $value
}

$classes = 'HKCU:\Software\Classes'
$fileTypes = @{ '.7n' = @('Sev7n.Script', 'Sev7n script'); '.8s' = @('Sev7n.Graph', 'Sev7n Eights graph') }
foreach ($extension in $fileTypes.Keys) {
    $progId, $description = $fileTypes[$extension]
    Set-DefaultValue "$classes\$extension"                     $progId
    Set-DefaultValue "$classes\$progId"                        $description
    Set-DefaultValue "$classes\$progId\DefaultIcon"            "$icon,0"
    Set-DefaultValue "$classes\$progId\shell\open\command"     "`"$javaw`" -jar `"$jar`" `"%1`""
}

# Tell Explorer file types changed, so icons refresh without a restart
Add-Type -Namespace Sev7n -Name Shell -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("shell32.dll")]
public static extern void SHChangeNotify(int eventId, int flags, System.IntPtr item1, System.IntPtr item2);
'@
[Sev7n.Shell]::SHChangeNotify(0x08000000, 0, [IntPtr]::Zero, [IntPtr]::Zero)
Write-Host "Registered .7n and .8s files to open with Sev7n"
