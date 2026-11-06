#!/usr/bin/env bash
# 构建完整 APK：Rust 交叉编译 -> jniLibs -> Gradle 打包签名
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
JNI_DIR="$ROOT/android/app/src/main/jniLibs"

ABIS="arm64-v8a armeabi-v7a x86 x86_64"
TARGET_ARGS=()
for abi in $ABIS; do
    TARGET_ARGS+=("-t" "$abi")
done

echo "[1/4] 生成 AXML 测试样本与图标资源"
python3 "$ROOT/scripts/gen_axml_fixture.py"
python3 "$ROOT/scripts/gen_icons.py"

echo "[2/4] cargo test -p shizuku-core"
(cd "$ROOT" && cargo test -p shizuku-core)

echo "[3/4] cargo-ndk 交叉编译 libshizuku_core.so ($ABIS)"
mkdir -p "$JNI_DIR"
(cd "$ROOT" && cargo ndk "${TARGET_ARGS[@]}" -o "$JNI_DIR" build --release -p shizuku-android-ffi)

echo "[4/4] Gradle assembleDebug"
(cd "$ROOT/android" && ./gradlew assembleDebug --no-daemon)

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
cp -f "$APK" "$ROOT/ShizukuInstaller.apk"
echo "完成: $ROOT/ShizukuInstaller.apk ($(stat -c%s "$ROOT/ShizukuInstaller.apk") bytes)"
