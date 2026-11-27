# iOS asset and component audit

This audit compares the Android source tree, bundled resources, notices, and current upstream Apple-platform options. It is a reuse decision record, not a claim that every listed item has already been ported.

## Existing Clip Studio repository

| Existing Android source / asset | License or status | iOS decision |
|---|---|---|
| StudioProjectStore.kt, StudioSession.kt, StudioWorkspace.kt | Project-owned Kotlin source | Port the project concepts into native Codable/JSON and native persistence. Kotlin/Android types are not copied. |
| ClipEditor.kt, StudioMedia.kt, FastMedia.kt | Project-owned Kotlin source | Reimplement editing and rendering with AVFoundation / Core Animation / VideoToolbox. |
| GuidedDownload.kt, RecordingPartWindow.kt, RecordingScan.kt | Project-owned Kotlin source | Port the range/part manifest concepts only. Use background URLSession and AVAssetDownloadURLSession instead of Android WorkManager / downloader classes. |
| YouTubeVideoImporter.kt, YouTubeLinkClassifier.kt, YouTubeDownloadValidation.kt | Includes Android yt-dlp / FFmpeg integration | Port only URL recognition and safe validation concepts. No YouTube audiovisual extractor is copied to iOS. |
| WhisperEngine.kt, whisper_jni.cpp, tiny model management | whisper.cpp MIT plus model notices | Prefer the Apple-native WhisperKit Core ML implementation for iOS. The Android JNI binary/model are not copied into the iOS app bundle. |
| YAMNet ONNX assets / model references | Apache-2.0 notices in the repository | Keep as a candidate for ONNX Runtime iOS or Core ML conversion. Not yet integrated into the iOS executable. |
| FacecamRescue.kt, FramingPreview.kt, StudioFraming.kt | Project-owned Kotlin source; ML Kit is Android-specific | Reimplement using Vision, Core Image, and AVFoundation. ML Kit Android artifact is not reused. |
| CaptionTrack.kt, SpeechHighlights.kt, HighlightRanking.kt | Project-owned Kotlin source | Port time/cue/ranking models to Swift. Current iOS caption model is intentionally smaller. |
| app/src/main/assets/youtube-agent/ | MIT, original attribution/license included | Selected ideas and six dependency-free Python utilities are documented for Swift reimplementation. A Python runtime is not bundled in iOS. |
| app/src/main/res/drawable-nodpi/studio_mark.webp | Existing in-repo artwork | Identified as a reusable branding candidate. It has not yet been converted into the required iOS AppIcon asset sizes. |
| AI Video Captions animation logic | MIT notice in Android assets | Candidate for a later native Core Animation caption-style port; not currently copied. |

## Upstream Apple-native components evaluated

### Used now
- AVFoundation — local asset inspection, playback, track composition, timed visual composition, and MP4 export. Apple-maintained, no third-party package.
- URLSession background tasks — authorized HTTP(S) range download and background event restoration.
- AVAssetDownloadURLSession — Apple-managed offline HLS VOD download packages.
- SwiftUI / PhotosUI / UniformTypeIdentifiers — native navigation and media selection.
- WhisperKit via Argmax Open-Source SDK — MIT-licensed Swift package; Core ML-based on-device transcription and word/segment timing. The app downloads the multilingual tiny model on first use rather than bundling model weights. The selected 2-minute transcription cap is a memory guard, not a WhisperKit limitation.

### Evaluated, not yet integrated
- Kadr — https://github.com/SteliyanH/kadr, Apache-2.0, iOS 17+. Its declarative AVFoundation composition engine and companion timeline/caption/photo packages are a strong option for a later multitrack timeline. It was not added to this first working editor to avoid adopting a second composition engine before we have an iOS render test suite.
- ONNX Runtime iOS / Core ML execution provider — https://onnxruntime.ai/docs/build/ios.html and https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html. Candidate for the existing YAMNet model if a Core ML conversion is not smaller or faster enough.
- Vision — native face rectangle detection and tracking candidate for facecam tracking. The iOS implementation will not bundle Android ML Kit.
- SoundAnalysis — native sound classification candidate for laughter, applause, and other audio-event cues before deciding whether to ship YAMNet on iOS.

## Components deliberately not ported

- youtubedl-android, yt-dlp extraction, undocumented YouTube endpoints, cookie scraping, and signature-deciphering code are not ported. User permission to edit the repository is not permission granted by YouTube or another media source.
- Android FFmpegKit binaries are not copied. The root notices identify the Android FFmpeg runtime as LGPL-3.0; this iOS path uses AVFoundation/VideoToolbox to avoid shipping a separate FFmpeg runtime.
- Android JNI, CMake, Gradle, Jetpack Compose, WorkManager, Android permissions, and Android-only ML Kit binaries remain Android-only.
- No third-party model weights or binary SDKs are copied unless their exact upstream license, attribution, source, and redistribution terms are recorded.

## License and model handling

- The repository root has an MIT license.
- The argmax-oss-swift package is MIT; include its notice in the shipped acknowledgements and preserve its license on redistribution.
- Whisper model weights are fetched separately at runtime. Before shipping a fixed model snapshot or offline model download bundle, verify the exact upstream model-repository license and include its model-specific notice.
- YAMNet and the existing ONNX conversion are recorded as Apache-2.0 in the Android project's third-party notices; reuse must preserve the upstream notice and model attribution.
- Kadr is only evaluated, not included as a dependency; retain Apache-2.0 notices if it is adopted later.
- Do not copy model binaries merely because the Android app already includes them. Prefer platform-native conversions with reproducible source/version/hash records.

## Next reuse priorities

1. Port YAMNet or SoundAnalysis highlight scoring and add tests against known audio fixtures.
2. Add Vision face tracking and the Android app's facecam rescue behavior.
3. Port the best MIT creator scripts to deterministic Swift utilities.
4. Adopt a timeline framework only after testing composition fidelity and export performance.
5. Convert the existing Studio Mark artwork to a full iOS asset catalog and verify light/dark appearance.
