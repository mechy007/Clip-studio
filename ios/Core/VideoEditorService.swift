import AVFoundation
import CoreGraphics
import Foundation
import QuartzCore
import UIKit

enum VideoEditorError: LocalizedError {
    case missingVideoTrack
    case invalidTrim
    case exportSessionUnavailable
    case exportFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingVideoTrack: "The recording has no readable video track."
        case .invalidTrim: "Choose a trim range that is at least 0.2 seconds long."
        case .exportSessionUnavailable: "This recording could not be prepared for MP4 export."
        case .exportFailed(let reason): "MP4 export failed: \(reason)"
        }
    }
}

enum SubtitleCodec {
    static func parseSRT(_ source: String) -> [CaptionCue] {
        let normalized = source
            .replacingOccurrences(of: "\u{feff}", with: "")
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        let blocks = normalized.components(separatedBy: "\n\n")
        return blocks.compactMap { block in
            let lines = block.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
            guard lines.count >= 2,
                  let timingLine = lines.first(where: { $0.contains("-->") }),
                  let arrow = timingLine.range(of: "-->") else {
                return nil
            }

            let left = timingLine[..<arrow.lowerBound].trimmingCharacters(in: .whitespaces)
            let right = timingLine[arrow.upperBound...].trimmingCharacters(in: .whitespaces)
            guard let start = milliseconds(left), let end = milliseconds(right), end > start else {
                return nil
            }

            let timingIndex = lines.firstIndex(of: timingLine) ?? 0
            let text = lines.dropFirst(timingIndex + 1).joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return CaptionCue(startMs: start, endMs: end, text: text)
        }
        .sorted { $0.startMs < $1.startMs }
    }

    static func makeSRT(_ cues: [CaptionCue]) -> String {
        cues.sorted { $0.startMs < $1.startMs }.enumerated().map { index, cue in
            "\(index + 1)\n\(timestamp(cue.startMs)) --> \(timestamp(cue.endMs))\n\(cue.text)"
        }.joined(separator: "\n\n") + "\n"
    }

    private static func milliseconds(_ value: String) -> Int64? {
        let clean = value.replacingOccurrences(of: ",", with: ".")
        let components = clean.split(separator: ":")
        guard components.count == 3,
              let hours = Int64(components[0]),
              let minutes = Int64(components[1]),
              let seconds = Double(components[2]),
              hours >= 0, minutes >= 0, seconds >= 0 else {
            return nil
        }
        return ((hours * 60 + minutes) * 60 * 1000) + Int64((seconds * 1000).rounded())
    }

    private static func timestamp(_ value: Int64) -> String {
        let clamped = max(0, value)
        let hours = clamped / 3_600_000
        let minutes = (clamped % 3_600_000) / 60_000
        let seconds = (clamped % 60_000) / 1000
        let millis = clamped % 1000
        return String(format: "%02lld:%02lld:%02lld,%03lld", hours, minutes, seconds, millis)
    }
}

enum VideoEditorService {
    static func export(
        project: ClipProject,
        sourceURL: URL,
        destinationDirectory: URL
    ) async throws -> URL {
        let asset = AVURLAsset(url: sourceURL)
        let sourceDuration = try await asset.load(.duration)
        let totalSeconds = CMTimeGetSeconds(sourceDuration)
        guard totalSeconds.isFinite, totalSeconds > 0 else {
            throw VideoEditorError.invalidTrim
        }

        let startSeconds = max(0, Double(project.trimStartMs) / 1000)
        let endSeconds = min(totalSeconds, Double(project.trimEndMs) / 1000)
        guard endSeconds - startSeconds >= 0.2 else {
            throw VideoEditorError.invalidTrim
        }

        let selectedRange = CMTimeRange(
            start: CMTime(seconds: startSeconds, preferredTimescale: 600),
            duration: CMTime(seconds: endSeconds - startSeconds, preferredTimescale: 600)
        )

        let sourceVideoTracks = try await asset.loadTracks(withMediaType: .video)
        guard let sourceVideo = sourceVideoTracks.first else {
            throw VideoEditorError.missingVideoTrack
        }

        let composition = AVMutableComposition()
        guard let destinationVideo = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            throw VideoEditorError.exportSessionUnavailable
        }
        try destinationVideo.insertTimeRange(selectedRange, of: sourceVideo, at: .zero)

