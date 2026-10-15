#!/bin/sh
# Builds Sev7n into build/sev7n.jar on macOS or Linux (the twin of build.ps1).
# Needs JDK 21+ (Homebrew: brew install openjdk@21; Ubuntu: sudo apt install openjdk-21-jdk).
set -e
cd "$(dirname "$0")"
ROOT=$(pwd)
BUILD="${1:-$ROOT/build}"

if [ -n "$JAVA_HOME" ]; then JDK="$JAVA_HOME/bin/"; else JDK=""; fi
command -v "${JDK}javac" >/dev/null 2>&1 || { echo "Needs JDK 21 or newer (javac wasn't found)"; exit 1; }

sh "$ROOT/get-libs.sh"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/bundle"

# 1. compile
find "$ROOT/src" -name '*.java' > "$BUILD/sources.txt"
"${JDK}javac" --release 21 -encoding UTF-8 -cp "$ROOT/lib/*" -d "$BUILD/classes" @"$BUILD/sources.txt"

# 2. unpack every library into one folder (with every platform's native files)
cd "$BUILD/bundle"
for library in "$ROOT"/lib/*.jar; do "${JDK}jar" xf "$library"; done
rm -f META-INF/MANIFEST.MF
find . \( -name module-info.class -o -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' \) -delete
cd "$ROOT"

# 3. add our classes and package everything
cp -R "$BUILD/classes/." "$BUILD/bundle/"
printf 'Main-Class: sev7n.Sev7n\nMulti-Release: true\n' > "$BUILD/manifest.txt"
"${JDK}jar" --create --file "$BUILD/sev7n.jar" --manifest "$BUILD/manifest.txt" -C "$BUILD/bundle" .
rm -rf "$BUILD/bundle"
echo "Built $BUILD/sev7n.jar"
