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
}
