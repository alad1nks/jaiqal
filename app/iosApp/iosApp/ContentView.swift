import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        let backendBaseUrl = Bundle.main.object(forInfoDictionaryKey: "JAIQAL_API_BASE_URL") as? String
            ?? "http://127.0.0.1:8080"
        let environmentName = Bundle.main.object(forInfoDictionaryKey: "JAIQAL_APP_ENVIRONMENT") as? String
            ?? "local"
        return MainViewControllerKt.MainViewController(
            backendBaseUrl: backendBaseUrl,
            environmentName: environmentName
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
