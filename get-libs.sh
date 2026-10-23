#!/bin/sh
# Downloads Sev7n's libraries from Maven Central into lib/ and checks their SHA-1 checksums
# (the macOS and Linux twin of get-libs.ps1). build.sh runs this automatically.
set -e
cd "$(dirname "$0")"
mkdir -p lib

LWJGL=3.3.6
IMGUI=1.89.0
JOML=1.10.8
REPO=https://repo1.maven.org/maven2

ARTIFACTS="
org/lwjgl/lwjgl/$LWJGL/lwjgl-$LWJGL.jar
org/lwjgl/lwjgl-glfw/$LWJGL/lwjgl-glfw-$LWJGL.jar
org/lwjgl/lwjgl-opengl/$LWJGL/lwjgl-opengl-$LWJGL.jar
org/lwjgl/lwjgl-vulkan/$LWJGL/lwjgl-vulkan-$LWJGL.jar
org/lwjgl/lwjgl-shaderc/$LWJGL/lwjgl-shaderc-$LWJGL.jar
org/lwjgl/lwjgl-tinyfd/$LWJGL/lwjgl-tinyfd-$LWJGL.jar
org/lwjgl/lwjgl-stb/$LWJGL/lwjgl-stb-$LWJGL.jar
org/joml/joml/$JOML/joml-$JOML.jar
io/github/spair/imgui-java-binding/$IMGUI/imgui-java-binding-$IMGUI.jar
io/github/spair/imgui-java-lwjgl3/$IMGUI/imgui-java-lwjgl3-$IMGUI.jar
io/github/spair/imgui-java-natives-windows/$IMGUI/imgui-java-natives-windows-$IMGUI.jar
io/github/spair/imgui-java-natives-macos/$IMGUI/imgui-java-natives-macos-$IMGUI.jar
io/github/spair/imgui-java-natives-linux/$IMGUI/imgui-java-natives-linux-$IMGUI.jar
org/lwjgl/lwjgl-vulkan/$LWJGL/lwjgl-vulkan-$LWJGL-natives-macos.jar
org/lwjgl/lwjgl-vulkan/$LWJGL/lwjgl-vulkan-$LWJGL-natives-macos-arm64.jar
"
# every platform's native code, so one sev7n.jar runs everywhere
for module in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-shaderc lwjgl-tinyfd lwjgl-stb; do
    for platform in windows macos macos-arm64 linux linux-arm64; do
        ARTIFACTS="$ARTIFACTS org/lwjgl/$module/$LWJGL/$module-$LWJGL-natives-$platform.jar"
    done
done

sha1() {
    if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi
}

for path in $ARTIFACTS; do
    file="lib/$(basename "$path")"
    [ -f "$file" ] && continue
    echo "Downloading $(basename "$path")"
    expected=$(curl -fsSL "$REPO/$path.sha1" | cut -d' ' -f1)
    curl -fsSL "$REPO/$path" -o "$file.part"
    actual=$(sha1 "$file.part")
    if [ "$actual" != "$expected" ]; then
        rm -f "$file.part"
        echo "Checksum mismatch for $path (expected $expected, got $actual)"
        exit 1
    fi
    mv "$file.part" "$file"
done
echo "Libraries ready in lib/"
