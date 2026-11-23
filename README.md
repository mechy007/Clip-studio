# Clip Studio

Clip Studio is an Android-only editor for turning recorded videos into short clips. It runs on 64-bit ARM phones with Android 10 or newer.

## Import and edit

1. Paste a YouTube recording link or open a local video. Select the initial layout before import.
2. Review ready sections and suggested highlights. The phone looks for relative loudness, laughter, shouting, cheering, and visual activity. Suggestions describe the signal that triggered them.
3. Trim the clip and choose a layout. The 50/50 layout puts the facecam above gameplay. Vertical fill, vertical fit, square, and landscape are also available. Drag or pinch each panel on the framing preview. Use Play source to review the original recording.
4. In Captions, choose the streamer’s audio track for offline transcription or enter custom text for the selected trim. Choose a style, position, and size. Preview the result before exporting or save an SRT file for an external editor.
5. Export one MP4 or queue up to ten. Choose 720p, 1080p, or 1440p, with optional muting. Files go to `Movies/Clip Studio` and can be shared from the app.

For long uploads and finished livestream replays, choose **30-minute parts** in Import. Each download stops after one part. Clip and export from the part, then choose **Download next part** when ready. The last part may be shorter. Completed parts and the next starting point are saved with the project. Open the finished replay once YouTube makes it available after a livestream.

Compatible YouTube recordings yield independently playable sections as the source arrives. Sections can be analyzed, trimmed, and exported while the download continues. A recording without a compatible progressive format can use the full-file importer. Adjacent ready sections can be joined for clips that cross a boundary. YouTube availability varies by recording.

Grant notification permission to see import, analysis, and export progress outside the app. Saved projects, completed sections, highlight suggestions, and finished clip links can be reopened after a restart. Compatible progressive YouTube transfers can resume from a saved section boundary. Queued clips and their selected caption styles are restored with the project. Unqueued caption edits are kept during the current app session. Automatic caption generation supports clips up to two minutes. Use media you have permission to process.

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

## Timeline and project tools

- **Preview export** renders a lower-resolution playback copy using the export layout, facecam path, audio effects, and caption timings. The waveform and playhead scrub the source while setting the clip boundaries.
- **Resume download** uses the saved section boundary and selected format when reconnecting to a compatible recorded YouTube source. **Edit across next section** joins two consecutive ready parts into an edit source while the rest of the download continues.
- **Highlight focus** offers balanced, funny reactions, gameplay action, and talking highlights. Nearby overlapping suggestions are deduplicated.
- **Track facecam** samples the trimmed interval and animates its crop. Either crop can be locked, and framing can still be adjusted manually.
- **Projects** keeps recent recordings, queued exports, and generated MP4 links. Save a **Creator preset** to reuse layout, crops, captions, quality, and audio settings.
- **Audio finishing** adds loudness normalization, fades, and optional independent gains for the source's first two audio tracks. A source with one premixed track supports normalization and fades.

## Faster editing and exports

- **Fast Export** checks for hardware H.264 at the requested 720p, 1080p, or 1440p size and 30 fps. It uses the same layout, tracking, caption and audio filters, and retries in software if the hardware attempt fails. **Quick trim** keeps source framing and audio, copying compatible tracks when the start aligns with a sync frame and using an accurate render otherwise.
- **Live preview** draws both crop panels from the same decoded frame as you play, drag, or pinch. Use **Preview export** for the final captions and audio effects.
- Previously generated captions and facecam samples are reused within covered ranges during the current app session. A narrower trim uses the existing samples and interpolates the new ends.
- **Export first** starts the queue with the selected clip. During a batch, **Next** moves it ahead of the remaining clips. Highlight analysis pauses during export and restarts from the unfinished section afterward.
- Compatible, consecutive sections from the same download can be joined by copying their tracks. Other sections still use the normal rendering path.
- **Export timings** identifies the encoder and reports setup, render/encode, and save times. Framing and caption effects run in the same pipeline as encoding, so they are reported together.

The editor has Trim, Frame, Captions, and Audio tabs. Use the top-bar Export button to open export settings. For a 50/50 vertical clip, turn on **Enhance facecam** under More export settings to sharpen the upper panel. Use **Preview export** to see the sharpening in a draft render. Use 1440p with a high-resolution recording when the crop contains enough source pixels and choose **High detail** to preserve more texture during compression.
