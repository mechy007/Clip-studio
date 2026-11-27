# Clip Studio iOS — architecture and implementation status

## Repository boundary

The Android app remains rooted at app/ with its Gradle build files at repository root. The iOS app is a separate native project under ios/. No Android source, build configuration, or assets are relocated.

## Implemented native architecture

| Area | Implementation |
|---|---|
| UI | SwiftUI Home / Import / Projects / Editor views |
| Local media | Files picker + Photos picker; copy source into app Documents |
| Project persistence | Atomic JSON files under Application Support; relative media/export paths; schema version 1 |
| Playback | AVPlayer / VideoPlayer |
| Editing | AVMutableComposition + AVMutableVideoComposition |
| Export | AVAssetExportSession MP4 with timed Core Animation caption layers |
| Captions | Editable cue records; SRT import/export; WhisperKit segment timing |
| Speech AI | Argmax WhisperKit, multilingual tiny model, Core ML inference, first-use model download |
| HTTP import | Background URLSession download tasks; 32 MiB HTTP Range chunks; maximum three active chunks; ETag / Last-Modified checks; persisted manifests; reassembly after validation; 8 GB cap |
| HLS replay | AVAssetDownloadURLSession for supported completed VOD assets |
| Background restoration | Stable URLSession identifiers and UIApplicationDelegate event handoff |

## Import source rules

### Local sources
Files and Photos picker results are copied into the app's private Documents media directory. This protects projects from temporary security-scoped access expiring and keeps the original source untouched.

### YouTube
The provider recognizes common watch, short, live, embed, and youtu.be URL shapes and canonicalizes the video reference. It does not retrieve, cache, or download audiovisual content. Creators can download their own recording through YouTube Studio / Google Takeout and import the resulting file.

No extractor, undocumented endpoint, cookie scraping, signature deciphering, DRM circumvention, or YouTube API media-byte download is included. Metadata discovery is a distinct capability and is not download authorization. Re-enable any YouTube media provider only after receiving platform-level written authorization that covers the exact app behavior and after preparing evidence for App Review.

### Direct HTTP media
The direct downloader requires:
1. HTTPS and no embedded URL username/password.
2. Successful HEAD with a stable content length.
3. A bytes=0-0 probe returning HTTP 206 and exact Content-Range.
4. A strong ETag or Last-Modified validator.
5. A source no larger than 8 GB.

The transfer uses three concurrent 32 MiB chunks. Each range has an If-Range validator. Each temporary download must return 206, the exact expected Content-Range, a matching entity validator, and the expected byte count before it is committed as a chunk. The final file is assembled in order and its total size is checked before the import UI exposes it. Interrupted chunks are retried at their last completed chunk boundary; partially transferred chunks are restarted rather than trusted.

The current implementation intentionally fails closed when the server does not meet these requirements. It does not claim to progressively decode arbitrary MP4 byte ranges; the current progressive behavior is download/checkpointing, followed by editing after final reassembly. Parsing incomplete MP4 fragments or editing ready ranges while later ranges are still downloading is a future phase.

### HLS VOD
AVAssetDownloadURLSession manages supported HLS offline asset packages and reports loaded time ranges. Completed replay packages are retained with a `.movpkg` extension and can be imported into the editor. An active livestream is not treated as an offline-downloadable replay. Wait for the source provider to publish a finished VOD playlist.

## Project model

ClipProject is local JSON schema version 1. It includes a stable project UUID, source display name, app-relative source path, duration, trim boundaries in integer milliseconds, output layout, export quality, audio mute flag, caption cues, export path, and timestamps.

The Android project model contains additional workflow state for multipart recordings, drafts, presets, highlight analysis, facecam/gameplay crops, audio finishing, and export queues. Cross-platform interchange is not yet claimed: a shared versioned schema and migration/conversion tests are required before Android projects can be opened losslessly in iOS.

## Editing and export

The editor currently supports:
- trim start and end in milliseconds with a minimum export duration check;
- original aspect, vertical fill, vertical fit, and square canvas modes;
- 720p, 1080p and 1440p quality selections;
- optional original-audio mute;
- manual caption cues, SRT import/export, and speech-to-text caption generation;
- MP4 rendering with visible caption overlays.

Export renders a new file and preserves the imported source. The current UI is a first functional editor, not a multitrack professional timeline. Separate facecam/gameplay regions, keyframes, animated overlays, audio fades/gains, and render queues remain planned.

## AI and analysis roadmap

1. Add a portable highlight model and analysis-result persistence.
2. Port the Android audio-window/RMS heuristics.
3. Evaluate the repository's existing YAMNet ONNX model under ONNX Runtime for iOS or convert it to Core ML; keep the Apache-2.0 model notice.
4. Add Vision face detection and track smoothing for facecam framing.
5. Reimplement Facecam Rescue and split-layout crop handling with Core Image / AVFoundation.
6. Port selected MIT creator tools to deterministic Swift; do not bundle a Python runtime only to run Android-side scripts.
7. Add explicit export queue recovery and storage estimates.

## Background task behavior

Background URLSession identifiers are stable and app-delegate events are forwarded to the corresponding singleton. The system may suspend, delay, or relaunch the app. Force-quitting cancels background URLSession transfers, and compute-heavy analysis is not promised to run indefinitely in the background.

## Security / privacy

- No API keys are embedded in the client.
- Imported files remain local by default.
- Download manifests are stored under Application Support with file protection enabled after first unlock.
- URL and media metadata are treated as untrusted input.
- Only explicit creator/source authorization is accepted for external media bytes.
- Do not bypass DRM, authentication, signed URL expiry, or service restrictions.

## Quality gates still required before release

- Execute the GitHub Actions simulator and generic-device builds after each code change.
- Add XCTest coverage for SRT parsing, project round-trip/migrations, URL validation, Content-Range validation, and retry/resume state.
- Add fixture-server integration tests for 206/200 responses, ETag changes, truncation, redirects, and connection failures.
- Test on real iPhone and iPad devices under memory pressure, low storage, thermal load, suspension, relaunch, and force-quit.
- Verify AVFoundation crop/orientation behavior with landscape, portrait, rotated, variable-frame-rate, HDR, and multi-audio-track samples.
- Add signed-device, TestFlight, privacy nutrition-label, and App Review preparation only after the above gates pass.

## Primary upstream references

- [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [YouTube API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies/)
- [Apple background URLSession downloads](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background)
- [Apple AVAssetDownloadURLSession](https://developer.apple.com/documentation/avfoundation/avassetdownloadurlsession)
- [Apple HTTP Live Streams persistence sample](https://developer.apple.com/documentation/avfoundation/using-avfoundation-to-play-and-persist-http-live-streams)
- [Argmax Open-Source SDK / WhisperKit](https://github.com/argmaxinc/argmax-oss-swift)
