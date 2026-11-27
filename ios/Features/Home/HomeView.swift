import AVKit
import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct HomeView: View {
    @EnvironmentObject private var store: ProjectStore
    @State private var selectedProject: ClipProject?
    @State private var importError: String?

    var body: some View {
        TabView {
            NavigationStack {
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        header
                        if let latest = store.projects.first {
                            Button {
                                selectedProject = latest
                            } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    Label("Continue editing", systemImage: "play.rectangle.fill")
                                        .font(.headline)
                                    Text(latest.title).font(.title3.bold())
                                    Text("Last opened \(latest.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    ProgressView(value: Double(latest.selectedDurationMs), total: Double(max(1, latest.durationMs)))
                                        .tint(.cyan)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding()
                                .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
                            }
                            .buttonStyle(.plain)
                        } else {
                            ContentUnavailableView(
                                "No projects yet",
                                systemImage: "film.stack",
                                description: Text("Import a recording to create your first edit.")
                            )
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 20)
                        }

                        VStack(alignment: .leading, spacing: 12) {
                            Text("Your studio").font(.title2.bold())
                            Label("Local recordings and creator-authorized sources", systemImage: "folder")
                            Label("Resumable direct downloads and HLS replays", systemImage: "arrow.down.circle")
                            Label("Trim, canvas layouts, captions and MP4 export", systemImage: "scissors")
                        }
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.white.opacity(0.04), in: RoundedRectangle(cornerRadius: 18))
                    }
                    .padding()
                }
                .navigationTitle("Clip Studio")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text("\(store.projects.count) projects")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .tabItem { Label("Home", systemImage: "house") }

            ImportView()
                .tabItem { Label("Import", systemImage: "plus.square") }

            ProjectsView(openProject: { selectedProject = $0 })
                .tabItem { Label("Projects", systemImage: "folder") }
        }
        .tint(.cyan)
        .sheet(item: $selectedProject) { project in
            EditorView(project: project)
                .environmentObject(store)
        }
        .alert("Clip Studio", isPresented: Binding(
            get: { importError != nil },
            set: { if !$0 { importError = nil } }
        )) {
            Button("OK", role: .cancel) { importError = nil }
        } message: {
            Text(importError ?? "")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: "film.stack")
                .font(.system(size: 38, weight: .semibold))
                .foregroundStyle(.cyan)
            Text("Make the moment a clip.")
                .font(.largeTitle.bold())
            Text("Import a recording, shape the frame, add captions and export.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.top, 14)
    }
}

struct ImportView: View {
    @EnvironmentObject private var store: ProjectStore
    @StateObject private var rangeDownloads = RangeDownloadManager.shared
    @StateObject private var hlsDownloads = HLSReplayDownloader.shared

