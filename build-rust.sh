#!/usr/bin/env bash
# Builds libwa_core.a (Rust) for each ABI and copies it where CMake expects it.
# Requires: rustup, cargo-ndk (cargo install cargo-ndk), Android NDK.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME, e.g. export ANDROID_NDK_HOME=\$HOME/Android/Sdk/ndk/27.0.12077973}"
cd "$(dirname "$0")/app/src/main/cpp/rust"
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk -t arm64-v8a build --release
mkdir -p prebuilt/arm64-v8a
cp target/aarch64-linux-android/release/libwa_core.a prebuilt/arm64-v8a/
cargo ndk -t x86_64 build --release
mkdir -p prebuilt/x86_64
cp target/x86_64-linux-android/release/libwa_core.a prebuilt/x86_64/
echo "Done. Now build the app with Gradle / Android Studio."
