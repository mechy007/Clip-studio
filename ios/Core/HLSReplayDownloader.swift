import AVFoundation
import Combine
import Foundation

enum ReplayDownloadState: String, Codable {
    case queued
    case downloading
    case completed
    case failed
}

struct HLSReplayJob: Codable, Identifiable {
    let id: UUID
    let title: String
    let sourceURL: URL
    var state: ReplayDownloadState
    var progress: Double
    var downloadLocation: String?
    var packagePath: String?
    var errorMessage: String?
}

enum HLSReplayError: LocalizedError {
    case permissionRequired
    case invalidURL
    case notReady

    var errorDescription: String? {
        switch self {
        case .permissionRequired: "Confirm that you own this recording or have explicit permission to download and edit it."
        case .invalidURL: "Choose an HTTPS HLS playlist URL ending in .m3u8."
        case .notReady: "This source is not available as a finished replay yet. Wait until the service publishes a VOD replay."
        }
    }
}

/// Native HLS offline downloader. AVAssetDownloadURLSession is for supported
/// downloadable HLS assets; it is not a way to save a live stream while it is live.
@MainActor
final class HLSReplayDownloader: NSObject, ObservableObject, AVAssetDownloadDelegate {
    static let shared = HLSReplayDownloader()
    static let backgroundIdentifier = "com.mechy007.clipstudio.ios.hlsreplays"

    @Published private(set) var jobs: [HLSReplayJob] = []

    private let fileManager = FileManager.default
    private var session: AVAssetDownloadURLSession!
    private var backgroundCompletionHandler: (() -> Void)?

    private var rootDirectory: URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("ClipStudio/HLSReplays", isDirectory: true)
    }

    private override init() {
        super.init()
        try? fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.backgroundIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.timeoutIntervalForResource = 60 * 60 * 24
        session = AVAssetDownloadURLSession(
            configuration: configuration,
            assetDownloadDelegate: self,
            delegateQueue: OperationQueue.main
        )
        reloadJobs()
    }

    func start(
        url: URL,
        title: String,
        userConfirmedRights: Bool
    ) throws -> UUID {
        guard userConfirmedRights else { throw HLSReplayError.permissionRequired }
        guard url.scheme?.lowercased() == "https",
              url.user == nil,
              url.password == nil,
              url.pathExtension.lowercased() == "m3u8" else {
            throw HLSReplayError.invalidURL
        }

        let id = UUID()
        let job = HLSReplayJob(
            id: id,
            title: title.isEmpty ? url.deletingPathExtension().lastPathComponent : title,
            sourceURL: url,
            state: .queued,
            progress: 0,
            downloadLocation: nil,
            packagePath: nil,
            errorMessage: nil
        )
        jobs.insert(job, at: 0)
        try persist(job)

        let asset = AVURLAsset(url: url)
        guard let task = session.assetDownloadTask(
            with: asset,
            assetTitle: job.title,
            assetArtworkData: nil,
            options: nil
        ) else {
            update(jobID: id) {
                $0.state = .failed
                $0.errorMessage = "iOS could not create an HLS asset download task."
            }
            return id
        }
        task.taskDescription = id.uuidString
        task.resume()
        update(jobID: id) { $0.state = .downloading }
        return id
    }

    func packageURL(for job: HLSReplayJob) -> URL? {
        guard let path = job.packagePath else { return nil }
        return URL(fileURLWithPath: path)
    }

    func handleBackgroundEvents(_ completion: @escaping () -> Void) {
        backgroundCompletionHandler = completion
    }

    private func update(jobID: UUID, _ change: (inout HLSReplayJob) -> Void) {
        guard let index = jobs.firstIndex(where: { $0.id == jobID }) else { return }
        var job = jobs[index]
        change(&job)
        jobs[index] = job
        try? persist(job)
    }

    private func persist(_ job: HLSReplayJob) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(job)
        try fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        try data.write(to: rootDirectory.appendingPathComponent(job.id.uuidString).appendingPathExtension("json"), options: .atomic)
    }

    private func reloadJobs() {
        let urls = (try? fileManager.contentsOfDirectory(at: rootDirectory, includingPropertiesForKeys: nil)) ?? []
        jobs = urls.compactMap { url in
            guard url.pathExtension == "json",
                  let data = try? Data(contentsOf: url),
                  let job = try? JSONDecoder().decode(HLSReplayJob.self, from: data) else { return nil }
            return job
        }.sorted { $0.id.uuidString > $1.id.uuidString }
    }

    private func jobID(for task: URLSessionTask) -> UUID? {
        task.taskDescription.flatMap(UUID.init(uuidString:))
    }

    func urlSession(
        _ session: URLSession,
        assetDownloadTask: AVAssetDownloadTask,
        didLoad timeRange: CMTimeRange,
        totalTimeRangesLoaded loadedTimeRanges: [NSValue],
        timeRangeExpectedToLoad expectedTimeRange: CMTimeRange
    ) {
        guard let id = jobID(for: assetDownloadTask) else { return }
        let expected = CMTimeGetSeconds(expectedTimeRange.duration)
        let loaded = loadedTimeRanges.reduce(0.0) { partial, value in
            partial + CMTimeGetSeconds(value.timeRangeValue.duration)
        }
        let progress = expected.isFinite && expected > 0 ? min(1, max(0, loaded / expected)) : 0
        update(jobID: id) {
            $0.state = .downloading
            $0.progress = progress
        }
    }

    func urlSession(
        _ session: URLSession,
        assetDownloadTask: AVAssetDownloadTask,
        willDownloadTo location: URL
    ) {
        guard let id = jobID(for: assetDownloadTask) else { return }
        update(jobID: id) { $0.downloadLocation = location.path }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = jobID(for: task),
              let job = jobs.first(where: { $0.id == id }) else { return }

        if let error {
            update(jobID: id) {
                $0.state = .failed
                $0.errorMessage = error.localizedDescription
            }
            return
        }

        guard let sourcePath = job.downloadLocation else {
            update(jobID: id) {
                $0.state = .failed
                $0.errorMessage = "The HLS download finished without a stored media package."
            }
            return
        }

        do {
            let source = URL(fileURLWithPath: sourcePath)
            let destination = rootDirectory.appendingPathComponent(id.uuidString, isDirectory: true)
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: source, to: destination)
            update(jobID: id) {
                $0.state = .completed
                $0.progress = 1
                $0.packagePath = destination.path
                $0.downloadLocation = nil
                $0.errorMessage = nil
            }
        } catch {
            update(jobID: id) {
                $0.state = .failed
                $0.errorMessage = error.localizedDescription
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let completion = backgroundCompletionHandler
        backgroundCompletionHandler = nil
        completion?()
    }
}
