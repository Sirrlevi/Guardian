package com.sirrlevi.guardian.core

/** Every Realtime Database path used by Parent and Child, so the two apps can never drift apart. */
object GuardianPaths {
    fun policy(familyId: String) = "families/$familyId/policies"
    fun device(familyId: String, deviceId: String) = "families/$familyId/devices/$deviceId"
    fun devices(familyId: String) = "families/$familyId/devices"
    /** Small node holding `live` (current app) and `ack` (what the child confirmed applying). Streamed by the parent. */
    fun realtime(familyId: String, deviceId: String) = device(familyId, deviceId) + "/rt"
    fun info(familyId: String, deviceId: String) = device(familyId, deviceId) + "/info"
    fun catalog(familyId: String, deviceId: String) = device(familyId, deviceId) + "/catalog"
    fun icons(familyId: String, deviceId: String) = device(familyId, deviceId) + "/icons"
    fun sessions(familyId: String, deviceId: String, dayKey: String) = device(familyId, deviceId) + "/sessions/$dayKey"
    /** Screen on/off and lock/unlock moments for one day, keyed by `<timestamp>_<kind>`. */
    fun screen(familyId: String, deviceId: String, dayKey: String) = device(familyId, deviceId) + "/screen/$dayKey"
    fun daily(familyId: String, deviceId: String) = device(familyId, deviceId) + "/daily"
    fun hourly(familyId: String, deviceId: String, dayKey: String) = device(familyId, deviceId) + "/hourly/$dayKey"
    /** Notification previews captured on the child, one node per day (`yyyyMMdd`) holding records keyed by their stable id. */
    fun comms(familyId: String, deviceId: String) = device(familyId, deviceId) + "/comms"
    fun commsDay(familyId: String, deviceId: String, dayKey: String) = comms(familyId, deviceId) + "/" + dayKey
    /** Parent-written backup settings for one child phone. */
    fun mediaSettings(familyId: String, deviceId: String) = device(familyId, deviceId) + "/settings/media"
    fun mediaStatus(familyId: String, deviceId: String) = device(familyId, deviceId) + "/media/status"
    fun mediaItems(familyId: String, deviceId: String) = device(familyId, deviceId) + "/media/items"
    fun mediaItem(familyId: String, deviceId: String, key: String) = mediaItems(familyId, deviceId) + "/" + key
    fun mediaBlob(familyId: String, deviceId: String, key: String) = device(familyId, deviceId) + "/media/blobs/" + key
    /** Keys the parent deleted; the child never backs these up again. */
    fun mediaDeleted(familyId: String, deviceId: String) = device(familyId, deviceId) + "/media/deleted"

    // ---- approved screen sharing (screenshots and live preview)
    fun capture(familyId: String, deviceId: String) = device(familyId, deviceId) + "/capture"
    /** Written by the child: what the sharing session is doing right now. */
    fun captureState(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/state"
    /** Written by the parent: `cmd/<id>` requests and the `viewer` heartbeat. Read (and cleared) by the child. */
    fun captureInbox(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/inbox"
    fun captureCommand(familyId: String, deviceId: String, id: String) = captureInbox(familyId, deviceId) + "/cmd/" + id
    fun captureViewer(familyId: String, deviceId: String) = captureInbox(familyId, deviceId) + "/viewer"
    /** Written by the child: what became of each command. */
    fun captureAcks(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/acks"
    fun captureAck(familyId: String, deviceId: String, id: String) = captureAcks(familyId, deviceId) + "/" + id
    fun captureShots(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/shots"
    fun captureShot(familyId: String, deviceId: String, id: String) = captureShots(familyId, deviceId) + "/" + id
    fun captureShotBlob(familyId: String, deviceId: String, id: String) = capture(familyId, deviceId) + "/shotblobs/" + id
    /** Only the newest live frame is ever stored; it is overwritten, not accumulated. */
    fun captureLive(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/live"
    fun captureLiveBlob(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/liveblob"
    /** Short history of what happened (asked, approved, screenshot, ended …) so the parent and child can see it. */
    fun captureLog(familyId: String, deviceId: String) = capture(familyId, deviceId) + "/log"

    // ---- device controls (lock now, restart)
    fun control(familyId: String, deviceId: String) = device(familyId, deviceId) + "/control"
    /** Written by the child: what the phone can do and its lock state. */
    fun controlStatus(familyId: String, deviceId: String) = control(familyId, deviceId) + "/status"
    /** Written by the parent, one node per request id. Read (and cleared) by the child. */
    fun controlInbox(familyId: String, deviceId: String) = control(familyId, deviceId) + "/inbox"
    fun controlCommand(familyId: String, deviceId: String, id: String) = controlInbox(familyId, deviceId) + "/" + id
    /** Written by the child: what became of each request. */
    fun controlAcks(familyId: String, deviceId: String) = control(familyId, deviceId) + "/acks"
    fun controlAck(familyId: String, deviceId: String, id: String) = controlAcks(familyId, deviceId) + "/" + id
    /** The phone's short history of requests it handled. */
    fun controlLog(familyId: String, deviceId: String) = control(familyId, deviceId) + "/log"

    // ---- device appearance (the wallpaper the child chose to share)
    fun appearance(familyId: String, deviceId: String) = device(familyId, deviceId) + "/appearance"
    /** Written by the child: when and how big the shared wallpaper picture is. Written after the picture, so it is always complete. */
    fun wallpaper(familyId: String, deviceId: String) = appearance(familyId, deviceId) + "/wallpaper"
    fun wallpaperBlob(familyId: String, deviceId: String) = appearance(familyId, deviceId) + "/wallpaperblob"

    // ---- device recovery (location, ring)
    fun recovery(familyId: String, deviceId: String) = device(familyId, deviceId) + "/recovery"
    /** Written by the parent: whether location is shared, how often, and how long history is kept. */
    fun recoverySettings(familyId: String, deviceId: String) = recovery(familyId, deviceId) + "/settings"
    /** Written by the child: which location permission it has and when it last read a position. */
    fun recoveryState(familyId: String, deviceId: String) = recovery(familyId, deviceId) + "/state"
    /** Written by the child: the newest real position. */
    fun recoveryLast(familyId: String, deviceId: String) = recovery(familyId, deviceId) + "/last"
    fun recoveryHistory(familyId: String, deviceId: String) = recovery(familyId, deviceId) + "/history"
    fun recoveryPoint(familyId: String, deviceId: String, key: String) = recoveryHistory(familyId, deviceId) + "/" + key
}
