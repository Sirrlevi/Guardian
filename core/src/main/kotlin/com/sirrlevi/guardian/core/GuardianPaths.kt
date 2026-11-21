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
    fun daily(familyId: String, deviceId: String) = device(familyId, deviceId) + "/daily"
    fun hourly(familyId: String, deviceId: String, dayKey: String) = device(familyId, deviceId) + "/hourly/$dayKey"
}
