import XCTest
@testable import ClipStudio

final class ClipStudioTests: XCTestCase {
    func testSRTParsingKeepsMillisecondTiming() {
        let source = """
        1
        00:00:01,250 --> 00:00:02,875
        First caption

        2
        00:00:03,000 --> 00:00:04,500
        Second caption
        """

        let cues = SubtitleCodec.parseSRT(source)
        XCTAssertEqual(cues.count, 2)
        XCTAssertEqual(cues[0].startMs, 1_250)
        XCTAssertEqual(cues[0].endMs, 2_875)
        XCTAssertEqual(cues[1].text, "Second caption")
    }

    func testSRTRoundTrip() {
        let cues = [
            CaptionCue(startMs: 250, endMs: 1_850, text: "Hello"),
            CaptionCue(startMs: 2_000, endMs: 3_500, text: "World")
        ]

        let encoded = SubtitleCodec.makeSRT(cues)
        let decoded = SubtitleCodec.parseSRT(encoded)
        XCTAssertEqual(decoded.map(\.startMs), cues.map(\.startMs))
        XCTAssertEqual(decoded.map(\.endMs), cues.map(\.endMs))
        XCTAssertEqual(decoded.map(\.text), cues.map(\.text))
    }

    func testYouTubeReferenceRecognizesCommonURLShapes() {
        XCTAssertEqual(
            YouTubeReference.videoID(from: "https://www.youtube.com/watch?v=abcDEF_1234"),
            "abcDEF_1234"
        )
        XCTAssertEqual(
            YouTubeReference.videoID(from: "https://youtu.be/abcDEF_1234"),
            "abcDEF_1234"
        )
        XCTAssertEqual(
            YouTubeReference.videoID(from: "https://www.youtube.com/live/abcDEF_1234"),
            "abcDEF_1234"
        )
        XCTAssertEqual(
            YouTubeReference.videoID(from: "https://www.youtube.com/shorts/abcDEF_1234"),
            "abcDEF_1234"
        )
    }

    func testYouTubeReferenceRejectsOtherHosts() {
        XCTAssertNil(YouTubeReference.videoID(from: "https://example.com/watch?v=abcDEF_1234"))
        XCTAssertNil(YouTubeReference.canonicalWatchURL(for: "not a URL"))
    }

    func testProjectJSONRoundTrip() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let project = ClipProject(
            id: UUID(),
            title: "Test recording",
            sourceName: "recording.mov",
            sourcePath: "Media/test.mov",
            durationMs: 12_000,
            trimStartMs: 1_000,
            trimEndMs: 9_000,
            layout: .verticalFit,
            quality: .p1080,
            muteAudio: true,
            captions: [CaptionCue(startMs: 2_000, endMs: 3_000, text: "Test caption")],
            exportPath: nil,
            createdAt: now,
            updatedAt: now
        )

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        let data = try encoder.encode(project)
        let restored = try decoder.decode(ClipProject.self, from: data)
        XCTAssertEqual(restored, project)
        XCTAssertEqual(restored.schemaVersion, 1)
    }
}
