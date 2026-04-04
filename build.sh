#!/bin/bash

# Build script for Android PTY Library - Native .so compilation
# Compiles the C PTY library for all Android ABIs using the Android NDK.

set -e

echo "=== Building Android PTY Native Library ==="

# Find Android NDK
if [ -z "$NDK_HOME" ] && [ -z "$ANDROID_NDK_HOME" ]; then
    echo "NDK_HOME not set. Searching common locations..."

    if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        NDK_HOME=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -type d | sort -V | tail -n1)
    elif [ -d "$HOME/Android/Sdk/ndk" ]; then
        NDK_HOME=$(find "$HOME/Android/Sdk/ndk" -maxdepth 1 -type d | sort -V | tail -n1)
    elif [ -d "/usr/local/lib/android/sdk/ndk" ]; then
        NDK_HOME=$(find "/usr/local/lib/android/sdk/ndk" -maxdepth 1 -type d | sort -V | tail -n1)
    fi
elif [ -n "$ANDROID_NDK_HOME" ]; then
    NDK_HOME="$ANDROID_NDK_HOME"
fi

if [ -z "$NDK_HOME" ] || [ ! -d "$NDK_HOME" ]; then
    echo "Error: Android NDK not found. Set NDK_HOME or ANDROID_NDK_HOME."
    exit 1
fi

echo "Using NDK: $NDK_HOME"

# Detect host OS for prebuilt toolchain path
case "$(uname -s)" in
    Linux*)  HOST_TAG="linux-x86_64";;
    Darwin*) HOST_TAG="darwin-x86_64";;
    *)       HOST_TAG="linux-x86_64";;
esac

TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "Error: Toolchain not found at $TOOLCHAIN"
    exit 1
fi

SYSROOT="$TOOLCHAIN/sysroot"

# Output directories
LIB_OUTPUT="android/terminal-lib/src/main/jniLibs"

# Clean previous builds (only build artifacts, not source files!)
echo "Cleaning previous builds..."
rm -rf "$LIB_OUTPUT"
rm -rf libs

mkdir -p libs

# Architecture map: ABI → clang triple
declare -A ARCH_MAP=(
    ["armeabi-v7a"]="armv7a-linux-androideabi21-clang"
    ["arm64-v8a"]="aarch64-linux-android21-clang"
    ["x86"]="i686-linux-android21-clang"
    ["x86_64"]="x86_64-linux-android21-clang"
)

for arch in armeabi-v7a arm64-v8a x86 x86_64; do
    cc_name="${ARCH_MAP[$arch]}"
    CC="$TOOLCHAIN/bin/$cc_name"

    echo ""
    echo "--- Building for $arch ---"

    if [ ! -f "$CC" ]; then
        echo "  Warning: Compiler not found at $CC, skipping $arch"
        continue
    fi

    # Create output directories
    mkdir -p "libs/$arch"
    mkdir -p "$LIB_OUTPUT/$arch"

    echo "  Compiler: $CC"

    "$CC" -shared -o "libs/$arch/libterm.so" -fPIC -O2 -Wall \
        -I"$SYSROOT/usr/include" \
        -I"$SYSROOT/usr/include/android" \
        native/libterm.c \
        -llog

    # Strip the .so for smaller size
    "$TOOLCHAIN/bin/llvm-strip" "libs/$arch/libterm.so"

    # Copy to jniLibs for the library module
    cp "libs/$arch/libterm.so" "$LIB_OUTPUT/$arch/"

    # Compile ftyd daemon executable
    "$CC" -o "libs/$arch/ftyd" -O2 -Wall \
        -I"$SYSROOT/usr/include" \
        -I"$SYSROOT/usr/include/android" \
        native/ftyd.c \
        -llog

    "$TOOLCHAIN/bin/llvm-strip" "libs/$arch/ftyd"

    so_size=$(wc -c < "libs/$arch/libterm.so")
    daemon_size=$(wc -c < "libs/$arch/ftyd")
    echo "  Done: $arch (libterm.so: ${so_size} bytes, ftyd: ${daemon_size} bytes)"
done

echo ""
echo "=== Build Complete ==="
echo "Libraries in: libs/  (libterm.so + ftyd per ABI)"
echo "JNI libs in:  $LIB_OUTPUT/  (libterm.so only)"
echo ""
echo "To build the Android project:"
echo "  cd android && ./gradlew :terminal-lib:build"
echo ""
echo "To publish locally:"
echo "  cd android && ./gradlew :terminal-lib:publishToMavenLocal"
