import UIKit
import SwiftUI
import Shared
import FirebaseCore

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        let backendBaseUrl = Bundle.main.object(forInfoDictionaryKey: "JAIQAL_API_BASE_URL") as? String
            ?? "http://127.0.0.1:8080"
        let environmentName = Bundle.main.object(forInfoDictionaryKey: "JAIQAL_APP_ENVIRONMENT") as? String
            ?? "local"
        #if DEBUG
        let enableNetworkLogging = true
        #else
        let enableNetworkLogging = false
        #endif
        return MainViewControllerKt.MainViewController(
            backendBaseUrl: backendBaseUrl,
            environmentName: environmentName,
            firebaseAuthBridge: FirebaseApp.app() == nil ? nil : AppleFirebaseAuthBridge(),
            enableNetworkLogging: enableNetworkLogging
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
    }
}
