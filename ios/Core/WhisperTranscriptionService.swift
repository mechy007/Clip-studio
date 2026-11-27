import AVFoundation
import Combine
import Foundation
import WhisperKit

enum WhisperTranscriptionError: LocalizedError {
    case noAudioTrack
    case clipTooLong
    case audioPreparationFailed(String)
    case emptyTranscript

    var errorDescription: String? {
        switch self {
        case .noAudioTrack: "This recording does not contain an audio track."
        case .clipTooLong: "For a reliable low-memory transcription, select a section of up to two minutes."
        case .audioPreparationFailed(let message): "Could not prepare audio for transcription: \(message)"
        case .emptyTranscript: "No speech was detected in the selected section."
        }
    }
}

/// On-device Whisper transcription accelerated by Apple's Core ML stack.
/// The multilingual tiny model is downloaded on first use instead of inflating
/// the app bundle with a large model file.
@MainActor
final class WhisperTranscriptionService: ObservableObject {
    static let shared = WhisperTranscriptionService()

    @Published private(set) var isWorking = false
    @Published private(set) var status = "Ready"

    private var engine: WhisperKit?

    private init() {}

    func transcribeVideo(
        sourceURL: URL,
        trimStartMs: Int64,
        trimEndMs: Int64
    ) async throws -> [CaptionCue] {
        let durationMs = trimEndMs - trimStartMs
        guard durationMs > 0, durationMs <= 120_000 else {
            throw WhisperTranscriptionError.clipTooLong
        }

        isWorking = true
        status = "Preparing audio…"
        defer {
            isWorking = false
            status = "Ready"
        }

        let asset = AVURLAsset(url: sourceURL)
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        guard !audioTracks.isEmpty else { throw WhisperTranscriptionError.noAudioTrack }

        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ClipStudio/Transcription", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let audioURL = cache.appendingPathComponent(UUID().uuidString).appendingPathExtension("m4a")
        defer { try? FileManager.default.removeItem(at: audioURL) }

        guard let exporter = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw WhisperTranscriptionError.audioPreparationFailed("iOS could not create an audio export session.")
        }
        exporter.outputURL = audioURL
        exporter.outputFileType = .m4a
        exporter.timeRange = CMTimeRange(
            start: CMTime(value: trimStartMs, timescale: 1000),
            duration: CMTime(value: durationMs, timescale: 1000)
        )

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            exporter.exportAsynchronously {
                switch exporter.status {
                case .completed:
                    continuation.resume()
                case .failed:
                    continuation.resume(throwing: WhisperTranscriptionError.audioPreparationFailed(
                        exporter.error?.localizedDescription ?? "Audio export failed."
                    ))
                case .cancelled:
                    continuation.resume(throwing: CancellationError())
                default:
                    continuation.resume(throwing: WhisperTranscriptionError.audioPreparationFailed(
                        "Audio export ended in state \(exporter.status.rawValue)."
                    ))
                }
            }
        }

        status = "Loading offline speech model…"
        if engine == nil {
            let configuration = WhisperKitConfig(
                model: "tiny",
                verbose: false,
                download: true,
                useBackgroundDownloadSession: false
            )
            engine = try await WhisperKit(configuration)
        }

        status = "Transcribing on this device…"
        let options = DecodingOptions(
            wordTimestamps: true,
            concurrentWorkerCount: 2
        )
        let results = try await engine?.transcribe(
            audioPath: audioURL.path,
            decodeOptions: options
        ) ?? []

        let cues = results.flatMap { result in
            result.segments.compactMap { segment -> CaptionCue? in
                let text = segment.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return nil }
                let start = trimStartMs + Int64((Double(segment.start) * 1000).rounded())
                let end = trimStartMs + Int64((Double(segment.end) * 1000).rounded())
                guard end > start else { return nil }
                return CaptionCue(
                    startMs: max(trimStartMs, start),
                    endMs: min(trimEndMs, end),
                    text: text
                )
            }
        }
        .sorted { $0.startMs < $1.startMs }

        guard !cues.isEmpty else { throw WhisperTranscriptionError.emptyTranscript }
        return cues
    }
}