    @State private var isFileImporterPresented = false
    @State private var photosSelection: PhotosPickerItem?
    @State private var directURL = ""
    @State private var hlsURL = ""
    @State private var youtubeURL = ""
    @State private var title = ""
    @State private var directHasRights = false
    @State private var hlsHasRights = false
    @State private var isWorking = false
    @State private var message: String?
    @State private var errorMessage: String?
    @State private var openProject: ClipProject?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        isFileImporterPresented = true
                    } label: {
                        Label("Choose video from Files", systemImage: "folder")
                            .font(.headline)
                    }
                    PhotosPicker(selection: $photosSelection, matching: .videos, photoLibrary: .shared()) {
                        Label("Choose video from Photos", systemImage: "photo.on.rectangle")
                    }
                    Text("Import a recording already saved on this device, including creator-owned videos exported from YouTube Studio.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Local recording")
                }

                Section {
                    TextField("https://…/recording.mp4", text: $directURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    TextField("Recording title (optional)", text: $title)
                    Toggle("I own this recording or have explicit permission to download and edit it", isOn: $directHasRights)
                    Button {
                        Task { await startDirectDownload() }
                    } label: {
                        HStack {
                            if isWorking { ProgressView() }
                            Label("Start resumable download", systemImage: "arrow.down.circle")
                        }
                    }
                    .disabled(isWorking || directURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !directHasRights)
                    Text("Direct MP4/MOV/M4V over HTTPS only. The server must support byte ranges and provide a stable ETag or Last-Modified validator.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Authorized direct media")
                }

                Section {
                    TextField("https://…/playlist.m3u8", text: $hlsURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    Toggle("I own this recording or have explicit permission to download and edit it", isOn: $hlsHasRights)
                    Button {
                        startHLSDownload()
                    } label: {
                        Label("Download HLS replay", systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .disabled(hlsURL.isEmpty || !hlsHasRights)
                    Text("Supported HLS VOD only. iOS cannot save an active live broadcast as an offline replay; wait until the provider publishes a finished replay.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Finished livestream replay")
                }

                Section {
                    TextField("Paste a YouTube recording or replay link", text: $youtubeURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    if YouTubeReference.videoID(from: youtubeURL) != nil {
                        Label("YouTube recording link recognized", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Link("Open YouTube Studio to download your own recording", destination: URL(string: "https://studio.youtube.com/")!)
                    }
                    Text("Clip Studio does not extract or download YouTube media. For your own channel, download the recording/replay through YouTube Studio or Google Takeout, then import the saved video above. For third-party content, use only a source-approved file or URL.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("YouTube recording import")
                }

                if let message {
                    Section { Text(message).foregroundStyle(.secondary) }
                }

                if !rangeDownloads.jobs.isEmpty {
                    Section("Direct download queue") {
                        ForEach(rangeDownloads.jobs) { job in
                            VStack(alignment: .leading, spacing: 7) {
                                Text(job.title).font(.subheadline.weight(.semibold))
                                ProgressView(value: job.progress)
                                HStack {
                                    Text("\(Int(job.progress * 100))% • \(ByteCountFormatter.string(fromByteCount: job.completedBytes, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: job.expectedBytes, countStyle: .file))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    Spacer()
                                    if let file = rangeDownloads.completedFile(for: job) {
                                        Button("Import") { Task { await importCompleted(file, title: job.title) } }
                                    } else if job.paused || job.lastError != nil {
                                        Button("Resume") { Task { await resume(job.id) } }
                                    } else {
                                        Button("Pause") { rangeDownloads.pause(jobID: job.id) }
                                    }
                                }
                                if let error = job.lastError {
                                    Text(error).font(.caption).foregroundStyle(.red)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }

                if !hlsDownloads.jobs.isEmpty {
                    Section("HLS replay queue") {
                        ForEach(hlsDownloads.jobs) { job in
                            VStack(alignment: .leading, spacing: 7) {
                                Text(job.title).font(.subheadline.weight(.semibold))
                                ProgressView(value: job.progress)
                                HStack {
                                    Text("\(Int(job.progress * 100))% • \(job.state.rawValue)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    Spacer()
                                    if let package = hlsDownloads.packageURL(for: job) {
                                        Button("Import") { Task { await importCompleted(package, title: job.title) } }
                                    }
                                }
                                if let error = job.errorMessage {
                                    Text(error).font(.caption).foregroundStyle(.red)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("Import")
            .onChange(of: photosSelection) { _, selection in
                guard let selection else { return }
                Task { await importPhoto(selection) }
            }
            .fileImporter(
                isPresented: $isFileImporterPresented,
                allowedContentTypes: [.movie, .mpeg4Movie, .quickTimeMovie],
                allowsMultipleSelection: false
            ) { result in
                Task { await importLocalFile(result) }
            }
            .sheet(item: $openProject) { project in
                EditorView(project: project)
                    .environmentObject(store)
            }
            .alert("Import", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func importPhoto(_ selection: PhotosPickerItem) async {
        do {
            guard let video = try await selection.loadTransferable(type: ImportedVideoFile.self) else {
                throw ProjectStoreError.missingMedia
            }
            let project = try await store.importVideo(from: video.url)
            openProject = project
            photosSelection = nil
            message = "Imported \(project.sourceName) from Photos."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func importLocalFile(_ result: Result<[URL], Error>) async {
        do {
            guard let url = try result.get().first else { return }
            let project = try await store.importVideo(from: url)
            openProject = project
            message = "Imported \(project.sourceName)."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startDirectDownload() async {
        isWorking = true
        defer { isWorking = false }
        do {
            guard let url = URL(string: directURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                throw RangeDownloadError.insecureURL
            }
            _ = try await rangeDownloads.start(
                url: url,
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                userConfirmedRights: directHasRights
            )
            message = "Download queued. Each section is checked before it is saved."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startHLSDownload() {
        do {
            guard let url = URL(string: hlsURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                throw HLSReplayError.invalidURL
            }
            _ = try hlsDownloads.start(
                url: url,
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                userConfirmedRights: hlsHasRights
            )
            message = "HLS replay download queued."
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func resume(_ id: UUID) async {
        do {
            try await rangeDownloads.resume(jobID: id)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func importCompleted(_ url: URL, title: String) async {
        do {
            let project = try await store.importVideo(from: url)
            openProject = project
            message = "Imported \(title) into Projects."
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

struct ProjectsView: View {
    @EnvironmentObject private var store: ProjectStore
    let openProject: (ClipProject) -> Void
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                if store.projects.isEmpty {
                    ContentUnavailableView(
                        "No saved projects",
                        systemImage: "folder",
                        description: Text("Imported recordings and saved edits will appear here.")
                    )
                } else {
                    ForEach(store.projects) { project in
                        Button {
                            openProject(project)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(project.title).font(.headline).lineLimit(1)
                                Text("\(project.sourceName) • \(project.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Text("\(project.layout.title) • \(project.quality.title) • \(project.captions.count) captions")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 5)
                        }
                        .swipeActions {
                            Button(role: .destructive) {
                                do { try store.delete(project) }
                                catch { errorMessage = error.localizedDescription }
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Projects")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { store.reload() } label: { Image(systemName: "arrow.clockwise") }
                }
            }
            .alert("Projects", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }
}


private struct ImportedVideoFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let original = received.file
            let ext = original.pathExtension.isEmpty ? "mov" : original.pathExtension
            let destination = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(ext)
            try FileManager.default.copyItem(at: original, to: destination)
            return ImportedVideoFile(url: destination)
        }
    }
}
