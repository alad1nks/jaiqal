import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import GoogleSignIn
import Security
import Shared
import UIKit

final class AppleFirebaseAuthBridge: NSObject, IosFirebaseAuthBridge {
    private static let googleSignInCancelledErrorCode = -5
    private var googleSignInInProgress = false
    private var appleRawNonce: String?
    private var appleCompletion: ((String?) -> Void)?
    private var appleAuthorizationController: ASAuthorizationController?
    private var applePresentationAnchor: ASPresentationAnchor?

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

    func signIn(method: FederatedAuthMethod, completion: @escaping (String?) -> Void) {
        switch method {
        case .google:
            signInWithGoogle(completion: completion)
        case .apple:
            signInWithApple(completion: completion)
        default:
            completion("provider-unavailable")
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

    private func signInWithGoogle(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                completion("provider-unavailable")
                return
            }
            guard !self.googleSignInInProgress,
                  self.appleCompletion == nil else {
                completion("provider-unavailable")
                return
            }
            guard let clientID = FirebaseApp.app()?.options.clientID,
                  !clientID.isEmpty,
                  let presenter = Self.presentingViewController() else {
                completion("provider-unavailable")
                return
            }

            self.googleSignInInProgress = true
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
                if let error {
                    self.finishGoogleSignIn(
                        errorCode: Self.stableGoogleSignInErrorCode(error),
                        completion: completion
                    )
                    return
                }
                guard let user = result?.user,
                      let idToken = user.idToken?.tokenString,
                      !idToken.isEmpty,
                      !user.accessToken.tokenString.isEmpty else {
                    self.finishGoogleSignIn(errorCode: "invalid-credentials", completion: completion)
                    return
                }

                let credential = GoogleAuthProvider.credential(
                    withIDToken: idToken,
                    accessToken: user.accessToken.tokenString
                )
                Auth.auth().signIn(with: credential) { _, firebaseError in
                    self.finishGoogleSignIn(
                        errorCode: Self.stableErrorCode(firebaseError),
                        completion: completion
                    )
                }
            }
        }
    }

    private func finishGoogleSignIn(errorCode: String?, completion: @escaping (String?) -> Void) {
        googleSignInInProgress = false
        completion(errorCode)
    }

    private func signInWithApple(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self else {
                completion("provider-unavailable")
                return
            }
            guard !self.googleSignInInProgress,
                  self.appleCompletion == nil,
                  let presentationAnchor = Self.presentationAnchor(),
                  let rawNonce = Self.randomNonce() else {
                completion("provider-unavailable")
                return
            }

            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [.fullName, .email]
            request.nonce = Self.sha256(rawNonce)

            let controller = ASAuthorizationController(authorizationRequests: [request])
            self.appleRawNonce = rawNonce
            self.appleCompletion = completion
            self.appleAuthorizationController = controller
            self.applePresentationAnchor = presentationAnchor
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }

    private func finishAppleSignIn(errorCode: String?) {
        let completion = appleCompletion
        appleRawNonce = nil
        appleCompletion = nil
        appleAuthorizationController = nil
        applePresentationAnchor = nil
        completion?(errorCode)
    }

    private static func randomNonce(length: Int = 32) -> String? {
        guard length > 0 else { return nil }
        let characterSet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var result = ""
        result.reserveCapacity(length)

        while result.count < length {
            var randomByte: UInt8 = 0
            guard SecRandomCopyBytes(kSecRandomDefault, 1, &randomByte) == errSecSuccess else {
                return nil
            }
            if randomByte < characterSet.count {
                result.append(characterSet[Int(randomByte)])
            }
        }
        return result
    }

    private static func sha256(_ input: String) -> String {
        let digest = SHA256.hash(data: Data(input.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private static func presentingViewController() -> UIViewController? {
        let windowScene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        guard let root = windowScene?.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            return nil
        }
        return topViewController(from: root)
    }

    private static func presentationAnchor() -> ASPresentationAnchor? {
        presentingViewController()?.view.window
    }

    private static func topViewController(from viewController: UIViewController) -> UIViewController {
        if let presented = viewController.presentedViewController {
            return topViewController(from: presented)
        }
        if let navigation = viewController as? UINavigationController,
           let visible = navigation.visibleViewController {
            return topViewController(from: visible)
        }
        if let tab = viewController as? UITabBarController,
           let selected = tab.selectedViewController {
            return topViewController(from: selected)
        }
        return viewController
    }

    private static func stableGoogleSignInErrorCode(_ error: Error) -> String {
        let nsError = error as NSError
        if nsError.domain == kGIDSignInErrorDomain,
           nsError.code == googleSignInCancelledErrorCode {
            return "cancelled"
        }
        if containsNetworkError(nsError) {
            return "network"
        }
        if nsError.domain == kGIDSignInErrorDomain {
            return "provider-unavailable"
        }
        return "unknown"
    }

    private static func containsNetworkError(_ error: NSError) -> Bool {
        if error.domain == NSURLErrorDomain {
            return true
        }
        guard let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError,
              underlying !== error else {
            return false
        }
        return containsNetworkError(underlying)
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
        case .accountExistsWithDifferentCredential:
            return "account-exists-with-different-credential"
        case .credentialAlreadyInUse:
            return "credential-already-in-use"
        case .missingOrInvalidNonce:
            return "invalid-nonce"
        default:
            return "unknown"
        }
    }
}

extension AppleFirebaseAuthBridge: ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        applePresentationAnchor ?? ASPresentationAnchor()
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard let rawNonce = appleRawNonce else {
            finishAppleSignIn(errorCode: "invalid-nonce")
            return
        }
        guard let appleCredential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let identityToken = appleCredential.identityToken,
              let idToken = String(data: identityToken, encoding: .utf8),
              !idToken.isEmpty else {
            finishAppleSignIn(errorCode: "invalid-credentials")
            return
        }

        let credential = OAuthProvider.appleCredential(
            withIDToken: idToken,
            rawNonce: rawNonce,
            fullName: appleCredential.fullName
        )
        Auth.auth().signIn(with: credential) { _, error in
            self.finishAppleSignIn(errorCode: Self.stableErrorCode(error))
        }
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        let nsError = error as NSError
        let errorCode: String
        if nsError.domain == ASAuthorizationError.errorDomain,
           nsError.code == ASAuthorizationError.canceled.rawValue {
            errorCode = "cancelled"
        } else if Self.containsNetworkError(nsError) {
            errorCode = "network"
        } else if nsError.domain == ASAuthorizationError.errorDomain {
            errorCode = "provider-unavailable"
        } else {
            errorCode = "unknown"
        }
        finishAppleSignIn(errorCode: errorCode)
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
