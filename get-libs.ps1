# Downloads Sev7n's libraries from Maven Central into lib\ and verifies their SHA-1 checksums.
#   LWJGL    - OpenGL, Vulkan, windowing and input (+ shaderc, which compiles shaders for Vulkan)
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

$artifacts = @(
    "org/lwjgl/lwjgl/$lwjgl/lwjgl-$lwjgl.jar"
    "org/lwjgl/lwjgl/$lwjgl/lwjgl-$lwjgl-natives-windows.jar"
    "org/lwjgl/lwjgl-glfw/$lwjgl/lwjgl-glfw-$lwjgl.jar"
    "org/lwjgl/lwjgl-glfw/$lwjgl/lwjgl-glfw-$lwjgl-natives-windows.jar"
    "org/lwjgl/lwjgl-opengl/$lwjgl/lwjgl-opengl-$lwjgl.jar"
    "org/lwjgl/lwjgl-opengl/$lwjgl/lwjgl-opengl-$lwjgl-natives-windows.jar"
    "org/lwjgl/lwjgl-vulkan/$lwjgl/lwjgl-vulkan-$lwjgl.jar"
    "org/lwjgl/lwjgl-shaderc/$lwjgl/lwjgl-shaderc-$lwjgl.jar"
    "org/lwjgl/lwjgl-shaderc/$lwjgl/lwjgl-shaderc-$lwjgl-natives-windows.jar"
    "org/joml/joml/$joml/joml-$joml.jar"
    "io/github/spair/imgui-java-binding/$imgui/imgui-java-binding-$imgui.jar"
    "io/github/spair/imgui-java-lwjgl3/$imgui/imgui-java-lwjgl3-$imgui.jar"
    "io/github/spair/imgui-java-natives-windows/$imgui/imgui-java-natives-windows-$imgui.jar"
)

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
