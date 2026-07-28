import FirebaseAuth
import Shared

final class AppleFirebaseAuthBridge: NSObject, IosFirebaseAuthBridge {
    func addAuthStateListener(listener: @escaping (IosFirebaseUser?) -> Void) -> IosAuthStateSubscription {
        let handle = Auth.auth().addStateDidChangeListener { _, user in
            listener(user.map(Self.sharedUser))
        }
        return AppleFirebaseAuthSubscription {
            Auth.auth().removeStateDidChangeListener(handle)
        }
    }

    func signUp(email: String, password: String, completion: @escaping (String?) -> Void) {
        Auth.auth().createUser(withEmail: email, password: password) { result, error in
            if let code = Self.stableErrorCode(error) {
                completion(code)
                return
            }
            guard let user = result?.user else {
                completion("no-current-user")
                return
            }
            user.sendEmailVerification { verificationError in
                completion(Self.stableErrorCode(verificationError))
            }
        }
    }

    func signIn(email: String, password: String, completion: @escaping (String?) -> Void) {
        Auth.auth().signIn(withEmail: email, password: password) { _, error in
            completion(Self.stableErrorCode(error))
        }
    }

    func sendPasswordReset(email: String, completion: @escaping (String?) -> Void) {
        Auth.auth().sendPasswordReset(withEmail: email) { error in
            completion(Self.stableErrorCode(error))
        }
    }

    func sendEmailVerification(completion: @escaping (String?) -> Void) {
        guard let user = Auth.auth().currentUser else {
            completion("no-current-user")
            return
        }
        user.sendEmailVerification { error in
            completion(Self.stableErrorCode(error))
        }
    }

    func reloadUser(completion: @escaping (IosFirebaseUser?, String?) -> Void) {
        guard let user = Auth.auth().currentUser else {
            completion(nil, "no-current-user")
            return
        }
        user.reload { error in
            let code = Self.stableErrorCode(error)
            completion(code == nil ? Auth.auth().currentUser.map(Self.sharedUser) : nil, code)
        }
    }

    func getIdToken(forceRefresh: Bool, completion: @escaping (String?, String?) -> Void) {
        guard let user = Auth.auth().currentUser else {
            completion(nil, "no-current-user")
            return
        }
        user.getIDTokenForcingRefresh(forceRefresh) { token, error in
            completion(token, Self.stableErrorCode(error))
        }
    }

    func signOut() -> String? {
        do {
            try Auth.auth().signOut()
            return nil
        } catch {
            return Self.stableErrorCode(error)
        }
    }

    private static func sharedUser(_ user: User) -> IosFirebaseUser {
        IosFirebaseUser(email: user.email, emailVerified: user.isEmailVerified)
    }

    private static func stableErrorCode(_ error: Error?) -> String? {
        guard let error else { return nil }
        switch FirebaseAuth.AuthErrorCode(rawValue: (error as NSError).code) {
        case .invalidEmail:
            return "invalid-email"
        case .invalidCredential, .wrongPassword, .userNotFound:
            return "invalid-credentials"
        case .emailAlreadyInUse:
            return "email-already-in-use"
        case .weakPassword:
            return "weak-password"
        case .userDisabled:
            return "user-disabled"
        case .tooManyRequests:
            return "too-many-requests"
        case .networkError:
            return "network"
        default:
            return "unknown"
        }
    }
}

private final class AppleFirebaseAuthSubscription: NSObject, IosAuthStateSubscription {
    private var cancellation: (() -> Void)?

    init(cancellation: @escaping () -> Void) {
        self.cancellation = cancellation
    }

    func cancel() {
        cancellation?()
        cancellation = nil
    }

    deinit {
        cancel()
    }
}
