<div align="center">

<img src="https://gitlab.com/GMB8281/wastickeraudiocreator/-/raw/master/app/src/main/ic_launcher-playstore.png?ref_type=heads" alt="WAStickerAudioCreator app icon" width="160">

# WAStickerAudioCreator

**Create custom stickers with audio and add them to WhatsApp as native stickers.**

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)](#technology-stack)
[![Rust](https://img.shields.io/badge/Rust-Native%20Core-000000?logo=rust&logoColor=white)](#technology-stack)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

</div>

---

## About

**WAStickerAudioCreator** is an Android application for creating and managing custom sticker packs with audio, then adding them directly to the official WhatsApp client.

The project builds on a reverse-engineering discovery I made on **November 25, 2026**: the WhatsApp Android client trusts locally stored sticker records in its private database and, in my tests, does not validate the `hash_of_image_part` field against the actual image content.

This missing validation makes it possible to register custom WebP and Ogg/Opus combinations locally. When a sticker is sent through WhatsApp, the official client assembles and sends the message using its native sound-sticker pipeline.

WAStickerAudioCreator turns that discovery into an Android user interface. Instead of manually editing database records, users can create stickers and packs in the app and integrate them with WhatsApp.

> **Important:** The app relies on this internal WhatsApp behavior. This is not an official sound-sticker integration or a public WhatsApp API.

## Features

- **Sticker pack creation:** Create custom packs and set their name and publisher.
- **Sound stickers:** Associate an Ogg/Opus audio file with a WebP sticker.
- **Built-in editor:** Position images, draw, add text, and choose colors.
- **Audio trimming:** Select the audio segment to associate with a sticker.
- **Animated stickers:** Import animated WebP files and apply editor elements to the animation.
- **Favorites:** Add individual stickers to WhatsApp favorites.
- **Pack installation:** Register complete packs in WhatsApp's sticker picker.
- **Pack management:** Update existing packs or remove them from WhatsApp.
- **Batch processing:** Add multiple stickers to a pack in one operation.
- **Environment checks:** Check root access and the WhatsApp database structure.

Packs and media files created by the app are stored in WAStickerAudioCreator's own private storage.

## How it works

WhatsApp for Android stores sound stickers as separate components:

- A `.webp` image file;
- An `.opus` audio file (Ogg/Opus);
- Related records in the private SQLite database, `stickers.db`.

The audio is associated with the sticker through the `sticker_audio` table, using the image hash as the relationship key. When a sticker is sent, the official client retrieves the local data and builds the outgoing message with its associated audio.

WAStickerAudioCreator uses this behavior to register the required files and metadata in WhatsApp's private storage. The message itself is still sent by the official client, using its normal media upload and end-to-end encryption pipeline.

### Architecture

The application is divided into three main parts:

- **Kotlin:** Android UI, editor, pack management, and media selection.
- **C++ / JNI:** Bridge between Kotlin code and the native library.
- **Rust:** SQLite operations, WebP/Opus file preparation, WhatsApp storage integration, and animated media processing.

Communication with WhatsApp takes place locally, using root permissions granted through the device's `su` manager.

## Requirements

Using the app requires:

1. Android device running **Android 7.0 (API 24) or later**.
2. Working **root access**, for example through Magisk or KernelSU.
3. The official WhatsApp for Android app installed (`com.whatsapp`).
4. WhatsApp must have been launched at least once so that its `stickers.db` database exists.
5. A WhatsApp version with the database schema expected by the app. Sound-sticker support was researched on **2.26.x** builds in November 2026.

The project configures builds for the `arm64-v8a` and `x86_64` architectures.

**Unrooted devices are not supported.** WhatsApp Business and modified or cloned clients are not covered by the current implementation.

## Media formats and limits

| Content | Format / limit |
|---|---|
| Image | WebP |
| Export dimensions | 512 × 512 |
| Maximum WebP size | 500 KB |
| Audio | Ogg/Opus |
| Maximum audio size | 1 MB |
| Animation | Animated WebP |
| Animated export target frame rate | 10 FPS |
| Maximum animated export duration | 10 seconds |

Audio must be an Ogg file containing Opus. Other formats are not accepted directly by the current importer.

## How to use

1. Install and open WAStickerAudioCreator.
2. Grant root access when prompted.
3. Create a pack and enter its name and publisher.
4. Add a sticker by selecting a WebP file.
5. Choose whether to associate an Ogg/Opus audio file.
6. Use the editor to position the image, draw, or add text. If audio is included, select the desired audio segment.
7. Save the sticker to the pack.
8. From the pack screen, use the option to add it to WhatsApp.
9. Open WhatsApp and find the pack in the sticker picker.

You can also add an individual sticker to WhatsApp favorites from the pack details.

The first root request may display a permission dialog from Magisk, KernelSU, or another compatible manager. The integration process may stop and restart WhatsApp so that the changes are loaded.

## Security, privacy, and limitations

- **Root is required.** The app modifies WhatsApp's private files and database records.
- **This is not a remote exploit.** The technique requires root control of the device; it does not provide remote access to other devices or accounts.
- **End-to-end encryption is not bypassed.** Messages are sent through the official WhatsApp client.
- **No custom server communication is involved.** The integration described here takes place locally on the device.
- **Compatibility may change.** WhatsApp's internal database schema is not a stable API. Updates may change it and break the integration.
- **Account risk.** Modifying WhatsApp's private data may violate its Terms of Service and could result in account restrictions or a ban. Use the app at your own risk; consider testing with a secondary account.
- **Make backups.** The code creates backups of `stickers.db` before writing changes, but this does not replace a complete backup of WhatsApp and its data.

Only use the app on devices and accounts you control. Do not use this technique to forge conversations, manipulate other people's data, or interfere with accounts belonging to others.

## Building from source

### Required tools

- Android Studio and Android SDK;
- Android NDK;
- CMake 3.22.1;
- JDK compatible with the project's Gradle configuration;
- Rust (`rustup` and `cargo`);
- `cargo-ndk`;
- `curl` and `tar`.

### 1. Configure the NDK

Set `ANDROID_NDK_HOME` to the directory of your installed Android NDK version. For example:

```bash
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.0.12077973"
```

Adjust the path to match your local installation.

### 2. Build the Rust library

From the repository root, run:

```bash
chmod +x build-rust.sh
./build-rust.sh
```

The script prepares the Android targets for `arm64-v8a` and `x86_64`, downloads the libwebp 1.4.0 source code if it is not already available, and builds the static library `libwa_core.a`.

### 3. Build the Android app

After the native library has been built, open the project in Android Studio and build the `app` module, or run:

```bash
./gradlew assembleDebug
```

CMake expects the static libraries to be available at:

```text
app/src/main/cpp/rust/prebuilt/arm64-v8a/libwa_core.a
app/src/main/cpp/rust/prebuilt/x86_64/libwa_core.a
```

These files are generated by `build-rust.sh` and must exist before building the APK.

## Project structure

```text
.
├── app/
│   └── src/main/
│       ├── cpp/
│       │   ├── native-bridge.cpp
│       │   └── rust/
│       │       ├── src/
│       │       │   ├── anim.rs
│       │       │   ├── ops.rs
│       │       │   ├── opus.rs
│       │       │   ├── shell.rs
│       │       │   └── webp.rs
│       │       └── Cargo.toml
│       └── java/com/marinov/wastickeraudiocreator/
│           ├── stickers/
│           ├── ui/
│           └── whatsapp/
├── build-rust.sh
├── gradle/
└── LICENSE
```

## Related research

The discovery behind this application is documented separately in **WAStickerAudioHack**, which contains the research into WhatsApp's private database, the relationship between WebP and Opus files, and the proof of concept for injecting sound stickers:

[GitHub — WAStickerAudioHack](https://github.com/GMB8281/WAStickerAudioHack)

[GitLab — WAStickerAudioHack](https://gitlab.com/GMB8281/WAStickerAudioHack)

[Codeberg — WAStickerAudioHack](https://codeberg.org/GMB8281/WAStickerAudioCreator)

## License

This project is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for the full license text.