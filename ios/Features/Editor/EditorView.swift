import AVKit
import SwiftUI
import UniformTypeIdentifiers

struct EditorView: View {
    @EnvironmentObject private var store: ProjectStore
    @Environment(\.dismiss) private var dismiss

    @State private var project: ClipProject
    @State private var player: AVPlayer
    @StateObject private var transcriber = WhisperTranscriptionService.shared
    @State private var isExporting = false
    @State private var exportURL: URL?
    @State private var subtitleExportURL: URL?
    @State private var errorMessage: String?
    @State private var isCaptionEditorPresented = false
    @State private var isSubtitleImporterPresented = false
    @State private var captionText = ""
    @State private var captionStart = "0"
    @State private var captionDuration = "3"

    init(project: ClipProject) {
        _project = State(initialValue: project)
        _player = State(initialValue: AVPlayer())
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VideoPlayer(player: player)
                        .frame(height: 230)
                        .clipShape(RoundedRectangle(cornerRadius: 18))

                    VStack(alignment: .leading, spacing: 8) {
                        Text(project.title).font(.title2.bold()).lineLimit(2)
                        Text("\(format(project.durationMs)) source • \(format(project.selectedDurationMs)) selected")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    GroupBox("Trim") {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Start")
                                Spacer()
                                TextField("Start seconds", value: Binding(
                                    get: { Double(project.trimStartMs) / 1000 },
                                    set: { project.trimStartMs = Int64(max(0, $0) * 1000); save() }
                                ), format: .number.precision(.fractionLength(2)))
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(width: 110)
                            }
                            Slider(
                                value: Binding(
                                    get: { Double(project.trimStartMs) / 1000 },
                                    set: { project.trimStartMs = min(Int64($0 * 1000), max(0, project.trimEndMs - 200)); save() }
                                ),
                                in: 0...max(0.2, Double(project.trimEndMs) / 1000 - 0.2)
                            )
                            HStack {
                                Text("End")
                                Spacer()
                                Text(format(project.trimEndMs))
                                    .monospacedDigit()
                            }
                            Slider(
                                value: Binding(
                                    get: { Double(project.trimEndMs) / 1000 },
                                    set: { project.trimEndMs = max(Int64($0 * 1000), project.trimStartMs + 200); save() }
                                ),
                                in: min(Double(project.durationMs) / 1000, Double(project.trimStartMs) / 1000 + 0.2)...max(0.2, Double(project.durationMs) / 1000)
                            )
                            Button("Play selected range") {
                                let start = CMTime(value: project.trimStartMs, timescale: 1000)
                                player.seek(to: start)
                                player.play()
                            }
                            .buttonStyle(.bordered)
                        }
                    }

                    GroupBox("Canvas and export") {
                        VStack(alignment: .leading, spacing: 12) {
                            Picker("Layout", selection: $project.layout) {
                                ForEach(CanvasLayout.allCases) { layout in
                                    Text(layout.title).tag(layout)
                                }
                            }
                            Picker("Quality", selection: $project.quality) {
                                ForEach(ExportQuality.allCases) { quality in
                                    Text(quality.title).tag(quality)
                                }
                            }
                            Toggle("Mute original audio", isOn: $project.muteAudio)
                        }
                        .onChange(of: project.layout) { _, _ in save() }
                        .onChange(of: project.quality) { _, _ in save() }
                        .onChange(of: project.muteAudio) { _, _ in save() }
                    }

