import Foundation

enum ImportSourceKind: String, Codable {
    case localFile
    case authorizedDirectFile
    case authorizedHLS
    case youtube
}

struct ImportSourceDescriptor: Codable {
    let id: UUID
    let kind: ImportSourceKind
    let displayName: String
    let sourceURL: URL?
    let isCreatorAuthorized: Bool
}

enum ImportProviderError: Error {
    case authorizationRequired
    case unsupportedSource
    case unavailable
}

protocol ImportProvider {
    var kind: ImportSourceKind { get }
    func resolve(_ input: String) async throws -> ImportSourceDescriptor
}

enum YouTubeReference {
    static func videoID(from input: String) -> String? {
        guard let components = URLComponents(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              let host = components.host?.lowercased() else { return nil }

        if host == "youtu.be" || host == "www.youtu.be" {
            return components.path.split(separator: "/").first.map(String.init)
        }

        guard host == "youtube.com" || host.hasSuffix(".youtube.com") else { return nil }
        if let id = components.queryItems?.first(where: { $0.name == "v" })?.value, !id.isEmpty {
            return id
        }

        let parts = components.path.split(separator: "/")
        guard parts.count >= 2,
              ["live", "shorts", "embed", "v"].contains(String(parts[0])) else { return nil }
        return String(parts[1])
    }

    static func canonicalWatchURL(for input: String) -> URL? {
        guard let id = videoID(from: input),
              id.range(of: #"^[A-Za-z0-9_-]{6,}$"#, options: .regularExpression) != nil else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = "www.youtube.com"
        components.path = "/watch"
        components.queryItems = [URLQueryItem(name: "v", value: id)]
        return components.url
    }
}

/// Recognizes a YouTube recording reference only. It does not retrieve or cache
/// audiovisual bytes; metadata recognition is not download authorization.
struct YouTubeImportProvider: ImportProvider {
    let kind: ImportSourceKind = .youtube

    func resolve(_ input: String) async throws -> ImportSourceDescriptor {
        guard let url = YouTubeReference.canonicalWatchURL(for: input),
              let id = YouTubeReference.videoID(from: input) else {
            throw ImportProviderError.unsupportedSource
        }
        return ImportSourceDescriptor(
            id: UUID(),
            kind: .youtube,
            displayName: "YouTube recording \(id)",
            sourceURL: url,
            isCreatorAuthorized: false
        )
    }
}

/// Resolves a direct HTTPS media URL without transferring bytes. The user must
/// separately confirm that they have permission before the downloader starts.
struct AuthorizedDirectImportProvider: ImportProvider {
    let kind: ImportSourceKind = .authorizedDirectFile

    func resolve(_ input: String) async throws -> ImportSourceDescriptor {
        guard let url = URL(string: input.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme?.lowercased() == "https",
              url.user == nil, url.password == nil,
              ["mp4", "mov", "m4v"].contains(url.pathExtension.lowercased()) else {
            throw ImportProviderError.unsupportedSource
        }
        return ImportSourceDescriptor(
            id: UUID(),
            kind: kind,
            displayName: url.lastPathComponent,
            sourceURL: url,
            isCreatorAuthorized: false
        )
    }
}

/// Describes replay readiness separately from media transfer. A replay is
/// downloadable through the HLS provider only when published as supported VOD.
enum ReplayAvailability: String, Codable {
    case upcoming
    case live
    case processing
    case ready
    case unavailable
}

struct ReplayDescriptor: Codable, Identifiable {
    let id: String
    let title: String
    let availability: ReplayAvailability
    let source: ImportSourceDescriptor?
    let lastCheckedAt: Date
}

protocol ReplaySourceResolving {
    func replay(for input: String) async throws -> ReplayDescriptor
}

struct ProgressiveDownloadCapability: Codable {
    let supportsByteRanges: Bool
    let expectedByteCount: Int64?
    let entityTag: String?
    let lastModified: String?
    let contentType: String?
}
