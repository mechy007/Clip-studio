import AVFoundation
import Combine
import Foundation

enum CanvasLayout: String, CaseIterable, Codable, Identifiable {
    case original
    case verticalFill
    case verticalFit
    case square

    var id: String { rawValue }

    var title: String {
        switch self {
        case .original: "Original"
        case .verticalFill: "Vertical fill"
        case .verticalFit: "Vertical fit"
        case .square: "Square"
        }
    }
}

enum ExportQuality: String, CaseIterable, Codable, Identifiable {
    case p720
    case p1080
    case p1440

    var id: String { rawValue }

    var title: String {
        switch self {
        case .p720: "720p"
        case .p1080: "1080p"
        case .p1440: "1440p"
        }
    }

    var longEdge: CGFloat {
        switch self {
        case .p720: 1280
        case .p1080: 1920
        case .p1440: 2560
        }
    }
}

struct CaptionCue: Codable, Identifiable, Equatable {
    var id: UUID = UUID()
    var startMs: Int64
    var endMs: Int64
    var text: String
}

struct ClipProject: Codable, Identifiable, Equatable {
    var schemaVersion: Int = 1
    var id: UUID
    var title: String
    var sourceName: String
    /// Path relative to ProjectStore.rootDirectory.
    var sourcePath: String
    var durationMs: Int64
    var trimStartMs: Int64
    var trimEndMs: Int64
    var layout: CanvasLayout
    var quality: ExportQuality
    var muteAudio: Bool
    var captions: [CaptionCue]
    var exportPath: String?
    var createdAt: Date
    var updatedAt: Date

    var selectedDurationMs: Int64 {
        max(0, trimEndMs - trimStartMs)
    }

    mutating func normalizeTimes() {
        durationMs = max(0, durationMs)
        trimStartMs = min(max(0, trimStartMs), durationMs)
        trimEndMs = min(max(trimStartMs, trimEndMs), durationMs)
        captions = captions.filter { $0.endMs > $0.startMs && !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
}

enum ProjectStoreError: LocalizedError {
    case missingMedia
    case invalidDuration
    case cannotCreateDirectory
    case cannotReadProject

    var errorDescription: String? {
        switch self {
        case .missingMedia: "The imported recording could not be found."
        case .invalidDuration: "This file does not contain a readable video track."
        case .cannotCreateDirectory: "Clip Studio could not create its project folder."
        case .cannotReadProject: "This project file could not be read."
        }
    }
}

@MainActor
final class ProjectStore: ObservableObject {
    @Published private(set) var projects: [ClipProject] = []

    let rootDirectory: URL
    let mediaDirectory: URL
    let exportsDirectory: URL
    let projectsDirectory: URL

    init(fileManager: FileManager = .default) {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        rootDirectory = documents.appendingPathComponent("ClipStudio", isDirectory: true)
        mediaDirectory = rootDirectory.appendingPathComponent("Media", isDirectory: true)
        exportsDirectory = rootDirectory.appendingPathComponent("Exports", isDirectory: true)
        projectsDirectory = rootDirectory.appendingPathComponent("Projects", isDirectory: true)

        do {
            try fileManager.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: exportsDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: projectsDirectory, withIntermediateDirectories: true)
        } catch {
            // The first actual save/import reports the underlying filesystem error.
        }
        reload()
    }

    func reload() {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: projectsDirectory,
            includingPropertiesForKeys: nil
        )) ?? []

        projects = urls
            .filter { $0.pathExtension.lowercased() == "json" }
            .compactMap { url in
                guard let data = try? Data(contentsOf: url),
                      let project = try? decoder.decode(ClipProject.self, from: data) else {
                    return nil
                }
                return project
            }
            .sorted { $0.updatedAt > $1.updatedAt }
    }

    func importVideo(from sourceURL: URL) async throws -> ClipProject {
        let hasSecurityScope = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if hasSecurityScope {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw ProjectStoreError.missingMedia
        }

        try FileManager.default.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)

        let id = UUID()
        let ext = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension.lowercased()
        let destination = mediaDirectory.appendingPathComponent(id.uuidString).appendingPathExtension(ext)
        try FileManager.default.copyItem(at: sourceURL, to: destination)

        do {
            let asset = AVURLAsset(url: destination)
            let duration = try await asset.load(.duration)
            let seconds = CMTimeGetSeconds(duration)
            guard seconds.isFinite, seconds > 0 else {
                throw ProjectStoreError.invalidDuration
            }

            let videoTracks = try await asset.loadTracks(withMediaType: .video)
            guard !videoTracks.isEmpty else { throw ProjectStoreError.invalidDuration }

            let milliseconds = Int64((seconds * 1000).rounded())
            let project = ClipProject(
                id: id,
                title: sourceURL.deletingPathExtension().lastPathComponent,
                sourceName: sourceURL.lastPathComponent,
                sourcePath: "Media/\(destination.lastPathComponent)",
                durationMs: milliseconds,
                trimStartMs: 0,
                trimEndMs: milliseconds,
                layout: .original,
                quality: .p1080,
                muteAudio: false,
                captions: [],
                exportPath: nil,
                createdAt: Date(),
                updatedAt: Date()
            )
            try save(project)
            return project
        } catch {
            try? FileManager.default.removeItem(at: destination)
            throw error
        }
    }

    func save(_ incoming: ClipProject) throws {
        var project = incoming
        project.normalizeTimes()
        project.updatedAt = Date()

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(project)
        let destination = projectFile(for: project.id)
        try data.write(to: destination, options: .atomic)

        if let index = projects.firstIndex(where: { $0.id == project.id }) {
            projects[index] = project
        } else {
            projects.append(project)
        }
        projects.sort { $0.updatedAt > $1.updatedAt }
    }

    func delete(_ project: ClipProject) throws {
        try? FileManager.default.removeItem(at: projectFile(for: project.id))
        let otherProjectsUseMedia = projects.contains {
            $0.id != project.id && $0.sourcePath == project.sourcePath
        }
        if !otherProjectsUseMedia {
            try? FileManager.default.removeItem(at: rootDirectory.appendingPathComponent(project.sourcePath))
        }
        if let exportPath = project.exportPath {
            try? FileManager.default.removeItem(at: rootDirectory.appendingPathComponent(exportPath))
        }
        projects.removeAll { $0.id == project.id }
    }

    func mediaURL(for project: ClipProject) -> URL {
        rootDirectory.appendingPathComponent(project.sourcePath)
    }

    func exportURL(for project: ClipProject) -> URL? {
        guard let path = project.exportPath else { return nil }
        return rootDirectory.appendingPathComponent(path)
    }

    func recordExport(_ url: URL, for project: ClipProject) throws {
        var updated = project
        updated.exportPath = "Exports/\(url.lastPathComponent)"
        try save(updated)
    }

    func projectFile(for id: UUID) -> URL {
        projectsDirectory.appendingPathComponent(id.uuidString).appendingPathExtension("json")
    }
}
