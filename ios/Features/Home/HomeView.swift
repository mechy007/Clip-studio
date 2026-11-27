import AVKit
import SwiftUI
import UniformTypeIdentifiers

struct HomeView: View {
    @State private var isImportPresented = false
    @State private var player: AVPlayer?
    @State private var importedFileName: String?
    @State private var importError: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Image(systemName: "film.stack")
                        .font(.system(size: 42, weight: .medium))
                        .foregroundStyle(.tint)
                    Text("Clip Studio")
                        .font(.largeTitle.bold())
                    Text("Turn recordings into your next great clip.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 32)

                if let player {
                    VideoPlayer(player: player)
                        .frame(minHeight: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                } else {
                    ContentUnavailableView(
                        "No video selected",
                        systemImage: "video",
                        description: Text("Import a recording to begin editing.")
                    )
                    .frame(maxHeight: .infinity)
                }

                if let importedFileName {
                    Text(importedFileName)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Button {
                    isImportPresented = true
                } label: {
                    Label("Import video", systemImage: "plus")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding()
                }
                .buttonStyle(.borderedProminent)
                .padding(.bottom, 12)
            }
            .padding()
            .navigationTitle("Home")
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(
                isPresented: $isImportPresented,
                allowedContentTypes: [.movie, .mpeg4Movie, .quickTimeMovie],
                allowsMultipleSelection: false,
                onCompletion: handleImport
            )
            .alert("Could not import video", isPresented: Binding(
                get: { importError != nil },
                set: { if !$0 { importError = nil } }
            )) {
                Button("OK", role: .cancel) { importError = nil }
            } message: {
                Text(importError ?? "Please try another video.")
            }
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        do {
            guard let sourceURL = try result.get().first else { return }
            let hasAccess = sourceURL.startAccessingSecurityScopedResource()
            defer {
                if hasAccess {
                    sourceURL.stopAccessingSecurityScopedResource()
                }
            }

            let fileManager = FileManager.default
            let importDirectory = fileManager.urls(
                for: .documentDirectory,
                in: .userDomainMask
            )[0].appendingPathComponent("Imports", isDirectory: true)

            try fileManager.createDirectory(
                at: importDirectory,
                withIntermediateDirectories: true
            )

            let destinationURL = importDirectory.appendingPathComponent(
                UUID().uuidString + "-" + sourceURL.lastPathComponent
            )
            try fileManager.copyItem(at: sourceURL, to: destinationURL)

            player?.pause()
            player = AVPlayer(url: destinationURL)
            importedFileName = sourceURL.lastPathComponent
            player?.play()
        } catch {
            importError = error.localizedDescription
        }
    }
}
