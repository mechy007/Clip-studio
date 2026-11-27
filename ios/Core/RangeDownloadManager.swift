import Combine
import Foundation

enum RangeDownloadError: LocalizedError {
    case permissionRequired
    case insecureURL
    case unsupportedServer
    case missingValidator
    case invalidResponse
    case sourceChanged
    case fileTooLarge
    case missingJob
    case chunkMismatch

    var errorDescription: String? {
        switch self {
        case .permissionRequired: "Confirm that you own this recording or have explicit permission to download and edit this source."
        case .insecureURL: "Only secure HTTPS media URLs can be downloaded."
        case .unsupportedServer: "This server does not provide a safe resumable byte-range download."
        case .missingValidator: "The source has no stable ETag or Last-Modified value, so safe resume cannot be guaranteed."
        case .invalidResponse: "The media server returned an invalid range response."
        case .sourceChanged: "The source changed during download. Start a new download so sections from different versions are never combined."
        case .fileTooLarge: "The source exceeds the 8 GB safety limit."
        case .missingJob: "This download job no longer exists."
        case .chunkMismatch: "A downloaded section failed its size or range check."
        }
    }
}

enum RangeChunkState: String, Codable {
    case queued
    case downloading
    case complete
    case failed
}

struct RangeDownloadChunk: Codable, Identifiable {
    var id: Int { index }
    let index: Int
    let lowerBound: Int64
    let upperBound: Int64
    var state: RangeChunkState
    var bytesWritten: Int64 = 0
    var attempts: Int = 0

    var expectedBytes: Int64 { upperBound - lowerBound + 1 }
}

struct RangeDownloadJob: Codable, Identifiable {
    let id: UUID
    let title: String
    let sourceURL: URL
    let expectedBytes: Int64
    let entityTag: String?
    let lastModified: String?
    let contentType: String?
    let chunkSize: Int64
    var chunks: [RangeDownloadChunk]
    var paused: Bool
    var completedFileName: String?
    var lastError: String?

    var completedBytes: Int64 {
        chunks.reduce(0) { total, chunk in
            total + (chunk.state == .complete ? chunk.expectedBytes : min(chunk.expectedBytes, chunk.bytesWritten))
        }
    }

    var progress: Double {
        guard expectedBytes > 0 else { return 0 }
        return min(1, Double(completedBytes) / Double(expectedBytes))
    }

    var isComplete: Bool { chunks.allSatisfy { $0.state == .complete } }
}

struct RemoteMediaInfo {
    let expectedBytes: Int64
    let entityTag: String?
    let lastModified: String?
    let contentType: String?
}

@MainActor
final class RangeDownloadManager: NSObject, ObservableObject, @preconcurrency URLSessionDownloadDelegate {
    static let shared = RangeDownloadManager()
    static let backgroundIdentifier = "com.mechy007.clipstudio.ios.rangedownload"

    @Published private(set) var jobs: [RangeDownloadJob] = []

    private let chunkSize: Int64 = 32 * 1024 * 1024
    private let maximumBytes: Int64 = 8 * 1024 * 1024 * 1024
    private let maximumConcurrentChunks = 3
    private let fileManager = FileManager.default
    private var session: URLSession!
    private var backgroundCompletionHandler: (() -> Void)?

    private var rootDirectory: URL {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("ClipStudio/RangeDownloads", isDirectory: true)
    }

