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
final class HLSReplayDownloader: NSObject, ObservableObject, @preconcurrency AVAssetDownloadDelegate {
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
    ) async throws -> UUID {
        guard userConfirmedRights else { throw HLSReplayError.permissionRequired }
        guard url.scheme?.lowercased() == "https",
              url.user == nil,
              url.password == nil,
              url.pathExtension.lowercased() == "m3u8" else {
            throw HLSReplayError.invalidURL
        }

        try await verifyPublishedVODPlaylist(url)

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
        let downloadConfiguration = AVAssetDownloadConfiguration(asset: asset, title: job.title)
        let task = session.makeAssetDownloadTask(downloadConfiguration: downloadConfiguration)
        task.taskDescription = id.uuidString
        task.resume()
        update(jobID: id) { $0.state = .downloading }
        return id
    }

    /// Apple only supports offline persistence for completed HLS VOD playlists.
    /// Resolve a master playlist to a media playlist before creating an asset task;
    /// attempting to persist an active live playlist can raise an AVFoundation exception.
    private func verifyPublishedVODPlaylist(_ url: URL, depth: Int = 0) async throws {
        guard depth <= 3 else { throw HLSReplayError.notReady }
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("application/vnd.apple.mpegurl, application/x-mpegURL, */*", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode),
              (http.url ?? url).scheme?.lowercased() == "https",
              data.count <= 2 * 1024 * 1024,
              let playlist = String(data: data, encoding: .utf8) else {
            throw HLSReplayError.notReady
        }

        let lines = playlist.components(separatedBy: .newlines)
        guard lines.first?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == "#EXTM3U" else {
            throw HLSReplayError.notReady
        }
        if lines.contains(where: { $0.trimmingCharacters(in: .whitespaces).uppercased() == "#EXT-X-ENDLIST" }) {
            return
        }

        for index in lines.indices where lines[index].trimmingCharacters(in: .whitespaces).uppercased().hasPrefix("#EXT-X-STREAM-INF:") {
            let next = lines.index(after: index)
            guard next < lines.endIndex else { continue }
            let variantLine = lines[next].trimmingCharacters(in: .whitespaces)
            guard !variantLine.isEmpty, !variantLine.hasPrefix("#"),
                  let variantURL = URL(string: variantLine, relativeTo: http.url ?? url)?.absoluteURL,
                  variantURL.scheme?.lowercased() == "https" else { continue }
            try await verifyPublishedVODPlaylist(variantURL, depth: depth + 1)
            return
        }

        throw HLSReplayError.notReady
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
            let destination = rootDirectory.appendingPathComponent(id.uuidString).appendingPathExtension("movpkg")
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
