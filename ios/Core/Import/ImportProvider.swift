import Foundation

enum ImportSourceKind: String, Codable {
    case localFile
    case authorizedDirectFile
    case authorizedHLS
    case youtube
}

struct ImportSourceDescriptor: Codable, Identifiable {
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

/// Describes replay readiness separately from the media download itself.
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

/// A provider may expose a replay only when it is authorized to provide the
/// underlying media bytes. Metadata discovery alone is not download permission.
protocol ReplaySourceResolving {
    func replay(for input: String) async throws -> ReplayDescriptor
}

/// Capability discovered from a creator-authorized direct media source.
struct ProgressiveDownloadCapability: Codable {
    let supportsByteRanges: Bool
    let expectedByteCount: Int64?
    let entityTag: String?
    let lastModified: String?
    let contentType: String?
}

/// YouTube media-byte access is intentionally gated. Metadata access does not
/// grant permission to download or cache audiovisual content. Enable a concrete
/// provider only after the required written authorization and App Review review.
struct YouTubeImportProvider: ImportProvider {
    let kind: ImportSourceKind = .youtube

    func resolve(_ input: String) async throws -> ImportSourceDescriptor {
        throw ImportProviderError.authorizationRequired
    }
}
