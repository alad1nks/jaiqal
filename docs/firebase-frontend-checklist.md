# Firebase frontend setup checklist

Complete this checklist outside the repository before validating Android/iOS authentication or Crashlytics against a real Firebase project.

## Setup status — 2026-10-22

The Firebase project `jaiqal` (project number `219975452692`) was created on the
Spark plan with Google Analytics and Gemini disabled during project creation.

| Item | Verified state |
| --- | --- |
| Android app | Registered as `com.alad1nks.jaiqal`; app ID `1:219975452692:android:a1144b45daf70abcae3f01`. |
| iOS app | Registered as `com.alad1nks.jaiqal.Jaiqal`, matching the current empty `TEAM_ID`. Recheck the effective bundle ID before configuring signing. |
| Authentication | Email/Password and Google enabled; public project name `Jaiqal`. |
| Android debug signing | SHA-1 and SHA-256 from this workstation's existing debug certificate registered. Release and Play App Signing fingerprints still required. |
| Authorized domains | Console lists `localhost`, `jaiqal.firebaseapp.com`, and `jaiqal.web.app`. |
| Apple sign-in | Deferred by the owner because Apple Developer Program membership is unavailable. |
| Client configuration | Android download validated for `jaiqal`, package and Google Web OAuth client; saved as `FIREBASE_ANDROID_CONFIG_JSON` in GitHub Environment `firebase-mobile`. `FIREBASE_PROJECT_ID=jaiqal` is set there; only branch `main` is allowed. iOS config remains pending. |
| Backend | Planned Ubuntu VPS at Hetzner; VPS has not been created. Server credentials and deployment are deferred. Local ignored `.env` sets `FIREBASE_PROJECT_ID=jaiqal`. |
| Acceptance | Real-device authentication, backend session/revocation checks, Crashlytics events/symbols, and the first configured CI run remain outstanding. |

The initial Gradle configuration blocker came from Ktor's open-ended
`commons-lang3` range selecting unreviewed `3.21.0`. The buildscript now pins the
already reviewed `3.20.0`. Windows Node.js 24.10.0 and 25.0.0 archives also have
verification entries, each matched against its official `SHASUMS256.txt`.
Dependency verification remains strict. Android SDK is present; the local Docker
daemon is not running, and iOS simulator tests require macOS.

This status is partial setup evidence, not production or end-to-end acceptance.

For the detailed owner/CI sequence, production-provider rollout and physical-device
acceptance, also follow [`google-apple-auth-human-setup.md`](google-apple-auth-human-setup.md).

## Firebase Console

- [ ] Create or select the Firebase project used by the backend's `FIREBASE_PROJECT_ID`.
- [ ] Add an Android application with package name `com.alad1nks.jaiqal`.
- [ ] Add an iOS application with bundle ID `com.alad1nks.jaiqal.Jaiqal` (or the exact bundle ID selected through `TEAM_ID` in the Xcode configuration).
- [ ] Download `google-services.json` and place it at `app/androidApp/google-services.json`.
- [ ] Download `GoogleService-Info.plist`, add it to the iOS application target, and place it at `app/iosApp/iosApp/GoogleService-Info.plist`.
- [ ] Enable Email/Password in Firebase Authentication.
- [ ] Enable Google in Firebase Authentication and select/configure its support email.
- [ ] Add the debug and release Android SHA-1/SHA-256 fingerprints, then download the updated `google-services.json`; verify that it generates a non-empty `default_web_client_id`.
- [ ] Copy `REVERSED_CLIENT_ID` from `GoogleService-Info.plist` into an iOS target URL Type so Google can return through `GIDSignIn.sharedInstance.handle(url)`.
- [ ] Enable Apple in Firebase Authentication and enter the Apple Services ID, Team ID, Key ID and private key created for this environment.
- [ ] Register Firebase's Apple OAuth return URL `https://<firebase-project-id>.firebaseapp.com/__/auth/handler` for the Services ID and verify that the configured Firebase project ID is exact.
- [ ] Keep Google OAuth secrets and the Apple private key in the provider/CI secret stores; do not place either in Kotlin, Swift resources, Gradle properties, xcconfig files, or repository history.
- [ ] If FCM is implemented after a backend push-token API exists, upload/configure the APNs authentication key for the iOS application.

## Apple Developer and Xcode

- [ ] Select the correct development team and verify that the resulting bundle ID matches the Firebase iOS application.
- [ ] Enable Sign in with Apple for the matching App ID in Apple Developer and add the Sign in with Apple capability to the iOS target.
- [ ] Confirm the provisioning profile contains the Apple sign-in entitlement and test on a real signed device/archive, not only an unsigned simulator build.
- [ ] Add Push Notifications and the required background mode only when FCM/APNs support is implemented.
- [ ] Build a configured Release archive and confirm that its dSYM upload phase completes.

