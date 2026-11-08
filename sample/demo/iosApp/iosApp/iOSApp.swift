import AVFAudio
import SwiftUI

@main
struct iOSApp: App {
    // The player engine observes interruptions but never touches the audio session, so the app
    // claims one here. Playback category makes this a non-mixing client, which is what lets Siri,
    // calls and route changes actually interrupt it.
    init() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback)
        try? session.setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
