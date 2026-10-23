# Downloads Sev7n's libraries from Maven Central into lib\ and verifies their SHA-1 checksums.
#   LWJGL    - OpenGL, Vulkan, windowing and input (+ shaderc, which compiles shaders for Vulkan,
#              and stb, which reads .ogg sound files)
#   JOML     - 3D math (vectors, matrices)
#   imgui    - Dear ImGui, the editor's UI toolkit
#
# build.ps1 runs this automatically when lib\ is missing.

$ErrorActionPreference = 'Stop'
$lib = Join-Path $PSScriptRoot 'lib'
New-Item -ItemType Directory -Force $lib | Out-Null

$lwjgl = '3.3.6'
$imgui = '1.89.0'
$joml  = '1.10.8'

# one Sev7n runs on Windows, macOS (Intel and Apple Silicon) and Linux: every platform's native code goes in
$platforms = @('windows', 'macos', 'macos-arm64', 'linux', 'linux-arm64')
$artifacts = @(
    "org/lwjgl/lwjgl/$lwjgl/lwjgl-$lwjgl.jar"
    "org/lwjgl/lwjgl-glfw/$lwjgl/lwjgl-glfw-$lwjgl.jar"
    "org/lwjgl/lwjgl-opengl/$lwjgl/lwjgl-opengl-$lwjgl.jar"
    "org/lwjgl/lwjgl-vulkan/$lwjgl/lwjgl-vulkan-$lwjgl.jar"
    "org/lwjgl/lwjgl-shaderc/$lwjgl/lwjgl-shaderc-$lwjgl.jar"
    "org/lwjgl/lwjgl-tinyfd/$lwjgl/lwjgl-tinyfd-$lwjgl.jar"
    "org/lwjgl/lwjgl-stb/$lwjgl/lwjgl-stb-$lwjgl.jar"
    "org/joml/joml/$joml/joml-$joml.jar"
    "io/github/spair/imgui-java-binding/$imgui/imgui-java-binding-$imgui.jar"
    "io/github/spair/imgui-java-lwjgl3/$imgui/imgui-java-lwjgl3-$imgui.jar"
    "io/github/spair/imgui-java-natives-windows/$imgui/imgui-java-natives-windows-$imgui.jar"
    "io/github/spair/imgui-java-natives-macos/$imgui/imgui-java-natives-macos-$imgui.jar"
    "io/github/spair/imgui-java-natives-linux/$imgui/imgui-java-natives-linux-$imgui.jar"
)
foreach ($module in @('lwjgl', 'lwjgl-glfw', 'lwjgl-opengl', 'lwjgl-shaderc', 'lwjgl-tinyfd', 'lwjgl-stb')) {
    foreach ($platform in $platforms) { $artifacts += "org/lwjgl/$module/$lwjgl/$module-$lwjgl-natives-$platform.jar" }
}
# Vulkan on a Mac runs through MoltenVK, which comes with LWJGL (other systems have Vulkan in their drivers)
$artifacts += "org/lwjgl/lwjgl-vulkan/$lwjgl/lwjgl-vulkan-$lwjgl-natives-macos.jar"
$artifacts += "org/lwjgl/lwjgl-vulkan/$lwjgl/lwjgl-vulkan-$lwjgl-natives-macos-arm64.jar"

$repo = 'https://repo1.maven.org/maven2'
foreach ($path in $artifacts) {
    $file = Join-Path $lib (Split-Path $path -Leaf)
    if (Test-Path $file) { continue }

    Write-Host "Downloading $(Split-Path $path -Leaf)"
    $expected = (Invoke-WebRequest "$repo/$path.sha1" -UseBasicParsing).Content.ToString().Trim().Split(' ')[0]
    Invoke-WebRequest "$repo/$path" -UseBasicParsing -OutFile "$file.part"
    $actual = (Get-FileHash "$file.part" -Algorithm SHA1).Hash
    if ($actual -ne $expected.ToUpper()) {
        Remove-Item "$file.part"
        throw "Checksum mismatch for $path (expected $expected, got $actual)"
    }
    Move-Item "$file.part" $file
}
Write-Host "Libraries ready in $lib"
