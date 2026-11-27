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

/// YouTube media-byte access is intentionally gated. Metadata access does not
/// grant permission to download or cache audiovisual content. Enable a concrete
/// provider only after the required written authorization and App Review review.
struct YouTubeImportProvider: ImportProvider {
    let kind: ImportSourceKind = .youtube

    func resolve(_ input: String) async throws -> ImportSourceDescriptor {
        throw ImportProviderError.authorizationRequired
    }
}