        if !project.muteAudio {
            let sourceAudioTracks = try await asset.loadTracks(withMediaType: .audio)
            for sourceAudio in sourceAudioTracks.prefix(2) {
                let sourceAudioRange = try await sourceAudio.load(.timeRange)
                let intersectionStart = max(
                    CMTimeGetSeconds(selectedRange.start),
                    CMTimeGetSeconds(sourceAudioRange.start)
                )
                let intersectionEnd = min(
                    CMTimeGetSeconds(selectedRange.end),
                    CMTimeGetSeconds(sourceAudioRange.end)
                )
                guard intersectionEnd > intersectionStart,
                      let destinationAudio = composition.addMutableTrack(
                        withMediaType: .audio,
                        preferredTrackID: kCMPersistentTrackID_Invalid
                      ) else { continue }

                let sourceIntersection = CMTimeRange(
                    start: CMTime(seconds: intersectionStart, preferredTimescale: 600),
                    duration: CMTime(seconds: intersectionEnd - intersectionStart, preferredTimescale: 600)
                )
                let destinationStart = CMTime(
                    seconds: intersectionStart - startSeconds,
                    preferredTimescale: 600
                )
                try destinationAudio.insertTimeRange(
                    sourceIntersection,
                    of: sourceAudio,
                    at: destinationStart
                )
            }
        }

        let naturalSize = try await sourceVideo.load(.naturalSize)
        let preferredTransform = try await sourceVideo.load(.preferredTransform)
        let orientedBounds = CGRect(origin: .zero, size: naturalSize)
            .applying(preferredTransform)
            .standardized
        let sourceWidth = max(1, abs(orientedBounds.width))
        let sourceHeight = max(1, abs(orientedBounds.height))
        let renderSize = canvasSize(
            layout: project.layout,
            quality: project.quality,
            sourceWidth: sourceWidth,
            sourceHeight: sourceHeight
        )

        let videoComposition = AVMutableVideoComposition()
        videoComposition.renderSize = renderSize
        videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.backgroundColor = UIColor.black.cgColor
        instruction.timeRange = CMTimeRange(start: .zero, duration: selectedRange.duration)

