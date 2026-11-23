# Clip Studio

Clip Studio is an Android app for turning imported recordings into short clips. It targets Android 10 and newer on 64-bit ARM phones.

The first build accepts a YouTube recording link or a video from the Android file picker. Choose a layout, select a ready section, trim it, and save an MP4 to `Movies/Clip Studio`. The split layout renders the facecam on the top half and gameplay on the bottom half, with independent crop controls. The player in this build previews the original source.

For a compatible YouTube recording, the importer writes independently playable sections as the source arrives. Each completed section becomes eligible for trimming and export during the transfer. If the progressive source is unavailable before any sections appear, the existing full-file importer is tried. YouTube availability varies by recording. Use media you have permission to use.

Build with JDK 17, Android SDK 35, and Gradle 8.9 using `gradle :app:assembleDebug`.

## Source

The YouTube link, download, and MP4 validation logic is adapted from [ClipForge](https://github.com/mechy007/ClipForge/tree/72fb6008c2051bc53d5a3e783f913558ed7fc2c6) under the MIT license included here. Clipper Engine and Clipper Free informed the workflow. Android FFmpeg dependencies and notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