## CI and secrets

### Android configuration check

The manual workflow `.github/workflows/firebase-android.yml` builds and lints the
Firebase-configured Android debug variant. It runs only on `main`, uses the
`firebase-mobile` GitHub Environment, disables build caching, publishes no build
artifacts, and removes the temporary config and Android outputs with `always()`.
Existing pull-request CI does not receive Firebase secrets.

Before running it, create the Environment with deployment branches restricted to
`main` and configure:

| Type | Name | Value |
| --- | --- | --- |
| Environment variable | `FIREBASE_PROJECT_ID` | `jaiqal` |
| Environment secret | `FIREBASE_ANDROID_CONFIG_JSON` | Complete updated `google-services.json` contents, plain JSON (not Base64). |

The validator rejects missing/invalid JSON, a different project or Android
package, missing Google Web OAuth client configuration, and Admin credentials.
It does not print configuration values. See [GitHub's secrets documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
for adding Environment secrets.

This workflow checks compilation, not device sign-in or a production release.
Its temporary runner debug signing key is not the workstation key registered in
Firebase. No installable artifact is published. A distribution pipeline still
needs managed signing and matching Firebase release/Play fingerprints.

iOS configuration remains pending: keep `GoogleService-Info.plist` in the CI file
secret store when an iOS job is added. A signed archive, callback URL scheme,
Apple team and dSYM verification require a separate macOS/signing setup. Never
put Firebase Admin JSON or an Apple private key in the mobile config secret.

The Android secret, project variable and `main` branch restriction are configured
in [the hosted Environment](https://github.com/alad1nks/jaiqal/settings/environments/24959205393/edit).
Running the workflow requires the reviewed changes to be merged into `main`;
the Environment restriction deliberately prevents running it from a PR branch.

- [ ] Store the two platform configuration files in the CI secret/file store according to the repository policy and materialize them only for the relevant build job.
- [ ] Keep release backend URLs and signing configuration in CI/deployment configuration rather than Kotlin source.
- [ ] Verify that CI logs and artifacts do not expose Firebase ID Tokens, passwords, claim codes, signing keys, or device credentials.
- [ ] Do not commit either platform Firebase configuration file; both paths are listed in `.gitignore`.
- [ ] Do not add a Firebase Admin service-account JSON to frontend resources, mobile build configuration, CI artifacts, or this repository.

## Credential boundary

`google-services.json` and `GoogleService-Info.plist` identify public client applications and are not backend service-account credentials. They contain no Firebase Admin private key, but this repository still treats them as environment-specific ignored configuration.

A Firebase Admin service-account JSON must exist only in the server runtime/secret manager when workload identity is unavailable. The frontend must never contain a Firebase Admin private key or use the Admin SDK.

## Post-setup verification

- [ ] Register a new test account through the client and receive the verification email.
- [ ] Verify the email, restart the application, and confirm Firebase restores the session.
- [ ] Confirm an unverified Email/Password user cannot enter protected application screens, while the same user can proceed after verification and reload.
- [ ] Sign in with Google on Android and iOS, then confirm the client reaches `GET /api/v1/auth/me` and receives an internal backend user ID.
- [ ] Sign in with Apple on Android and iOS; verify first-sign-in name handling and repeat sign-in when Apple no longer returns the name or public email.
- [ ] Verify an Apple Private Relay address and a Firebase user without an email both synchronize without changing the internal UUID.
- [ ] Cancel each Google/Apple system dialog and confirm cancellation is not shown as a critical authentication failure.
- [ ] Attempt a provider collision for an existing email and confirm the client asks for the previously used method without automatically linking or merging accounts.
- [ ] Confirm repeated protected requests reuse the same internal user and that one backend `401` causes exactly one forced Firebase ID Token refresh and retry.
- [ ] Confirm logout ends Firebase authentication, clears Android Credential Manager state, and removes only the current account's local cache.
- [ ] Delete an Email/Password, Google and Apple test account through Settings after recent reauthentication; confirm owned backend data is removed and retrying the idempotent request is safe.
- [ ] For Apple deletion on iOS, confirm a fresh authorization code is obtained and Firebase token revocation succeeds before the Firebase user is deleted.
- [ ] Confirm a deleted/tombstoned Firebase identity cannot be silently auto-provisioned again and that no ID/access token, nonce, authorization code, email or Firebase UID appears in logs.
- [ ] Confirm the plant deep link `jaiqal://plants/{plantId}` opens the expected screen for an authenticated owner.
- [ ] Produce one controlled non-fatal/test crash in a release test build on each configured platform and confirm symbolicated events in Crashlytics.
