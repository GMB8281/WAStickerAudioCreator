
<div align="center">

<img src="https://gitlab.com/GMB8281/wastickeraudiocreator/-/raw/master/app/src/main/ic_launcher-playstore.png?ref_type=heads" alt="WAStickerAudioCreator app icon" width="160">

# WAStickerAudioCreator

**Create custom stickers with audio and add them to WhatsApp as native stickers.**

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](#requisitos)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)](#tecnologias)
[![Rust](https://img.shields.io/badge/Rust-Native%20Core-000000?logo=rust&logoColor=white)](#tecnologias)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

<div>

---

## About the Project

**WAStickerAudioCreator** is an Android application for creating and managing custom sticker packs with audio and adding them directly to the official WhatsApp application.

The project is based on a reverse-engineering discovery I made on **November 25, 2026**: the WhatsApp Android client trusts local sticker records stored in its private database and, in the tests conducted, does not validate the `hash_of_image_part` field against the actual image content.

This validation flaw makes it possible to locally register custom WebP and Ogg/Opus combinations. When the user sends the sticker through WhatsApp itself, the official application builds and sends the audio message through the platform's native workflow.

WAStickerAudioCreator turns this discovery into an Android interface: instead of manually performing database operations, users can create their stickers and packs within the application and request integration with WhatsApp.

> **Important:** The application relies on this internal WhatsApp behavior. This is not an official audio sticker integration, nor does it use a public WhatsApp API.

## Features

- **Pack creation:** Create your own sticker packs by defining a name and publisher.
- **Stickers with audio:** Associate an Ogg/Opus audio file with a WebP sticker.
- **Built-in editor:** Move and position images, draw, add text, and choose colors.
- **Audio trimming:** Select the audio segment to be associated with the sticker.
- **Animated stickers:** Import animated WebP files and overlay editor elements onto the animation.
- **Favorites:** Add individual stickers to WhatsApp favorites.
- **Pack installation:** Register complete packs in WhatsApp's sticker picker.
- **Updates and removal:** Update previously added packs or remove them from WhatsApp.
- **Batch processing:** Add multiple stickers to a pack in a single operation.
- **Environment verification:** Check root access and the database structure available in WhatsApp.

Packs and media files created by the application are stored in WAStickerAudioCreator's own private storage.

## How It Works

WhatsApp for Android stores audio stickers using separate components:

- A `.webp` image;
- An `.opus` audio file (Ogg/Opus);
- Related records in the private SQLite database `stickers.db`.

The audio is associated with the sticker through the `sticker_audio` table, using the image hash as the relationship key. When a sticker is sent, the official client retrieves the local data and builds the message with the associated audio.

WAStickerAudioCreator uses this behavior to register the necessary files and metadata in WhatsApp's private storage. The actual sending process is still handled by the official client, including its standard media transmission workflow and end-to-end encryption.

### Architecture

The application is divided into three main components:

- **Kotlin:** Android interface, editor, pack management, and media selection.
- **C++ / JNI:** Bridge between Kotlin code and the native library.
- **Rust:** SQLite database operations, WebP/Opus file preparation, integration with WhatsApp storage, and animated media processing.

Communication with WhatsApp takes place locally, using root permissions granted through the device's `su` manager.

## Requirements

Using the application requires:

1. An Android device running **Android 7.0 (API 24) or higher**.
2. Functional **root access**, for example, through Magisk or KernelSU.
3. The official WhatsApp for Android application installed (`com.whatsapp`).
4. WhatsApp must have been launched at least once, with its `stickers.db` database already created.
5. A WhatsApp version that uses the sticker database schema expected by the application. Audio sticker support was researched on **2.26.x** versions in November 2026.

The project is configured to build for the `arm64-v8a` and `x86_64` architectures.

**Devices without root access are not supported.** WhatsApp Business and modified/cloned clients are not covered by the current implementation.

## Media Formats and Limits

| Content | Format / Limit |
| --- | --- |
| Image | WebP |
| Export dimensions | 512 × 512 |
| Maximum WebP size | 500 KB |
| Audio | Ogg/Opus |
| Maximum audio size | 1 MB |
| Animation | Animated WebP |
| Target animated export frame rate | 10 FPS |
| Maximum animated export duration | 10 seconds |

The audio must be an Ogg file containing Opus. Files in other formats are not directly supported by the current importer.

## How to Use

1. Install and open WAStickerAudioCreator.
2. Grant root access when prompted.
3. Create a pack and enter its name and publisher.
4. Add a sticker by selecting a WebP file.
5. Choose whether to associate an Ogg/Opus audio file.
6. Use the editor to position the image, draw, or add text. If audio is included, adjust the desired segment.
7. Save the sticker to the pack.
8. On the pack screen, use the option to add it to WhatsApp.
9. Open WhatsApp and look for the pack in the sticker picker.

You can also add an individual sticker to your favorites directly from the pack details screen.

The first root access request may display a prompt from Magisk, KernelSU, or another compatible manager. The integration process may close and reopen WhatsApp so that the changes can be loaded.

## Security, Privacy, and Limitations

- **Root access is mandatory.** The application modifies WhatsApp's private files and records.
- **This is not a remote exploit.** The technique requires root control of the user's own device; it does not allow remote access to third-party accounts or devices.
- **It does not break end-to-end encryption.** Messages are sent through the official WhatsApp client.
- **No communication with proprietary servers.** The integration described here takes place locally on the device.
- **Compatibility may change.** WhatsApp's internal database schema is not a stable API and may change with updates, potentially breaking the integration.
- **Account risk.** Modifying private data may violate WhatsApp's Terms of Service and could result in account restrictions or a ban. Use at your own risk; consider testing with a secondary account.
- **Back up your data.** The code creates backups of the `stickers.db` database before writing changes, but this does not replace a complete backup of WhatsApp and its data.

Only use the application on devices and accounts under your control. Do not use this technique to forge conversations, manipulate third-party data, or interfere with other people's accounts.

## Building from Source

### Required Tools

- Android Studio and Android SDK;
- Android NDK;
- CMake 3.22.1;
- JDK compatible with the Gradle configuration;
- Rust (`rustup` and `cargo`);
- `cargo-ndk`;
- `curl` and `tar`.

### 1\. Configure the NDK

Set `ANDROID_NDK_HOME` to the directory of your installed Android NDK version. Example:

```bash
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.0.12077973"
```

Adjust the path to match the version installed in your environment.

### 2\. Build the Rust Library

From the repository root, run:

```bash
chmod +x build-rust.sh
./build-rust.sh
```

The script prepares the Android targets for `arm64-v8a` and `x86_64`, downloads the libwebp 1.4.0 source code if it is not already present, and builds the `libwa_core.a` static library.

### 3\. Build the Application

After the native library has been compiled, open the project in Android Studio and build the `app` module, or run:

```bash
./gradlew assembleDebug
```

CMake expects to find the static libraries at:

```text
app/src/main/cpp/rust/prebuilt/arm64-v8a/libwa_core.a
app/src/main/cpp/rust/prebuilt/x86_64/libwa_core.a
```

These files are generated by `build-rust.sh` and must exist before building the APK.

## Project Structure

text

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

## Related Research

The discovery behind this application is documented separately in the **WAStickerAudioHack** repository, which contains research on the private database, the relationship between WebP and Opus, and the sticker injection proof of concept:

[GitLab — WAStickerAudioHack](<https://gitlab.com/GMB8281/WAStickerAudioHack>)

[GitHub — WAStickerAudioHack](<https://github.com/GMB8281/WAStickerAudioHack>)

## License

This project is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See the LICENSE file for the full license text.