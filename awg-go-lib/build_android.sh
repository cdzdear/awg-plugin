#!/bin/bash
# Build script for AWG Go shared library for Android
# Produces .so files for arm64-v8a, armeabi-v7a, x86_64
#
# Prerequisites:
#   - Go 1.21+
#   - Android NDK r25c+ (set ANDROID_NDK_ROOT)
#   - gomobile (go install golang.org/x/mobile/cmd/gomobile@latest)
#
# Usage: ./build_android.sh [output_dir]

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${1:-$SCRIPT_DIR/../android-patch/jniLibs}"

echo "=== AWG Go Library Build for Android ==="
echo "Output: $OUTPUT_DIR"

# Check NDK
if [ -z "$ANDROID_NDK_ROOT" ]; then
    # Try common locations
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
        export ANDROID_NDK_ROOT="$(ls -d $HOME/Library/Android/sdk/ndk/*/)"
    elif [ -d "$HOME/Android/Sdk/ndk" ]; then
        export ANDROID_NDK_ROOT="$(ls -d $HOME/Android/Sdk/ndk/*/)"
    else
        echo "ERROR: Set ANDROID_NDK_ROOT"
        exit 1
    fi
fi
echo "NDK: $ANDROID_NDK_ROOT"

# Check gomobile
if ! command -v gomobile &>/dev/null; then
    echo "Installing gomobile..."
    go install golang.org/x/mobile/cmd/gomobile@latest
    gomobile init
fi

# Build for each ABI
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
GOARCHS=("arm64" "arm" "amd64")
GOARMS=("" "7" "")

mkdir -p "$OUTPUT_DIR"

for i in "${!ABIS[@]}"; do
    ABI="${ABIS[$i]}"
    GOARCH="${GOARCHS[$i]}"
    GOARM="${GOARMS[$i]}"
    
    echo ""
    echo "--- Building for $ABI (GOARCH=$GOARCH) ---"
    
    OUT_DIR="$OUTPUT_DIR/$ABI"
    mkdir -p "$OUT_DIR"
    
    # Set up Android toolchain
    case "$ABI" in
        arm64-v8a)
            CLANG="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android21-clang"
            LLVM_AR="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
            CGO_TRIPLET="aarch64-linux-android"
            ;;
        armeabi-v7a)
            CLANG="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang"
            LLVM_AR="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
            CGO_TRIPLET="armv7a-linux-androideabi"
            ;;
        x86_64)
            CLANG="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android21-clang"
            LLVM_AR="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
            CGO_TRIPLET="x86_64-linux-android"
            ;;
    esac
    
    CC="$CLANG" \
    AR="$LLVM_AR" \
    CGO_ENABLED=1 \
    GOOS=android \
    GOARCH="$GOARCH" \
    GOARM="$GOARM" \
    go build \
        -buildmode=c-shared \
        -o "$OUT_DIR/libawg.so" \
        -ldflags="-s -w" \
        .
    
    echo "Built: $OUT_DIR/libawg.so"
done

echo ""
echo "=== Build Complete ==="
echo "Libraries in: $OUTPUT_DIR"
ls -lh "$OUTPUT_DIR"/*/libawg.so 2>/dev/null || true
