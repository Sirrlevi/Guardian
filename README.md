# Guardian

Guardian is an Android parental-control project with two separate applications:

- **Guardian Parent** — family account, child-device pairing, usage reports and policy editor.
- **Guardian Child** — visible child-device companion with usage reporting, a managed browser, approved DNS filtering and Device Owner app controls.
- **core** — Firebase Authentication / Realtime Database REST client and URL/domain matching rules.

## Current implementation

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

## Important platform boundaries

- Guardian Browser is the only place where full HTTPS URL paths and page duration can be recorded reliably.
- Other browsers do not provide third-party apps with unrestricted full browsing history. The VPN can filter DNS domains, not encrypted URL paths.
- DNS-over-HTTPS/TLS, IPv6 DNS, hard-coded IP addresses and app-specific DNS can bypass this prototype DNS filter.
- Usage Access is user-granted and its event timing is not a guaranteed real-time stream.
- Periodic WorkManager sync is best-effort, not instant remote control.
- Android's screen-capture consent cannot be bypassed; this project does not implement covert screen viewing.

This is an actively evolving starter implementation, not a certified MDM or a substitute for Android Enterprise provisioning and a production security review.