        let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: destinationVideo)
        let normalize = preferredTransform.concatenating(
            CGAffineTransform(translationX: -orientedBounds.minX, y: -orientedBounds.minY)
        )
        let fillScale = max(renderSize.width / sourceWidth, renderSize.height / sourceHeight)
        let fitScale = min(renderSize.width / sourceWidth, renderSize.height / sourceHeight)
        let scale = project.layout == .verticalFit ? fitScale : fillScale
        let offsetX = (renderSize.width - sourceWidth * scale) / 2
        let offsetY = (renderSize.height - sourceHeight * scale) / 2
        let finalTransform = normalize
            .concatenating(CGAffineTransform(scaleX: scale, y: scale))
            .concatenating(CGAffineTransform(translationX: offsetX, y: offsetY))
        layerInstruction.setTransform(finalTransform, at: .zero)
        instruction.layerInstructions = [layerInstruction]
        videoComposition.instructions = [instruction]

        if !project.captions.isEmpty {
            videoComposition.animationTool = captionAnimationTool(
                cues: project.captions,
                trimStartMs: project.trimStartMs,
                trimEndMs: project.trimEndMs,
                renderSize: renderSize
            )
        }

        let preset: String
        switch project.quality {
        case .p720: preset = AVAssetExportPreset1280x720
        case .p1080: preset = AVAssetExportPreset1920x1080
        case .p1440: preset = AVAssetExportPresetHighestQuality
        }

        guard let exporter = AVAssetExportSession(asset: composition, presetName: preset) else {
            throw VideoEditorError.exportSessionUnavailable
        }

        try FileManager.default.createDirectory(
            at: destinationDirectory,
            withIntermediateDirectories: true
        )
        let outputURL = destinationDirectory
            .appendingPathComponent("clip-\(project.id.uuidString)-\(Int(Date().timeIntervalSince1970))")
            .appendingPathExtension("mp4")
        try? FileManager.default.removeItem(at: outputURL)

        exporter.outputURL = outputURL
        exporter.outputFileType = .mp4
        exporter.shouldOptimizeForNetworkUse = true
        exporter.videoComposition = videoComposition

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            exporter.exportAsynchronously {
                switch exporter.status {
                case .completed:
                    continuation.resume()
                case .failed:
                    continuation.resume(throwing: VideoEditorError.exportFailed(
                        exporter.error?.localizedDescription ?? "Unknown encoder error"
                    ))
                case .cancelled:
                    continuation.resume(throwing: CancellationError())
                default:
                    continuation.resume(throwing: VideoEditorError.exportFailed(
                        "The export ended in state \(exporter.status.rawValue)."
                    ))
                }
            }
        }
        return outputURL
    }

    private static func canvasSize(
        layout: CanvasLayout,
        quality: ExportQuality,
        sourceWidth: CGFloat,
        sourceHeight: CGFloat
    ) -> CGSize {
        let edge = quality.longEdge
        let width: CGFloat
        let height: CGFloat

        switch layout {
        case .verticalFill, .verticalFit:
            width = edge * 9 / 16
            height = edge
        case .square:
            width = edge
            height = edge
        case .original:
            let scale = min(1, edge / max(sourceWidth, sourceHeight))
            width = sourceWidth * scale
            height = sourceHeight * scale
        }

        return CGSize(
            width: max(2, floor(width / 2) * 2),
            height: max(2, floor(height / 2) * 2)
        )
    }

    private static func captionAnimationTool(
        cues: [CaptionCue],
        trimStartMs: Int64,
        trimEndMs: Int64,
        renderSize: CGSize
    ) -> AVVideoCompositionCoreAnimationTool {
        let parent = CALayer()
        parent.frame = CGRect(origin: .zero, size: renderSize)
        parent.masksToBounds = true

        let videoLayer = CALayer()
        videoLayer.frame = parent.bounds
        parent.addSublayer(videoLayer)

        for cue in cues where cue.endMs > trimStartMs && cue.startMs < trimEndMs {
            let relativeStart = max(0, cue.startMs - trimStartMs)
            let relativeEnd = min(trimEndMs, cue.endMs) - trimStartMs
            guard relativeEnd > relativeStart else { continue }

            let textLayer = CATextLayer()
            textLayer.string = cue.text
            textLayer.font = UIFont.boldSystemFont(ofSize: max(22, renderSize.width * 0.038))
            textLayer.fontSize = max(22, renderSize.width * 0.038)
            textLayer.alignmentMode = .center
            textLayer.foregroundColor = UIColor.white.cgColor
            textLayer.backgroundColor = UIColor.black.withAlphaComponent(0.58).cgColor
            textLayer.cornerRadius = 14
            textLayer.isWrapped = true
            textLayer.contentsScale = UIScreen.main.scale
            textLayer.frame = CGRect(
                x: renderSize.width * 0.06,
                y: renderSize.height * 0.07,
                width: renderSize.width * 0.88,
                height: renderSize.height * 0.14
            )
            textLayer.opacity = 0

            let visibility = CAKeyframeAnimation(keyPath: "opacity")
            visibility.values = [0, 1, 1, 0]
            visibility.keyTimes = [0, 0.025, 0.975, 1]
            visibility.beginTime = AVCoreAnimationBeginTimeAtZero + Double(relativeStart) / 1000
            visibility.duration = Double(relativeEnd - relativeStart) / 1000
            visibility.fillMode = .both
            visibility.isRemovedOnCompletion = false
            textLayer.add(visibility, forKey: "caption-visibility")
            parent.addSublayer(textLayer)
        }

        return AVVideoCompositionCoreAnimationTool(
            postProcessingAsVideoLayer: videoLayer,
            in: parent
        )
    }
}
