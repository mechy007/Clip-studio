# Clip Studio for iOS

Clip Studio for iOS is a separate, native Apple-platform application. It lives in this ios/ directory and does not move, replace, or modify the existing root-level Android Gradle project.

## Product goals

- Native iPhone and iPad experience using SwiftUI.
- Local video import, editing, captions, offline analysis, and MP4 export.
- Creator-authorized remote media import, with resumable and progressive processing where the source supports it.
- A provider boundary for YouTube recordings and livestream replays. YouTube downloading must remain disabled unless the required written authorization and App Store compliance are established.
- Shared, versioned Clip Studio project specification across Android and iOS.

## Initial platform baseline

- Minimum deployment target: iOS 17.0.
- Language: Swift.
- UI: SwiftUI.
- Media playback and composition: AVFoundation.
- Encoding: AVAssetWriter / VideoToolbox where appropriate.
- Face analysis: Vision.
- Offline transcription: whisper.cpp (integration planned; validate model and binary licensing before bundling).
- Audio event inference: evaluate the existing YAMNet ONNX model with ONNX Runtime for iOS; compare a Core ML conversion before choosing the production runtime.
- Persistence: SwiftData, with a versioned JSON interchange format for projects.

## Import and download policy

Apple App Review Guideline 5.2.3 requires explicit authorization before an app enables saving, converting, or downloading media from third-party services such as YouTube. YouTube's developer policies separately prohibit downloading, importing, caching, or storing copies of YouTube audiovisual content through YouTube API Services without prior written approval.

Therefore:
1. Local files and creator-authorized direct media URLs are the initial supported import paths.
2. YouTube is represented by a provider interface, not an assumed working downloader.
3. The YouTube provider must not be enabled for distribution until written authorization and App Review requirements are satisfied.
4. YouTube Data API metadata access is not permission to download video bytes.
5. Never bypass DRM, access controls, signed URL restrictions, or platform restrictions.

## Progressive download design

For creator-authorized HTTP(S) sources:
- Inspect source metadata and verify byte-range support before scheduling segmented downloads.
- Use background URLSession download tasks with HTTP Range requests for independently downloadable byte ranges.
- Persist a manifest containing source identity, expected size, ETag/Last-Modified validators, chunk boundaries, completed chunk checksums, and task identifiers.
- Validate every response's status and Content-Range before accepting a chunk.
- Write each completed segment atomically; never expose partial files as complete media.
- Analyze completed media ranges as soon as their required audio/video dependencies are available.
- Reassemble only after all required ranges pass validation; otherwise retain resumable checkpoints.
- For authorized HLS VOD sources, evaluate AVAssetDownloadURLSession. Do not assume it can turn an arbitrary platform stream into a downloadable file.

iOS background execution is system-managed. Background URLSession can continue supported HTTP(S) transfers while the app is suspended, but the app must recover delegate events and persisted state. Force-quitting the app cancels background transfers. Do not promise uninterrupted arbitrary background processing.

## Feature phases

1. App shell, navigation, local import, playback, persistence.
2. Timeline, frame-accurate trim, layouts, preview and MP4 export.
3. Creator-authorized remote import, resumable range transfers, progressive analysis.
4. Whisper transcription, YAMNet audio events, captions and highlight ranking.
5. Vision face tracking, facecam rescue, audio finishing and creator presets.
6. Cross-platform project compatibility, performance tests and TestFlight readiness.

## Build and testing

The project is intended to build with Xcode on macOS. GitHub Actions macOS runners can provide simulator builds and tests. Physical-device testing, code signing, TestFlight, and App Store distribution require Apple tooling and credentials.

See the architecture blueprint at ../docs/ios/ARCHITECTURE.md.
