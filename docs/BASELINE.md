# Guardian baseline report (taken before the Child Safety upgrade)

Repository state: commit `b2f0d90` on `main`. This file is the reference for "what must keep working".
Everything below was read from the code, not assumed.

## 1. Existing working features

**Parent app** (`com.sirrlevi.guardian.parent`)
- Email/password sign-in (Firebase Identity Toolkit REST), session kept in Android Keystore-backed storage (`SessionVault`).
- Pairing code generator (8 digits, 15 min), device list, per-child dashboard (Home, Apps, Activity, Reports, Settings tabs).
- Per-app rules: block, daily limit, per-weekday limits, today-only extra/less time (-/+ buttons), pause all apps, schedules (school/bed time), website/domain/URL block lists.
- Live "current app", ack banner ("child phone confirmed"), requests for more time with Approve/Deny.
- Charts: hourly, 7/30 day trends, sessions timeline, rankings.
- In-app updater (GitHub Releases, signer pre-check, optional token), update notifications, light/dark/system theme, animated brand intro.

**Child app** (`com.sirrlevi.guardian.child`)
- Pairing with the parent's code (own Firebase account), always-visible foreground monitor with permanent notification.
- Per-app foreground time (UsageStatsManager events -> `UsageSessionTracker`), overlay "time's up" screen, Device Owner app suspension when provisioned.
- DNS-based website filter (VPN, visible key icon), Guardian Browser (full URL history only inside it).
- Streams `rt/live`, `rt/ack`, `info`, `catalog`, `icons`, `sessions/<day>`, `hourly/<day>`, `daily/<day>`, `requests`, `browserHistory` to the parent.
- In-app updater, WorkManager periodic sync (`ChildSyncWorker`), boot/package-replaced receivers.

**Shared `:core`**: `GuardianApi` (REST + SSE), `GuardianPaths`, `PolicyModel` (rules, codec, today-adjustment), `Enforcement`, `UsageSessionTracker`, `DomainRules`, `UpdateRules`, `AppCategories`, `Durations`.

## 2. Architecture

- Three Gradle modules: `:core` (pure JVM), `:parent`, `:child` (Compose + Material 3, Kotlin 2.0.21, AGP 8.7.3, compile/target SDK 35, min 26).
- No Firebase SDK. All traffic is Realtime Database REST (`GuardianApi`), live updates via Server-Sent-Events streams.
- Identity: `familyId` = parent account uid. Each Child signs in with its own account; `deviceId` = child account uid. Pairing codes live in `pairingCodes/<code>`.
- Parent -> Child direction: parent writes `families/<fid>/policies` (+ `updatedAt`); the Child streams it and acks in `rt/ack`.
- Child -> Parent direction: child writes only under `families/<fid>/devices/<deviceId>/...` through an in-memory `Outbox` (latest-wins and ordered items).
- Parent keeps a small JSON cache (`ParentRepository.saveCache`, `SCHEMA = 2`). Gson restores it **without running Kotlin constructors**, so any new non-null field on a cached model must bump `SCHEMA` (the cache is simply refetched).

## 3. Existing permissions

Parent: `INTERNET`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`.

Child: `INTERNET`, `ACCESS_NETWORK_STATE`, `PACKAGE_USAGE_STATS` (user-granted), `QUERY_ALL_PACKAGES`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; VPN consent via `VpnService.prepare`; Device Admin (`force-lock` only); optional Device Owner (adb provisioning).

## 4. Firebase structure (Realtime Database)

```
pairingCodes/<code>            { familyId, createdAt, expiresAt }
families/<fid>/
  policies                     { appRules/<pkg,encoded>, blockedPackages, blockedDomains, blockedUrls,
                                 schedules[], pauseUntil, updatedAt }
  members/<uid>                child membership
  devices/<deviceId>/
    info, status, rt/{live,ack}, catalog, icons
    sessions/<day>/<startMs>   hourly/<day>   daily/<day>
    requests/<ts>              browserHistory   usage/*  apps/installed  recentActivities
```

Rules (`firebase/database.rules.json`): everything denied by default; a family node is readable by the parent or its members; each `devices/<deviceId>` subtree is readable and writable by the parent **or the device itself**. There are no finer per-path rules yet (a paired child account could write any path under its own device node, and the parent could write any path there too).

## 5. Services, workers, receivers

- `GuardianMonitorService` (foreground, `specialUse`): 1 s loop, SSE policy stream, outbox uploader, overlay, Device Owner enforcement, periodic info/aggregates.
- `DomainFilterVpnService` (foreground, `specialUse`, `BIND_VPN_SERVICE`).
- `ChildSyncWorker` (periodic WorkManager): policy refresh, usage snapshots, catalog sync, restarts the monitor.
- `GuardianUpdateCheckWorker` (both apps, ~6 h), `GuardianBootReceiver`, `GuardianPackageReplacedReceiver` (both), `GuardianAdminReceiver`.

## 6. Existing tests (all must stay green)

- `:core` unit tests: DomainRules, DurationsAndSse, Enforcement, PolicyModel, UpdateRules, UsageSessionTracker.
- Robolectric UI smoke tests: `ParentUiSmokeTest` (every tab, per-app sheet, adjust buttons, intro, logo, sign-in), `ChildUiSmokeTest` (pairing + every paired screen).
- CI (`.github/workflows/android.yml`): tests, lint, debug + signed release builds, signer verification, release publishing.

## 7. Known limitations that already apply

- Usage data is per app, never per conversation. Websites outside Guardian Browser are visible only as blocked DNS names.
- Hard enforcement (suspension) needs Device Owner; otherwise an overlay is used and a determined child can work around it.
- Background work is best effort (Doze, OEM battery managers). WorkManager is not real time.
- Realtime Database is the only backend: there is **no object storage and no server of our own**.

## 8. Regression risks for the upgrade

| Risk | Mitigation |
|---|---|
| `GuardianMonitorService` is one large class; adding features there could slow the 1 s loop | New features run in their own services/workers and only reuse `ChildCloud` |
| Parent cache restores models through Gson without constructors | New model fields get defaults and `SCHEMA` is bumped when cached models change |
| Existing rules give the device and the parent write access to the whole device node | Add per-path rules for new nodes (`comms`, `cmd`, `cmdResult`, `alerts`, ...) without narrowing paths the shipped apps already use |
| Adding a new foreground-service type or permission changes the first-run flow | Every new permission is optional and explained; existing required set (`usage`, `overlay`) is unchanged |
| Two outbox/listener implementations would race | Reuse `ChildCloud` for auth, one new listener only for the command node |
| Release pipeline / signing | Untouched. Version still comes from the run number |
