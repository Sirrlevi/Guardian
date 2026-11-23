package com.sirrlevi.guardian.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Firebase Realtime Database keys may not contain '.', so package names are stored with ','
 * instead. ',' can never appear in an Android package name, so the mapping is lossless.
 */
object PkgKey {
    fun encode(packageName: String): String = packageName.replace('.', ',')
    fun decode(key: String): String = key.replace(',', '.')
}

/**
 * One parent decision about one app. A limit of 0 seconds means "no daily limit".
 *
 * [bonusSec] (stored under that name for compatibility) is a one-off adjustment of *today's* limit
 * that is only valid on the day named [bonusDay]: positive = extra time, negative = time taken away.
 * It never changes the normal limit and disappears by itself the next day.
 */
data class AppRule(
    val blocked: Boolean = false,
    val dailyLimitSec: Int = 0,
    /** Calendar.DAY_OF_WEEK (1 = Sunday … 7 = Saturday) → allowed seconds that day. 0 = not allowed that day. */
    val daySec: Map<Int, Int> = emptyMap(),
    val bonusDay: String = "",
    val bonusSec: Int = 0
) {
    val isEmpty: Boolean get() = !blocked && dailyLimitSec <= 0 && daySec.isEmpty() && bonusSec == 0

    /** The normal allowed seconds on [dayOfWeek] before any one-off adjustment, or null when the app is unlimited. */
    fun baseLimitFor(dayOfWeek: Int): Int? {
        val base = daySec[dayOfWeek] ?: dailyLimitSec.takeIf { it > 0 }
        return if (base == null || base >= UNLIMITED_SEC) null else base
    }

    /** Today's one-off adjustment in seconds (+ extra, − taken away). Always 0 on any other day. */
    fun extraFor(dayKey: String): Int = if (bonusDay == dayKey) bonusSec else 0

    /** Allowed seconds for [dayOfWeek] on the day named [dayKey] including the adjustment, or null when unlimited. */
    fun limitFor(dayOfWeek: Int, dayKey: String): Int? {
        val base = baseLimitFor(dayOfWeek) ?: return null
        return (base + extraFor(dayKey)).coerceAtLeast(0)
    }

    /** This rule with today's adjustment replaced by [extraSec] (0 clears it). */
    fun withExtra(dayKey: String, extraSec: Int): AppRule =
        if (extraSec == 0) copy(bonusDay = "", bonusSec = 0) else copy(bonusDay = dayKey, bonusSec = extraSec)

    /**
     * Adds [deltaSec] (negative = take time away) to today's adjustment. The result is clamped so today's
     * limit never goes below zero and the extra never exceeds [MAX_EXTRA_SEC]. Unlimited apps are unchanged.
     */
    fun adjustedBy(dayOfWeek: Int, dayKey: String, deltaSec: Int): AppRule {
        val base = baseLimitFor(dayOfWeek) ?: return this
        val next = (extraFor(dayKey).toLong() + deltaSec).coerceIn(-base.toLong(), MAX_EXTRA_SEC.toLong()).toInt()
        return withExtra(dayKey, next)
    }

    companion object {
        const val UNLIMITED_SEC = 24 * 60 * 60
        const val MAX_EXTRA_SEC = 12 * 60 * 60
    }
}

/** A recurring window (school hours, bedtime) during which only [allowedPackages] may be used. */
data class Schedule(
    val id: String,
    val name: String,
    val days: List<Int>,
    val startMin: Int,
    val endMin: Int,
    val allowedPackages: List<String> = emptyList(),
    val enabled: Boolean = true
)

data class FamilyPolicy(
    val blockedDomains: List<String> = emptyList(),
    val blockedUrls: List<String> = emptyList(),
    val appRules: Map<String, AppRule> = emptyMap(),
    val schedules: List<Schedule> = emptyList(),
    /** While [pauseUntil] is in the future every non-essential app is blocked. */
    val pauseUntil: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun blockedPackages(): List<String> = appRules.filter { it.value.blocked }.keys.sorted()
    fun withRule(packageName: String, rule: AppRule): FamilyPolicy =
        copy(appRules = if (rule.isEmpty) appRules - packageName else appRules + (packageName to rule))
}

