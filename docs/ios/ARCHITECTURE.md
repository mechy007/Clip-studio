# Clip Studio iOS Architecture Blueprint

## 1. Repository boundary

The Android application remains at the repository root:
- app/
- build.gradle.kts
- settings.gradle.kts
- existing Gradle wrapper and workflows

The iOS application is independent under ios/. No Android files or build configuration should be moved to make room for it.

## 2. Proposed modules

    ios/
      ClipStudio.xcodeproj/
      App/
        ClipStudioApp.swift
        AppEnvironment.swift
      Features/
        Home/
        Import/
        Highlights/
        Editor/
        Captions/
        Projects/
        AIHub/
        Export/
      Core/
        ProjectModel/
        Media/
        Import/
        Downloads/
        Transcription/
        AudioAnalysis/
        VideoAnalysis/
        Rendering/
        Persistence/
      Resources/
        Models/
        Presets/
      Tests/

Keep feature views thin. Put media work, provider logic, persistence, and export in testable services.

## 3. Main services

- MediaLibraryService: Photos picker and Files import, security-scoped URL handling, metadata inspection.
- PlaybackService: AVPlayer-based playback and time observation.
- ProjectStore: SwiftData persistence and schema migrations.
- ProjectInterchange: versioned JSON encode/decode for Android/iOS project interchange.
- ImportProvider: protocol for local, authorized direct-file, authorized HLS, and separately gated YouTube sources.
- DownloadCoordinator: background URLSession orchestration, pause/resume, task restoration and progress.
- RangeManifestStore: source validators, range boundaries, chunk state and checksums.
- ProgressiveAnalysisCoordinator: schedule analysis only when required media ranges are present and validated.
- TranscriptionService: whisper.cpp adapter.
- AudioEventService: YAMNet inference adapter; ONNX Runtime vs Core ML remains a measured implementation choice.
- FaceTrackingService: Vision face detection/tracking.
- CompositionService: AVMutableComposition and AVVideoComposition graph.
- ExportService: AVAssetWriter pipeline, progress, cancellation and recovery.

## 4. YouTube and live replay provider

The app must distinguish metadata discovery from media-byte access:
- YouTube Data API may be used for permitted metadata and channel/broadcast discovery within its policies.
- API access does not grant permission to download or cache audiovisual content.
- The YouTube media provider stays unavailable for downloading until written authorization covers the intended behavior and App Review requirements are met.
- Do not use undocumented endpoints, signature deciphering, cookie scraping, DRM circumvention, or other access-control bypasses.

For a creator-authorized replay source, the provider may expose:
- replay readiness and availability state;
- source duration and format metadata;
- stable media URL or HLS manifest;
- range or segment capabilities;
- source validators and expiry behavior.

A replay that is still being prepared must be represented as preparing, not as a failed or complete recording. Polling must be bounded and respectful. Once the provider confirms a stable authorized source, the regular download pipeline can start.

## 5. Progressive and resumable downloads

### HTTP range path

1. Request metadata with HEAD or a small range probe.
2. Confirm Accept-Ranges: bytes, a stable length, and a validator (ETag or Last-Modified) where available.
3. Divide the file into bounded ranges.
4. Schedule HTTP Range requests using background URLSession download tasks.
5. Validate HTTP 206 and exact Content-Range; reject a 200 full-body response where a partial range was expected.
6. Verify source validator has not changed between chunks.
7. Move temporary downloads atomically into the project checkpoint directory.
8. Publish completed ranges to progressive analysis.
9. Reassemble and validate the final media before editing/export.

If range support or stable validators are unavailable, fall back to one resumable full-file transfer where supported. Do not fake progressive availability.

### HLS path

For authorized HLS assets, use AVFoundation's asset-download facilities when the source and media format are supported. Preserve audio/subtitle media selections as appropriate. HLS segment downloading is not a workaround for platform download restrictions.

### Background behavior

Use a stable background URLSession identifier and restore tasks on launch. Persist task IDs and chunk state before starting work. Handle relaunch callbacks and call the system completion handler after delegate events are processed. iOS may defer background work; force-quit cancels background URLSession transfers. UI and analysis must tolerate suspension and relaunch.

## 6. Shared project format

Use a versioned JSON project document with:
- schemaVersion and appVersion;
- source identity and media metadata;
- clip start/end in integer milliseconds or rational media time;
- output canvas, aspect ratio and frame rate;
- normalized crop rectangles and layout definitions;
- caption segments with start/end, text, style and position;
- audio gain, fades and processing options;
- highlight timestamps, scores and detection reasons;
- export configuration.

Media bytes are stored separately. Use stable IDs and explicit migration rules. Never serialize platform-specific absolute sandbox paths as portable source identity.

## 7. Performance and quality gates

- Keep UI responsive during import, analysis and rendering.
- Bound concurrent downloads and inference jobs.
- Monitor thermal state, memory pressure, battery and free disk space.
- Avoid loading full source videos into memory.
- Test corrupt, truncated, changing, expired and non-range sources.
- Test interrupted transfers, app suspension, app relaunch and force-quit behavior.
- Compare Android/iOS project interchange and output framing.
- Test on real iPhone and iPad hardware before release.

## 8. Security and privacy

- Do not embed API secrets in the app.
- Store OAuth tokens in Keychain.
- Use least-privilege file access and security-scoped bookmarks where required.
- Treat imported media and metadata as untrusted input.
- Keep media local by default; clearly disclose any external AI/API processing.
- Do not bypass DRM or source access controls.

## 9. References

- Apple App Review Guidelines, sections 5.2.2 and 5.2.3: https://developer.apple.com/app-store/review/guidelines/
- Apple background URLSession downloads: https://developer.apple.com/documentation/foundation/downloading-files-in-the-background
- Apple AVAssetDownloadURLSession: https://developer.apple.com/documentation/avfoundation/avassetdownloadurlsession
- YouTube API Services Developer Policies: https://developers.google.com/youtube/terms/developer-policies/
