#!/usr/bin/env bash
set -e

NCNN_VERSION="20240820"
DEFAULT_ZIP="ncnn-${NCNN_VERSION}-android-vulkan-shared.zip"
NCNN_URL="https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${DEFAULT_ZIP}"
TARGET_DIR="app/src/main/cpp/ncnn"
TEMP_DIR="temp_ncnn_extract"

echo "=== Setting up NCNN Android Vulkan SDK ==="

mkdir -p "app/src/main/cpp"

# Check if any ncnn zip already exists locally
LOCAL_ZIP=$(ls ncnn-*-android-vulkan-shared.zip 2>/dev/null | head -n 1)

if [ -n "$LOCAL_ZIP" ] && [ -f "$LOCAL_ZIP" ]; then
    echo "Found local archive: $LOCAL_ZIP"
    ZIP_TO_EXTRACT="$LOCAL_ZIP"
else
    echo "Downloading ${DEFAULT_ZIP} from ${NCNN_URL}..."
    curl -L -o "$DEFAULT_ZIP" "$NCNN_URL"
    ZIP_TO_EXTRACT="$DEFAULT_ZIP"
fi

echo "Extracting ${ZIP_TO_EXTRACT}..."
rm -rf "$TEMP_DIR" "$TARGET_DIR"
mkdir -p "$TEMP_DIR"
unzip -q "$ZIP_TO_EXTRACT" -d "$TEMP_DIR"

# Find directory containing arm64-v8a ABI folder
ABI_ROOT=$(find "$TEMP_DIR" -type d -name "arm64-v8a" | head -n 1)

if [ -n "$ABI_ROOT" ]; then
    PARENT_DIR=$(dirname "$ABI_ROOT")
    mv "$PARENT_DIR" "$TARGET_DIR"
else
    mv "$TEMP_DIR" "$TARGET_DIR"
fi

rm -rf "$TEMP_DIR"
echo "=== NCNN SDK successfully installed at ${TARGET_DIR} ==="
ls -la "$TARGET_DIR"

echo "=== Copying shared libncnn.so to jniLibs for APK packaging ==="
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if [ -f "$TARGET_DIR/$abi/lib/libncnn.so" ]; then
        mkdir -p "app/src/main/jniLibs/$abi"
        cp -f "$TARGET_DIR/$abi/lib/libncnn.so" "app/src/main/jniLibs/$abi/libncnn.so"
        echo "Copied libncnn.so to app/src/main/jniLibs/$abi"
    fi
done