    private override init() {
        super.init()
        try? fileManager.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.backgroundIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.httpMaximumConnectionsPerHost = maximumConcurrentChunks
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 60 * 60 * 24
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: OperationQueue.main)
        reloadJobs()
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor in
                self?.reconcile(tasks: tasks)
            }
        }
    }

    func start(url: URL, title: String, userConfirmedRights: Bool) async throws -> UUID {
        guard userConfirmedRights else { throw RangeDownloadError.permissionRequired }
        let info = try await Self.inspect(url: url, maximumBytes: maximumBytes)
        guard info.entityTag != nil || info.lastModified != nil else {
            throw RangeDownloadError.missingValidator
        }

        let id = UUID()
        let count = Int((info.expectedBytes + chunkSize - 1) / chunkSize)
        let chunks = (0..<count).map { index -> RangeDownloadChunk in
            let lower = Int64(index) * chunkSize
            let upper = min(info.expectedBytes - 1, lower + chunkSize - 1)
            return RangeDownloadChunk(index: index, lowerBound: lower, upperBound: upper, state: .queued)
        }

        let directory = directory(for: id)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let job = RangeDownloadJob(
            id: id,
            title: title.isEmpty ? url.lastPathComponent : title,
            sourceURL: url,
            expectedBytes: info.expectedBytes,
            entityTag: info.entityTag,
            lastModified: info.lastModified,
            contentType: info.contentType,
            chunkSize: chunkSize,
            chunks: chunks,
            paused: false,
            completedFileName: nil,
            lastError: nil
        )
        jobs.insert(job, at: 0)
        try persist(job)
        schedule(jobID: id)
        return id
    }

    func resume(jobID: UUID) async throws {
        guard var job = job(id: jobID) else { throw RangeDownloadError.missingJob }
        let latest = try await Self.inspect(url: job.sourceURL, maximumBytes: maximumBytes)
        let sameEntity = latest.expectedBytes == job.expectedBytes &&
            (job.entityTag == nil || latest.entityTag == job.entityTag) &&
            (job.lastModified == nil || latest.lastModified == job.lastModified)
        guard sameEntity else {
            job.lastError = RangeDownloadError.sourceChanged.localizedDescription
            update(job)
            try persist(job)
            throw RangeDownloadError.sourceChanged
        }

        job.paused = false
        job.lastError = nil
        // Preserve active background tasks; only retry chunks already queued.
        for index in job.chunks.indices where job.chunks[index].state == .queued {
            job.chunks[index].bytesWritten = 0
        }
        update(job)
        try persist(job)
        schedule(jobID: jobID)
    }

    func pause(jobID: UUID) {
        guard var job = job(id: jobID) else { return }
        job.paused = true
        for index in job.chunks.indices where job.chunks[index].state == .downloading {
            job.chunks[index].state = .queued
            job.chunks[index].bytesWritten = 0
            job.chunks[index].attempts = 0
        }
        update(job)
        try? persist(job)
        session.getAllTasks { tasks in
            for task in tasks where task.taskDescription?.hasPrefix("\(jobID.uuidString)|") == true {
                task.cancel()
            }
        }
    }

    func completedFile(for jobID: UUID) -> URL? {
        guard let job = job(id: jobID), let fileName = job.completedFileName else { return nil }
        return directory(for: jobID).appendingPathComponent(fileName)
    }

    func handleBackgroundEvents(_ completion: @escaping () -> Void) {
        backgroundCompletionHandler = completion
    }

    private func schedule(jobID: UUID) {
        guard var job = job(id: jobID), !job.paused, !job.isComplete else { return }
        let activeCount = job.chunks.filter { $0.state == .downloading }.count
        let availableSlots = max(0, maximumConcurrentChunks - activeCount)
        guard availableSlots > 0 else { return }

        let queued = job.chunks.filter { $0.state == .queued }.prefix(availableSlots)
        for chunk in queued {
            var request = URLRequest(url: job.sourceURL)
            request.httpMethod = "GET"
            request.setValue("bytes=\(chunk.lowerBound)-\(chunk.upperBound)", forHTTPHeaderField: "Range")
            if let entityTag = job.entityTag, !entityTag.hasPrefix("W/") {
                request.setValue(entityTag, forHTTPHeaderField: "If-Range")
            } else if let modified = job.lastModified {
                request.setValue(modified, forHTTPHeaderField: "If-Range")
            }
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

            let task = session.downloadTask(with: request)
            task.taskDescription = "\(jobID.uuidString)|\(chunk.index)"
            task.resume()
            if let index = job.chunks.firstIndex(where: { $0.index == chunk.index }) {
                job.chunks[index].state = .downloading
                job.chunks[index].bytesWritten = 0
                job.chunks[index].attempts += 1
            }
        }
        update(job)
        try? persist(job)
    }

    private func reconcile(tasks: [URLSessionTask]) {
        let activeDescriptions = Set(tasks.compactMap(\.taskDescription))
        for index in jobs.indices {
            var job = jobs[index]
            for chunkIndex in job.chunks.indices where job.chunks[chunkIndex].state == .downloading {
                let description = "\(job.id.uuidString)|\(job.chunks[chunkIndex].index)"
                if !activeDescriptions.contains(description) {
                    job.chunks[chunkIndex].state = .queued
                    job.chunks[chunkIndex].bytesWritten = 0
                }
            }
            update(job)
            try? persist(job)
        }

        for job in jobs where !job.paused && !job.isComplete {
            schedule(jobID: job.id)
        }
    }

    private func parseTaskDescription(_ value: String) -> (jobID: UUID, chunkIndex: Int)? {
        let pieces = value.split(separator: "|", maxSplits: 1)
        guard pieces.count == 2,
              let id = UUID(uuidString: String(pieces[0])),
              let index = Int(pieces[1]) else { return nil }
        return (id, index)
    }

    private func job(id: UUID) -> RangeDownloadJob? {
        jobs.first { $0.id == id }
    }

    private func update(_ job: RangeDownloadJob) {
        if let index = jobs.firstIndex(where: { $0.id == job.id }) {
            jobs[index] = job
        }
    }

    private func directory(for id: UUID) -> URL {
        rootDirectory.appendingPathComponent(id.uuidString, isDirectory: true)
    }

    private func chunkURL(jobID: UUID, index: Int) -> URL {
        directory(for: jobID).appendingPathComponent(String(format: "%06d.part", index))
    }

    private func manifestURL(jobID: UUID) -> URL {
        directory(for: jobID).appendingPathComponent("manifest.json")
    }

    private func persist(_ job: RangeDownloadJob) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(job).write(to: manifestURL(jobID: job.id), options: .atomic)
        try? fileManager.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: manifestURL(jobID: job.id).path
        )
    }

    private func reloadJobs() {
        let decoder = JSONDecoder()
        let directories = (try? fileManager.contentsOfDirectory(
            at: rootDirectory,
            includingPropertiesForKeys: nil
        )) ?? []
        jobs = directories.compactMap { directory in
            let manifest = directory.appendingPathComponent("manifest.json")
            guard let data = try? Data(contentsOf: manifest),
                  let job = try? decoder.decode(RangeDownloadJob.self, from: data) else { return nil }
            return job
        }.sorted { $0.id.uuidString > $1.id.uuidString }
    }

    private func fail(_ jobID: UUID, message: String) {
        guard var job = job(id: jobID) else { return }
        job.lastError = message
        update(job)
        try? persist(job)
    }

    private func validateAndStore(task: URLSessionDownloadTask, location: URL) {
        guard let parsed = task.taskDescription.flatMap(parseTaskDescription),
              var job = job(id: parsed.jobID),
              let chunkIndex = job.chunks.firstIndex(where: { $0.index == parsed.chunkIndex }),
              let response = task.response as? HTTPURLResponse else { return }

        let chunk = job.chunks[chunkIndex]
        let expectedRange = "bytes \(chunk.lowerBound)-\(chunk.upperBound)/\(job.expectedBytes)"
        let actualRange = Self.header("Content-Range", from: response)
        let validatorMatches: Bool
        if let entityTag = job.entityTag {
            validatorMatches = Self.header("ETag", from: response) == entityTag
        } else if let lastModified = job.lastModified {
            validatorMatches = Self.header("Last-Modified", from: response) == lastModified
        } else {
            validatorMatches = false
        }

        guard response.statusCode == 206,
              actualRange == expectedRange,
              validatorMatches else {
            fail(job.id, message: RangeDownloadError.invalidResponse.localizedDescription)
            return
        }

        do {
            let attributes = try fileManager.attributesOfItem(atPath: location.path)
            guard (attributes[.size] as? NSNumber)?.int64Value == chunk.expectedBytes else {
                fail(job.id, message: RangeDownloadError.chunkMismatch.localizedDescription)
                return
            }
            let destination = chunkURL(jobID: job.id, index: chunk.index)
            try? fileManager.removeItem(at: destination)
            try fileManager.moveItem(at: location, to: destination)
            job.chunks[chunkIndex].state = .complete
            job.chunks[chunkIndex].bytesWritten = chunk.expectedBytes
            job.lastError = nil
            update(job)
            try persist(job)
        } catch {
            fail(job.id, message: error.localizedDescription)
        }
    }

    private func finishIfReady(_ jobID: UUID) {
        guard var job = job(id: jobID), !job.paused, job.isComplete else { return }
        let originalExtension = job.sourceURL.pathExtension.lowercased()
        let outputExtension = ["mp4", "mov", "m4v"].contains(originalExtension) ? originalExtension : "mp4"
        let outputURL = directory(for: jobID).appendingPathComponent("recording").appendingPathExtension(outputExtension)
        do {
            try? fileManager.removeItem(at: outputURL)
            guard fileManager.createFile(atPath: outputURL.path, contents: nil) else {
                throw RangeDownloadError.chunkMismatch
            }
            let output = try FileHandle(forWritingTo: outputURL)
            defer { try? output.close() }
            for chunk in job.chunks.sorted(by: { $0.index < $1.index }) {
                let partURL = chunkURL(jobID: jobID, index: chunk.index)
                let data = try Data(contentsOf: partURL, options: .mappedIfSafe)
                guard Int64(data.count) == chunk.expectedBytes else { throw RangeDownloadError.chunkMismatch }
                try output.write(contentsOf: data)
            }
            try output.synchronize()
            let attributes = try fileManager.attributesOfItem(atPath: outputURL.path)
            guard (attributes[.size] as? NSNumber)?.int64Value == job.expectedBytes else {
                throw RangeDownloadError.chunkMismatch
            }

            job.completedFileName = outputURL.lastPathComponent
            job.lastError = nil
            update(job)
            try persist(job)
            for chunk in job.chunks {
                try? fileManager.removeItem(at: chunkURL(jobID: jobID, index: chunk.index))
            }
        } catch {
            fail(jobID, message: error.localizedDescription)
        }
    }

    private static func inspect(url: URL, maximumBytes: Int64) async throws -> RemoteMediaInfo {
        guard url.scheme?.lowercased() == "https",
              url.user == nil, url.password == nil,
              let host = url.host, !host.isEmpty else {
            throw RangeDownloadError.insecureURL
        }

        var head = URLRequest(url: url)
        head.httpMethod = "HEAD"
        head.timeoutInterval = 30
        let (_, headResponse) = try await URLSession.shared.data(for: head)
        guard let headHTTP = headResponse as? HTTPURLResponse,
              (200..<300).contains(headHTTP.statusCode) else {
            throw RangeDownloadError.unsupportedServer
        }
        guard let size = header("Content-Length", from: headHTTP).flatMap(Int64.init), size > 0 else {
            throw RangeDownloadError.unsupportedServer
        }
        guard size <= maximumBytes else { throw RangeDownloadError.fileTooLarge }

        var probe = URLRequest(url: url)
        probe.httpMethod = "GET"
        probe.setValue("bytes=0-0", forHTTPHeaderField: "Range")
        probe.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        probe.timeoutInterval = 30
        let (data, probeResponse) = try await URLSession.shared.data(for: probe)
        guard let http = probeResponse as? HTTPURLResponse,
              http.statusCode == 206,
              header("Content-Range", from: http) == "bytes 0-0/\(size)",
              data.count == 1 else {
            throw RangeDownloadError.unsupportedServer
        }
        guard (http.url ?? url).scheme?.lowercased() == "https" else {
            throw RangeDownloadError.insecureURL
        }

        let type = header("Content-Type", from: http)
        if type?.localizedCaseInsensitiveContains("text/html") == true ||
            type?.localizedCaseInsensitiveContains("mpegurl") == true {
            throw RangeDownloadError.unsupportedServer
        }
        let etag = header("ETag", from: http) ?? header("ETag", from: headHTTP)
        let usableETag = etag?.hasPrefix("W/") == true ? nil : etag

        return RemoteMediaInfo(
            expectedBytes: size,
            entityTag: usableETag,
            lastModified: header("Last-Modified", from: http) ?? header("Last-Modified", from: headHTTP),
            contentType: type ?? header("Content-Type", from: headHTTP)
        )
    }

    private static func header(_ name: String, from response: HTTPURLResponse) -> String? {
        response.allHeaderFields.first {
            String(describing: $0.key).caseInsensitiveCompare(name) == .orderedSame
        }.map { String(describing: $0.value) }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let parsed = task.taskDescription.flatMap(parseTaskDescription),
              var job = job(id: parsed.jobID),
              let chunk = job.chunks.first(where: { $0.index == parsed.chunkIndex }),
              let http = response as? HTTPURLResponse else {
            completionHandler(.cancel)
            return
        }

        let expectedRange = "bytes \(chunk.lowerBound)-\(chunk.upperBound)/\(job.expectedBytes)"
        let actualRange = Self.header("Content-Range", from: http)
        let validatorMatches: Bool
        if let entityTag = job.entityTag {
            validatorMatches = Self.header("ETag", from: http) == entityTag
        } else if let lastModified = job.lastModified {
            validatorMatches = Self.header("Last-Modified", from: http) == job.lastModified
        } else {
            validatorMatches = false
        }

        guard http.statusCode == 206, actualRange == expectedRange, validatorMatches else {
            job.paused = true
            if let index = job.chunks.firstIndex(where: { $0.index == parsed.chunkIndex }) {
                job.chunks[index].state = .failed
                job.chunks[index].bytesWritten = 0
            }
            job.lastError = RangeDownloadError.invalidResponse.localizedDescription
            update(job)
            try? persist(job)
            completionHandler(.cancel)
            return
        }

        completionHandler(.allow)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        validateAndStore(task: downloadTask, location: location)
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let parsed = downloadTask.taskDescription.flatMap(parseTaskDescription),
              var job = job(id: parsed.jobID),
              let index = job.chunks.firstIndex(where: { $0.index == parsed.chunkIndex }) else { return }
        job.chunks[index].bytesWritten = min(job.chunks[index].expectedBytes, max(0, totalBytesWritten))
        update(job)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let parsed = task.taskDescription.flatMap(parseTaskDescription),
              var job = job(id: parsed.jobID) else { return }

        if let error {
            if !job.paused {
                var shouldRetry = false
                if let index = job.chunks.firstIndex(where: { $0.index == parsed.chunkIndex }),
                   job.chunks[index].state != .complete {
                    shouldRetry = job.chunks[index].attempts < 4
                    job.chunks[index].state = shouldRetry ? .queued : .failed
                    job.chunks[index].bytesWritten = 0
                }
                job.lastError = error.localizedDescription
                update(job)
                try? persist(job)
                if shouldRetry {
                    schedule(jobID: job.id)
                } else {
                    job.paused = true
                    update(job)
                    try? persist(job)
                }
            }
            return
        }

        if job.isComplete {
            finishIfReady(job.id)
        } else if !job.paused {
            schedule(jobID: job.id)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let completion = backgroundCompletionHandler
        backgroundCompletionHandler = nil
        completion?()
    }
}
