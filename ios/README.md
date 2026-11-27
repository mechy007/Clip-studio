# Clip Studio for iOS

A separate native iPhone and iPad video editor. The iOS application lives in this directory; the existing Android application remains at the repository root and is not changed by this work.

## What is implemented in this branch

- SwiftUI navigation for Home, Import, and Projects.
- Video import from the iOS Files picker and Photos picker. Imported sources are copied into the app's private Documents area.
- Local project persistence as versioned JSON, with source media and exports stored separately.
- AVPlayer playback and a native AVFoundation editor.
- Millisecond trim boundaries, Original / Vertical Fill / Vertical Fit / Square canvases, 720p / 1080p / 1440p export choices, and optional source-audio muting.
- MP4 export using AVMutableComposition, AVMutableVideoComposition, and AVAssetExportSession. Timed caption cues are burned into the export.
- Manual caption authoring, SRT import/export, and local Whisper caption generation through the MIT-licensed WhisperKit framework. The multilingual tiny model is downloaded on first use; transcription is limited to selections up to two minutes to keep memory demands bounded.
- Creator-authorized direct HTTPS MP4/MOV/M4V download using HTTP byte ranges. The downloader probes range support, requires an ETag or Last-Modified validator, transfers 32 MiB chunks with at most three concurrent tasks, validates each 206 Content-Range response, persists checkpoints, and reassembles the file only after all chunks pass validation. It refuses sources larger than 8 GB.
- Creator-authorized HLS VOD replay downloads through AVAssetDownloadURLSession, with progress and persisted replay state.
- Background URLSession callbacks are reconnected through the app delegate.

This is a functional native foundation, not yet full feature parity with Android. AI highlight ranking, YAMNet event analysis, Vision face tracking, Facecam Rescue, multi-part recording analysis, animated captions, advanced audio finishing, export queues, and full Android/iOS project interchange remain planned.

## Importing YouTube recordings

A YouTube recording or replay URL can be recognized by the import screen, but it is not used to retrieve audiovisual bytes. For your own channel, download the recording or finished replay through YouTube Studio or Google Takeout and then import that local file with Files or Photos. For another creator's recording, use only a source-approved file or URL.

This separation is intentional. Apple's App Review Guideline 5.2.3 requires explicit authorization from a third-party source before an app enables saving, converting, or downloading its media. YouTube's developer policies separately require YouTube's prior written approval for downloading, importing, backing up, caching, or storing copies of YouTube audiovisual content through YouTube API Services. A creator's channel ownership, API metadata access, or user consent alone must not be treated as that platform authorization.

References:
- [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [YouTube API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies/)

## Progressive downloads and livestream replays

The direct-file path is for explicitly authorized HTTPS media endpoints. It requires a stable Content-Length, HTTP byte-range support, and a stable ETag or Last-Modified validator. It does not attempt to bypass DRM, login gates, signed URL restrictions, or source access controls.

The HLS path uses Apple's AVFoundation asset-download API. It is for supported downloadable HLS assets that are available as completed VOD replays. It cannot save an active livestream as an offline replay while the broadcast is still in progress. Wait until the platform publishes the replay.

Background transfers are managed by iOS. A background URLSession can continue supported HTTP(S) downloads while the app is suspended, and iOS can relaunch the app to deliver delegate events. The operating system may schedule work later, and force-quitting the app cancels background transfers. This is not unlimited background execution.

Apple documentation:
- [Background URLSession downloads](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background)
- [AVAssetDownloadURLSession](https://developer.apple.com/documentation/avfoundation/avassetdownloadurlsession)
- [Persisting HTTP Live Streams](https://developer.apple.com/documentation/avfoundation/using-avfoundation-to-play-and-persist-http-live-streams)

## Build

Requirements:
- macOS with Xcode 16 or newer.
- iOS deployment target: 17.0.
- Swift Package Manager access to https://github.com/argmaxinc/argmax-oss-swift (WhisperKit product).

Open ios/ClipStudio.xcodeproj, allow Xcode to resolve Swift packages, select the ClipStudio scheme, and build for an iOS Simulator. The GitHub Actions workflow also builds both simulator and generic iOS device targets without code signing.

A developer-signed physical-device build, TestFlight upload, and App Store release require an Apple Developer account, signing credentials, and real-device QA. These cannot be substituted by a simulator CI build.

## Project structure

- App/: SwiftUI entry point and background URLSession restoration.
- Features/Home/: Home dashboard, import flows, and saved-project list.
- Features/Editor/: playback, trim controls, captions, transcription, and export UI.
- Core/ProjectModels.swift: local project model and atomic JSON persistence.
- Core/VideoEditorService.swift: subtitle parsing, composition and MP4 export.
- Core/RangeDownloadManager.swift: authorized HTTP byte-range downloader.
- Core/HLSReplayDownloader.swift: supported HLS VOD asset downloads.
- Core/WhisperTranscriptionService.swift: on-device speech-to-text adapter.
- Core/Import/: source recognition and provider boundaries.

See [the architecture and implementation status](../docs/ios/ARCHITECTURE.md) and [the asset/component audit](ASSET_AND_COMPONENT_AUDIT.md).
