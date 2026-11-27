import SwiftUI
import UIKit

@MainActor
final class ClipStudioAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        switch identifier {
        case RangeDownloadManager.backgroundIdentifier:
            RangeDownloadManager.shared.handleBackgroundEvents(completionHandler)
        case HLSReplayDownloader.backgroundIdentifier:
            HLSReplayDownloader.shared.handleBackgroundEvents(completionHandler)
        default:
            completionHandler()
        }
    }
}

@main
struct ClipStudioApp: App {
    @UIApplicationDelegateAdaptor(ClipStudioAppDelegate.self) private var appDelegate
    @StateObject private var projectStore = ProjectStore()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(projectStore)
                .preferredColorScheme(.dark)
        }
    }
}
