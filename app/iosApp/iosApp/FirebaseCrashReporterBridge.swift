import FirebaseCrashlytics
import Shared

final class AppleFirebaseCrashReporterBridge: NSObject, IosCrashReporterBridge {
    func recordNonFatal(code: String) {
        let error = NSError(
            domain: "com.alad1nks.jaiqal.nonfatal",
            code: Self.stableCode(code),
            userInfo: [NSLocalizedDescriptionKey: code]
        )
        Crashlytics.crashlytics().record(error: error)
    }

    private static func stableCode(_ code: String) -> Int {
        switch code {
        case "BACKEND_SESSION_SYNC": return 1
        default: return 0
        }
    }
}
