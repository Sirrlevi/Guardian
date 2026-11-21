# Guardian

Guardian is an Android parental-control project with two separate applications:

- **Guardian Parent** — family account, child-device pairing, usage reports and policy editor.
- **Guardian Child** — visible child-device companion with usage reporting, a managed browser, approved DNS filtering and Device Owner app controls.
- **core** — Firebase Authentication / Realtime Database REST client and URL/domain matching rules.

## Current implementation

- Parent UI: Overview, Apps, Web, Controls, Reports and Settings tabs, with app cards, usage bars, recent activity and app-policy controls.
- Child UI: Protection, Browser and Settings tabs with transparent permission status and in-app updates.
- Recent foreground events are collected from Android Usage Access and displayed in the parent activity feed; this is sampled usage data, not a live screen stream.

- Parent and child use separate Firebase accounts; an expiring 8-digit invite pairs the child account with the family.
- Child session tokens are encrypted with an Android Keystore AES/GCM key.
- Usage Access reporting sends app name, package, foreground duration and last-used timestamp.
- Parent policies include blocked domains, URL/path patterns for Guardian Browser, blocked package names and daily app limits.
- Guardian Browser records full URL, title, start/end time, approximate dwell time and blocked attempts.
- The child VPN shows Android's normal VPN indicator and a persistent notification. It filters configured IPv4 DNS-over-UDP queries.
- App suspension is implemented with DevicePolicyManager and is deliberately gated on actual Device Owner status.
- WorkManager performs policy and usage sync about every 15 minutes when Android permits background work.

## Quick setup

Read docs/SECURITY_AND_LIMITATIONS.md before enrolling a device.

1. Configure Firebase Authentication (Email/Password), Realtime Database and the supplied firebase/database.rules.json.
2. Add Actions secrets FIREBASE_API_KEY and FIREBASE_DATABASE_URL, then push or run the Android workflow.
3. Download both APKs from the latest GitHub Release.
4. Install Guardian Parent on the parent phone and create a parent account.
5. Generate an 8-digit pairing code in Guardian Parent.
6. Install Guardian Child on the supervised device, create a separate child account, enter the code and pair.
7. On the child device, grant Usage Access and separately approve the VPN prompt if domain filtering is required.

## Parent policy syntax

Each entry is on its own line.

- Block domains: example.com (also matches subdomains).
- Block a specific URL path in Guardian Browser: https://example.com/private/*.
- Block an installed app: Android package name, such as com.example.app.
- Daily app limit: com.example.app=45 (minutes per day).

## Device Owner and app suspension

Strong app suspension and time limits require the child app to be Device Owner. A normal installation cannot grant that status. On a test device you own and have reset/provisioned for management, the documented command is:

adb shell dpm set-device-owner com.sirrlevi.guardian.child/.GuardianAdminReceiver

Do not use this on a phone you are not authorized to manage. Device Owner provisioning is a device-management operation and may require a fresh setup.

## Build

GitHub Actions uses Java 17 and Gradle 8.9. It runs core and Android unit-test tasks, builds both debug APKs, uploads them as an artifact, and creates a latest GitHub Release.

For stable release signing, configure these repository Actions secrets:

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

The keystore is decoded only on the runner and is ignored by Git. Without these secrets the workflow publishes debug APKs for testing; debug signing is not a durable update identity across hosted runners.

## In-app updates (Parent + Child)

Both apps include **Settings → Check for updates**:

- Save one GitHub fine-grained personal access token on each phone.
- Check manually, on app launch, and periodically in the background (WorkManager, about every 15 minutes when Android permits (the OS may delay runs)).
- A notification is posted once per newly detected signed release. Tapping it opens Settings → Check for updates directly; download (with a progress bar) and install are available there.
- The updater downloads only the APK matching the installed app (Parent or Child) from the latest GitHub Release and checks the asset size/archive before handing it to Android's package installer.
- Android asks the user to allow installs from Guardian and confirm the update. Silent installation is not available to an ordinary app.
- App data stays in place for an in-place update when application ID and signing certificate match. Do not uninstall or clear app storage.
- Android stops services and cancels background jobs when it replaces an app. Both apps handle the package-replaced broadcast to re-arm update checks, and the child also re-arms usage sync and (if it was running and VPN consent still exists) the DNS filter.

### GitHub updater token

Create a fine-grained personal access token for resource owner `Sirrlevi`, restrict it to **only** the `Guardian` repository, and grant **Contents: Read-only** repository permission. Paste the token into Settings → Check for updates on both phones. It is encrypted locally using Android Keystore. Do not commit it, put it in source code, or add it as an APK build constant. Use an expiry date and rotate/revoke it if a phone is lost.

### Mandatory persistent Android signing

An APK update can replace an installed app only when it has the same signing key. Before installing the first Guardian release, create one long-lived keystore and keep a secure backup:

```bash
keytool -genkeypair -v -keystore guardian-release.jks -alias guardian-release -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 guardian-release.jks > guardian-release.base64
```

Add these repository Actions secrets (Settings → Secrets and variables → Actions):

- `ANDROID_KEYSTORE_BASE64`: the full single-line contents of `guardian-release.base64`
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: `guardian-release`
- `ANDROID_KEY_PASSWORD`: key password

Never regenerate or lose this keystore after distributing the signed APKs. If it changes, Android will reject future in-place updates. The workflow may still build debug APK artifacts without the signing secrets, but it will **not publish debug APKs as updates**. A new latest Release is published only after CI passes and both APKs are signed with the persistent key.

If an older debug-signed Guardian APK was already installed, it cannot be updated to this signing key in place. Uninstall it and install the first signed release before creating accounts or pairing devices.

## Important platform boundaries

- Guardian Browser is the only place where full HTTPS URL paths and page duration can be recorded reliably.
- Other browsers do not provide third-party apps with unrestricted full browsing history. The VPN can filter DNS domains, not encrypted URL paths.
- DNS-over-HTTPS/TLS, IPv6 DNS, hard-coded IP addresses and app-specific DNS can bypass this prototype DNS filter.
- Usage Access is user-granted and its event timing is not a guaranteed real-time stream.
- Periodic WorkManager sync is best-effort, not instant remote control.
- Android's screen-capture consent cannot be bypassed; this project does not implement covert screen viewing.

This is an actively evolving starter implementation, not a certified MDM or a substitute for Android Enterprise provisioning and a production security review.
