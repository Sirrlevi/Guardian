# Security, setup and Android limitations

## Firebase setup

1. Create a Firebase project and enable Authentication → Email/Password.
2. Create a Realtime Database and copy its HTTPS database URL.
3. Add an app in Firebase to obtain the Web API key used by the Identity Toolkit REST API.
4. Import firebase/database.rules.json into Realtime Database → Rules and publish them.
5. Add GitHub repository Actions secrets FIREBASE_API_KEY and FIREBASE_DATABASE_URL.
6. Re-run the Android workflow so the Firebase settings are compiled into both APKs.

Each Parent and Child installation uses its own Firebase email/password account. The parent creates a time-limited pairing code; the child signs in with a separate child account and enters that code. The parent account owns the family record.

Firebase API keys identify the project; they are not a substitute for database rules. Never remove the supplied rules or make the database globally readable/writable.

### What the database rules enforce

`firebase/database.rules.json` follows least privilege. Everything is denied unless a rule grants it:

- **Parent** (uid = family id): reads and writes its own family, nothing else.
- **Paired phone**: writes only the paths the Child app really writes (usage, info, sessions, communication previews, media upload, capture and control answers, wallpaper, recovery points, its own registration fields) and reads only its own data and the family policy. It may *clear* a command it has handled but never create or change one, so a command cannot be forged into its own inbox. A request for more time must stay "pending" when the phone writes it; only the parent can approve or deny it.
- **Parent-only data**: Safety alerts (settings, dismissals, events, permission snapshots), media and recovery settings, "deleted media" markers, the policy and the command inbox. A phone can neither read nor change its own Safety data.
- **Other phones and other families**: a phone cannot read or write another phone's data, the family record or the member list. A removed member (member record deleted by the parent) loses all access at once.
- **Joining a family**: the member record must carry the invitation code the parent created, and that code must belong to that family and still be valid. Invitations can only be created, replaced or withdrawn by their owner, last at most one hour (the app uses 15 minutes) and cannot be listed.

`DatabaseRulesTest` runs the real rules file through a small evaluator for these cases. It is not Firebase itself, so after publishing the rules, try a few requests in the Firebase console Rules Playground once.

Compatibility: the rules need the current Child app (it sends the invitation code with its member record and registers itself with a patch). A phone paired earlier keeps working. Pairing with an older Child app fails until it is updated. Publishing the rules is a manual step (Realtime Database → Rules); CI does not deploy them.

Residual risks: the 8-digit invitation code can be guessed in theory by a signed-in user for as long as it lives; Firebase rules cannot rate-limit. A compromised child phone can still send false data about itself, because nothing can prove its data is honest.

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

## In-app update system

The Parent and Child apps each include an App Updates section. They query the private repository's latest GitHub Release when the app opens, when the user taps Check for updates, and periodically in the background (approximately every 6 hours, subject to Android's WorkManager scheduling). New releases trigger a notification once per version when notifications are permitted. The updater downloads only the matching app asset and verifies its reported size and ZIP/APK signature bytes before opening Android's system package installer.

### Private-repository access

Create a GitHub fine-grained personal access token for resource owner `Sirrlevi`, select only the `Guardian` repository, and grant **Contents: Read-only** repository permission. Enter it in Settings → App Updates on each phone. The token is encrypted locally with Android Keystore; it is not embedded in the APK or in the source tree. Use an expiry date, keep the token private, and revoke/replace it if a phone is lost. A token stored on a device is still a credential and the device should be protected.

### Persistent update signing

Android requires an APK update to be signed with the same certificate as the installed package. Generate one keystore before the first installation:

```bash
keytool -genkeypair -v -keystore guardian-release.jks -alias guardian-release -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 guardian-release.jks > guardian-release.base64
```

Add the following repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: contents of `guardian-release.base64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`: `guardian-release`
- `ANDROID_KEY_PASSWORD`

Keep a secure backup of the keystore and passwords. Never rotate the signing key for an app already distributed to devices. The workflow builds debug APKs for CI, but publishes a new GitHub Release only when both release APKs have been signed with the persistent keystore. Consequently, a push whose tests fail—or a successful build without signing secrets—does not create an update for the apps.

### Update limitations and preserved data

- Android still displays its package-installer confirmation. Guardian cannot silently install updates on ordinary devices.
- The first installation must come from the persistent-key signed release. Previously installed debug APKs signed by a different runner key must be uninstalled first; do this before account creation/pairing because uninstalling clears local data.
- A normal in-place update preserves app-private SharedPreferences and Android Keystore-backed session/token data. Uninstalling the app or clearing its storage does not.
- Background update checks are best-effort, not instant push delivery. Android can delay periodic work because of battery and network policies. Manual Check for updates is available at any time.

## Telegram reports (optional)

Off until the parent saves a bot token and chat, tests the connection and switches reports on (Parent app → More → Telegram reports).

- Guardian has no server, so the **Parent app sends the reports itself, only while it is open**. Reports that could not be sent wait and go out the next time the app is open. Nothing is sent in the background.
- The bot token and chat ID are stored encrypted with an AES-GCM key held in the Android Keystore of the parent's phone. They are not in the source, the APK, GitHub, logs or plain files, and the token is never shown again after saving. Signing out removes them.
- What the Keystore does **not** protect against: someone who unlocks the parent's phone and uses Guardian, a rooted phone, and Telegram itself (bot chats are not end-to-end encrypted). Use a bot only you control and a chat only you can read.
- Requests go only to `api.telegram.org` over HTTPS (other hosts, ports and redirects are refused). Messages are plain text.
- A report counts as **sent** only when Telegram answers with a message id. Anything else is shown as waiting, will try again, not sent, or **not confirmed** (the connection broke after sending, or the app stopped mid-send). Not-confirmed reports are never re-sent automatically, so they cannot be duplicated; the parent may choose to send them again.
- Each report has a unique key, so the same alert is never queued twice. Sending is rate-limited (at most 10 per pass, 1.5 s apart, 15 per minute), failed sends back off (1, 5, 15, 60, 360 min, 6 tries), and Telegram's "slow down" is obeyed. History is kept for 1, 7, 30 or 90 days (default 30).
- Only what happens after reports are switched on is sent. Switching off discards what was waiting; clearing history is never followed by a re-send of what was cleared.
- What is sent: the Safety alerts you see (label, title, reason; these can name an app, a sender, a link or a flagged word), phone status changes, and an optional daily screen-time total. Never the full text of messages, photos, videos, screenshots or Shared media. Automatic media backup to Telegram is deliberately not offered.
- Reports and their delivery state are kept in the Parent app's private no-backup storage until their retention ends.

## Safety alerts

Alerts come from simple rules over message previews, app use and the phone's own reports. They never claim certainty, always state their reason, and the parent can dismiss or restore them. They can be wrong in both directions and are not evidence of anything. They are only evaluated while the Parent app is open.

## Transparency

This project is designed for disclosed parental controls on an authorized, supervised child device. It shows the VPN indicator and persistent filtering notification, requests Android permissions through system UI, and does not hide itself or bypass OS consent. Do not use it for covert monitoring or on a device without appropriate authority and disclosure.