object PolicyCodec {
    fun parse(json: JsonObject?): FamilyPolicy {
        if (json == null) return FamilyPolicy()
        val rules = linkedMapOf<String, AppRule>()
        json.objectOrNull("appRules")?.entrySet()?.forEach { (key, value) ->
            val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val daySec = linkedMapOf<Int, Int>()
            obj.objectOrNull("daySec")?.entrySet()?.forEach { (day, seconds) ->
                val d = day.toIntOrNull()
                val s = seconds.intOrNull()
                if (d != null && d in 1..7 && s != null && s >= 0) daySec[d] = s
            }
            rules[PkgKey.decode(key)] = AppRule(
                blocked = obj.boolOrFalse("blocked"),
                dailyLimitSec = obj.intOr("dailySec", 0).coerceAtLeast(0),
                daySec = daySec,
                bonusDay = obj.stringOr("bonusDay", ""),
                bonusSec = obj.intOr("bonusSec", 0).coerceIn(-AppRule.UNLIMITED_SEC, AppRule.UNLIMITED_SEC)
            )
        }
        // Rules published by earlier Guardian versions stay honoured.
        json.stringList("blockedPackages").forEach { packageName ->
            rules[packageName] = (rules[packageName] ?: AppRule()).copy(blocked = true)
        }
        json.objectOrNull("appTimeLimitsMinutes")?.entrySet()?.forEach { (key, value) ->
            val minutes = value.intOrNull() ?: return@forEach
            val packageName = PkgKey.decode(key)
            val current = rules[packageName] ?: AppRule()
            if (minutes > 0 && current.dailyLimitSec <= 0) rules[packageName] = current.copy(dailyLimitSec = minutes * 60)
        }
        val schedules = json.arrayOrNull("schedules")?.mapNotNull { parseSchedule(it) } ?: emptyList()
        return FamilyPolicy(
            blockedDomains = json.stringList("blockedDomains"),
            blockedUrls = json.stringList("blockedUrls"),
            appRules = rules.filterValues { !it.isEmpty },
            schedules = schedules,
            pauseUntil = json.longOr("pauseUntil", 0L),
            updatedAt = json.longOr("updatedAt", 0L)
        )
    }

    fun ruleToJson(rule: AppRule): JsonObject = JsonObject().apply {
        if (rule.blocked) addProperty("blocked", true)
        if (rule.dailyLimitSec > 0) addProperty("dailySec", rule.dailyLimitSec)
        if (rule.daySec.isNotEmpty()) add("daySec", JsonObject().apply {
            rule.daySec.toSortedMap().forEach { (day, sec) -> addProperty(day.toString(), sec) }
        })
        if (rule.bonusSec != 0 && rule.bonusDay.isNotBlank()) {
            addProperty("bonusDay", rule.bonusDay)
            addProperty("bonusSec", rule.bonusSec)
        }
    }

    /**
     * A multi-path update for one app rule. Sending only the changed paths means two parent
     * devices can never overwrite each other's unrelated rules.
     */
    fun ruleUpdate(packageName: String, rule: AppRule, policy: FamilyPolicy, now: Long): JsonObject {
        val next = policy.withRule(packageName, rule)
        return JsonObject().apply {
            val path = "appRules/" + PkgKey.encode(packageName)
            if (rule.isEmpty) add(path, com.google.gson.JsonNull.INSTANCE) else add(path, ruleToJson(rule))
            add("blockedPackages", JsonArray().also { array -> next.blockedPackages().forEach { array.add(it) } })
            addProperty("updatedAt", now)
        }
    }

    fun scheduleToJson(schedule: Schedule): JsonObject = JsonObject().apply {
        addProperty("id", schedule.id)
        addProperty("name", schedule.name)
        add("days", JsonArray().also { array -> schedule.days.forEach { array.add(it) } })
        addProperty("startMin", schedule.startMin)
        addProperty("endMin", schedule.endMin)
        add("allowed", JsonArray().also { array -> schedule.allowedPackages.forEach { array.add(it) } })
        addProperty("enabled", schedule.enabled)
    }

    private fun parseSchedule(element: JsonElement): Schedule? {
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val days = obj.arrayOrNull("days")?.mapNotNull { it.intOrNull() }?.filter { it in 1..7 } ?: emptyList()
        if (days.isEmpty()) return null
        return Schedule(
            id = obj.stringOr("id", ""),
            name = obj.stringOr("name", "Schedule"),
            days = days,
            startMin = obj.intOr("startMin", 0).coerceIn(0, 1439),
            endMin = obj.intOr("endMin", 0).coerceIn(0, 1439),
            allowedPackages = obj.stringList("allowed"),
            enabled = if (obj.has("enabled")) obj.boolOrFalse("enabled") else true
        )
    }
}

// ---- tiny null-safe JSON helpers shared across the project ----

fun JsonElement?.intOrNull(): Int? = try {
    if (this == null || isJsonNull) null else asInt
} catch (_: Exception) { null }

fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.arrayOrNull(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

fun JsonObject.stringOr(name: String, fallback: String = ""): String = try {
    get(name)?.takeIf { it.isJsonPrimitive }?.asString ?: fallback
} catch (_: Exception) { fallback }

fun JsonObject.intOr(name: String, fallback: Int = 0): Int = get(name).intOrNull() ?: fallback

fun JsonObject.longOr(name: String, fallback: Long = 0L): Long = try {
    get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: fallback
} catch (_: Exception) { fallback }

fun JsonObject.boolOrFalse(name: String): Boolean = try {
    get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
} catch (_: Exception) { false }

fun JsonObject.stringList(name: String): List<String> =
    arrayOrNull(name)?.mapNotNull { item ->
        try { item.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) } catch (_: Exception) { null }
    } ?: emptyList()
