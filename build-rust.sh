#!/usr/bin/env bash
# Builds libwa_core.a (Rust + vendored libwebp) per ABI.
# Requires: rustup, cargo-ndk, Android NDK, curl, tar.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME, e.g. export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/27.0.12077973}"
cd "$(dirname "$0")/app/src/main/cpp/rust"

WEBP_VER=1.4.0
if [ ! -d vendor/libwebp ]; then
    echo "Fetching libwebp $WEBP_VER ..."
    mkdir -p vendor
    curl -L -o /tmp/libwebp.tar.gz \
        "https://github.com/webmproject/libwebp/archive/refs/tags/v${WEBP_VER}.tar.gz"
    tar -xzf /tmp/libwebp.tar.gz -C vendor
    mv "vendor/libwebp-${WEBP_VER}" vendor/libwebp
fi

rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a build --release
mkdir -p prebuilt/arm64-v8a
cp target/aarch64-linux-android/release/libwa_core.a prebuilt/arm64-v8a/
cargo ndk -t x86_64 build --release
mkdir -p prebuilt/x86_64
cp target/x86_64-linux-android/release/libwa_core.a prebuilt/x86_64/
echo "Done. Now build the app with Gradle / Android Studio."