                    GroupBox("Captions") {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("\(project.captions.count) caption cues")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            ForEach(project.captions.prefix(6)) { cue in
                                HStack(alignment: .top) {
                                    Text("\(format(cue.startMs))–\(format(cue.endMs))")
                                        .font(.caption.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                    Text(cue.text).lineLimit(2)
                                    Spacer(minLength: 0)
                                    Button(role: .destructive) {
                                        project.captions.removeAll { $0.id == cue.id }
                                        save()
                                    } label: {
                                        Image(systemName: "trash")
                                    }
                                    .buttonStyle(.borderless)
                                }
                            }
                            HStack {
                                Button {
                                    isCaptionEditorPresented = true
                                } label: {
                                    Label("Add caption", systemImage: "plus")
                                }
                                .buttonStyle(.bordered)
                                Button {
                                    isSubtitleImporterPresented = true
                                } label: {
                                    Label("Import SRT", systemImage: "text.badge.plus")
                                }
                                .buttonStyle(.bordered)
                            }
                            Button {
                                Task { await transcribeSelectedRange() }
                            } label: {
                                HStack {
                                    if transcriber.isWorking { ProgressView() }
                                    Label(transcriber.isWorking ? transcriber.status : "Generate captions on device", systemImage: "waveform")
                                }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(transcriber.isWorking || project.selectedDurationMs > 120_000 || project.selectedDurationMs == 0)
                            Text("Uses the multilingual Whisper tiny model on-device. First use downloads the model; automatic captioning is limited to two-minute selections to control memory use.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Button {
                                exportSRT()
                            } label: {
                                Label("Export captions as SRT", systemImage: "doc.text")
                            }
                            .buttonStyle(.bordered)
                            if let subtitleExportURL {
                                ShareLink(item: subtitleExportURL) {
                                    Label("Share SRT file", systemImage: "square.and.arrow.up")
                                }
                            }
                        }
                    }

                    Button {
                        Task { await export() }
                    } label: {
                        HStack {
                            if isExporting { ProgressView().tint(.white) }
                            Label(isExporting ? "Exporting MP4…" : "Export MP4", systemImage: "square.and.arrow.up")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isExporting)

                    if let exportURL {
                        ShareLink(item: exportURL) {
                            Label("Share exported clip", systemImage: "square.and.arrow.up.on.square")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding()
            }
            .navigationTitle("Editor")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { save(); dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") { save() }
                }
            }
            .sheet(isPresented: $isCaptionEditorPresented) {
                captionSheet
                    .presentationDetents([.medium, .large])
            }
            .fileImporter(
                isPresented: $isSubtitleImporterPresented,
                allowedContentTypes: [.plainText, UTType(filenameExtension: "srt") ?? .plainText],
                allowsMultipleSelection: false
            ) { result in
                importSRT(result)
            }
            .alert("Editor", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .onDisappear { player.pause(); save() }
        }
        .onAppear {
            player.replaceCurrentItem(with: AVPlayerItem(url: store.mediaURL(for: project)))
        }
    }

    private var captionSheet: some View {
        NavigationStack {
            Form {
                TextField("Caption text", text: $captionText, axis: .vertical)
                    .lineLimit(2...5)
                TextField("Start time (seconds)", text: $captionStart)
                    .keyboardType(.decimalPad)
                TextField("Duration (seconds)", text: $captionDuration)
                    .keyboardType(.decimalPad)
                Text("Caption times are relative to the original recording.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .navigationTitle("New caption")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isCaptionEditorPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let start = Int64((Double(captionStart) ?? 0) * 1000)
                        let duration = Int64((Double(captionDuration) ?? 3) * 1000)
                        guard !captionText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                              duration > 0, start >= 0, start < project.durationMs else { return }
                        project.captions.append(CaptionCue(
                            startMs: start,
                            endMs: min(project.durationMs, start + duration),
                            text: captionText.trimmingCharacters(in: .whitespacesAndNewlines)
                        ))
                        project.captions.sort { $0.startMs < $1.startMs }
                        save()
                        captionText = ""
                        isCaptionEditorPresented = false
                    }
                    .disabled(captionText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func transcribeSelectedRange() async {
        do {
            let cues = try await transcriber.transcribeVideo(
                sourceURL: store.mediaURL(for: project),
                trimStartMs: project.trimStartMs,
                trimEndMs: project.trimEndMs
            )
            project.captions = cues
            save()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func importSRT(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let hasAccess = url.startAccessingSecurityScopedResource()
            defer { if hasAccess { url.stopAccessingSecurityScopedResource() } }
            let source = try String(contentsOf: url, encoding: .utf8)
            let cues = SubtitleCodec.parseSRT(source)
            guard !cues.isEmpty else {
                errorMessage = "No valid SRT caption cues were found."
                return
            }
            project.captions = cues
            save()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func exportSRT() {
        do {
            try FileManager.default.createDirectory(at: store.exportsDirectory, withIntermediateDirectories: true)
            let url = store.exportsDirectory
                .appendingPathComponent("captions-\(project.id.uuidString)")
                .appendingPathExtension("srt")
            try SubtitleCodec.makeSRT(project.captions).write(to: url, atomically: true, encoding: .utf8)
            subtitleExportURL = url
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func save() {
        do {
            project.normalizeTimes()
            try store.save(project)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func export() async {
        isExporting = true
        defer { isExporting = false }
        do {
            try store.save(project)
            let result = try await VideoEditorService.export(
                project: project,
                sourceURL: store.mediaURL(for: project),
                destinationDirectory: store.exportsDirectory
            )
            try store.recordExport(result, for: project)
            exportURL = result
            project.exportPath = "Exports/\(result.lastPathComponent)"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func format(_ milliseconds: Int64) -> String {
        let totalSeconds = max(0, milliseconds) / 1000
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        return hours > 0
            ? String(format: "%lld:%02lld:%02lld", hours, minutes, seconds)
            : String(format: "%lld:%02lld", minutes, seconds)
    }
}
