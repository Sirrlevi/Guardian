# Security, setup and Android limitations

## Firebase setup

1. Create a Firebase project and enable Authentication → Email/Password.
2. Create a Realtime Database and copy its HTTPS database URL.
3. Add an app in Firebase to obtain the Web API key used by the Identity Toolkit REST API.
4. Import firebase/database.rules.json into Realtime Database → Rules and publish them.
5. Add GitHub repository Actions secrets FIREBASE_API_KEY and FIREBASE_DATABASE_URL.
6. Re-run the Android workflow so the Firebase settings are compiled into both APKs.

Each Parent and Child installation uses its own Firebase email/password account. The parent creates a time-limited pairing code; the child signs in with a separate child account and enters that code. The rules permit the paired child account to write its own device record; the parent account owns family policy.

Firebase API keys identify the project; they are not a substitute for database rules. Never remove the supplied rules or make the database globally readable/writable.

## Android enrollment and permissions

- The child app must be installed openly on the child's device.
- Usage reporting requires the user to grant Usage access in Android Settings.
- Domain filtering requires the child/guardian to approve Android's VPN prompt. Android will show the system VPN indicator. The local DNS filter handles IPv4 DNS over UDP and blocks configured domains; DNS-over-HTTPS, DNS-over-TLS, hard-coded IP connections, IPv6 DNS and apps with their own resolver can bypass it.
- Full URL and page-duration logging is available only inside Guardian Browser. Other browsers do not expose their complete HTTPS URL paths/history to ordinary third-party apps.
- App suspension and app time limits require Guardian Child to be provisioned as Device Owner on a fully managed device. This normally requires a fresh/unprovisioned device. A normal app install or the Device Admin screen does not grant Device Owner.
- Device Owner provisioning example for a test device after setup/wipe: adb shell dpm set-device-owner com.sirrlevi.guardian.child/.GuardianAdminReceiver. Do not run this on a device you are not authorized to manage. It can require removing accounts and resetting a device.
- Android may restrict background execution. The app uses WorkManager for periodic policy sync; it is not a real-time guarantee.

## Build and signing

The GitHub Actions workflow always runs unit tests and builds both debug APKs. If the four Android signing secrets are present (ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD), it also builds signed release APKs. The keystore is decoded only on the runner and is never committed. Without those secrets, the published APKs are debug-signed, so their signing identity is not guaranteed to be stable across hosted runners. Add your own persistent keystore secrets before distributing updates to devices.

Build versions use the GitHub Actions run number. Each successful push creates a latest GitHub Release and uploads both Parent and Child APKs.

## Transparency

This project is designed for disclosed parental controls on an authorized, supervised child device. It shows the VPN indicator and persistent filtering notification, requests Android permissions through system UI, and does not hide itself or bypass OS consent. Do not use it for covert monitoring or on a device without appropriate authority and disclosure.
