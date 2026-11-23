# Clip Studio

Clip Studio is an Android-only editor for turning recorded videos into short clips. It runs on 64-bit ARM phones with Android 10 or newer.

## Import and edit

1. Paste a YouTube recording link or open a local video. Select the initial layout before import.
2. Review ready sections and suggested highlights. The phone looks for relative loudness, laughter, shouting, cheering, and visual activity. Suggestions describe the signal that triggered them.
3. Trim the clip and choose a layout. The 50/50 layout puts the facecam above gameplay. Drag or pinch each panel on the framing preview. Use Play source to review the original recording.
4. Generate offline captions in auto-detect mode or a selected language, edit their text, and choose Clean, Karaoke, or Pop.
5. Export one MP4 or queue up to ten. Choose 720p or 1080p, with optional muting. Files go to `Movies/Clip Studio` and can be shared from the app.

Compatible YouTube recordings yield independently playable sections as the source arrives. Sections can be analyzed, trimmed, and exported while the download continues. A recording without a compatible progressive format can use the full-file importer. A clip within the progressive view is limited to its selected section, and YouTube availability varies by recording.

Grant notification permission to see import, analysis, and export progress outside the app. The latest imported project, completed sections, and highlight suggestions can be reopened after a restart. Interrupted transfers must be started again. Caption edits and the export queue are kept during the current app session. Automatic caption generation supports clips up to two minutes. Use media you have permission to process.

## Build

Use JDK 17, Android SDK 35, Android NDK 27.2.12479018, CMake 3.22.1, and Gradle 8.9.

```sh
gradle :app:assembleDebug :app:testDebugUnitTest
```

The build verifies the SHA-256 fingerprints of its bundled offline speech and audio-event models. The GitHub Actions build produces a debug APK and a SHA-256 checksum.

## References

- [ClipForge](https://github.com/mechy007/ClipForge/tree/72fb6008c2051bc53d5a3e783f913558ed7fc2c6) supplied the MIT-licensed YouTube import, Whisper integration, caption timing, and audio model integration.
- [Clipper Engine](https://github.com/rrobin9/clipper-engine) and the supplied Clipper Free workflow informed the dashboard and import-first editing flow.
- [Eklipse's editor guide](https://eklipse.gg/help/how-to-perform-basic-edits-in-eklipse-studio/) and its [workflow video](https://www.youtube.com/watch?v=yqJFXXdCvZ8) informed the progression from highlight review to framing and export.
- [PySceneDetect](https://github.com/Breakthrough/PySceneDetect) was a reference for visual-change detection. [YAMNet's class map](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv) informed which reaction events to use.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependencies and models.
