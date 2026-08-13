# Firebase frontend setup checklist

Complete this checklist outside the repository before validating Android/iOS authentication or Crashlytics against a real Firebase project.

## Firebase Console

- [ ] Create or select the Firebase project used by the backend's `FIREBASE_PROJECT_ID`.
- [ ] Add an Android application with package name `com.alad1nks.jaiqal`.
- [ ] Add an iOS application with bundle ID `com.alad1nks.jaiqal.Jaiqal` (or the exact bundle ID selected through `TEAM_ID` in the Xcode configuration).
- [ ] Download `google-services.json` and place it at `app/androidApp/google-services.json`.
- [ ] Download `GoogleService-Info.plist`, add it to the iOS application target, and place it at `app/iosApp/iosApp/GoogleService-Info.plist`.
- [ ] Enable Email/Password in Firebase Authentication.
- [ ] If Google Sign-In is later approved, enable its Firebase provider, configure Android SHA-1/SHA-256 fingerprints, and add the generated iOS URL scheme.
- [ ] If Sign in with Apple is later approved, enable its Firebase provider and configure the Apple service ID, key, team ID, redirect URL, and Xcode capability.
- [ ] If FCM is implemented after a backend push-token API exists, upload/configure the APNs authentication key for the iOS application.

## Apple Developer and Xcode

- [ ] Select the correct development team and verify that the resulting bundle ID matches the Firebase iOS application.
- [ ] Add Sign in with Apple capability only when that provider is implemented.
- [ ] Add Push Notifications and the required background mode only when FCM/APNs support is implemented.
- [ ] Build a configured Release archive and confirm that its dSYM upload phase completes.

## CI and secrets

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
- [ ] Confirm the client reaches `GET /api/v1/auth/me` and receives an internal backend user ID.
- [ ] Confirm logout clears the current account's local cache.
- [ ] Confirm the plant deep link `jaiqal://plants/{plantId}` opens the expected screen for an authenticated owner.
- [ ] Produce one controlled non-fatal/test crash in a release test build on each configured platform and confirm symbolicated events in Crashlytics.
