package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyModelTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test fun packageKeysNeverContainDots() {
        val key = PkgKey.encode("com.google.android.youtube")
        assertFalse(key.contains('.'))
        assertEquals("com.google.android.youtube", PkgKey.decode(key))
    }

    @Test fun parsesAppRulesWithEncodedKeys() {
        val policy = PolicyCodec.parse(json("""
            {"appRules":{"com,whatsapp":{"blocked":true},"com,youtube":{"dailySec":3600,"daySec":{"1":7200,"7":0}}},
             "blockedDomains":["a.com"],"updatedAt":99}
        """.trimIndent()))
        assertTrue(policy.appRules.getValue("com.whatsapp").blocked)
        assertEquals(3600, policy.appRules.getValue("com.youtube").dailyLimitSec)
        assertEquals(7200, policy.appRules.getValue("com.youtube").daySec[1])
        assertEquals(listOf("a.com"), policy.blockedDomains)
        assertEquals(99L, policy.updatedAt)
    }

    @Test fun legacyFieldsStillWork() {
        val policy = PolicyCodec.parse(json("""{"blockedPackages":["com.game"],"appTimeLimitsMinutes":{"com,video":45}}"""))
        assertTrue(policy.appRules.getValue("com.game").blocked)
        assertEquals(45 * 60, policy.appRules.getValue("com.video").dailyLimitSec)
    }

    @Test fun ruleJsonRoundTrips() {
        val rule = AppRule(blocked = false, dailyLimitSec = 5400, daySec = mapOf(2 to 1800), bonusDay = "20261121", bonusSec = 900)
        val parsed = PolicyCodec.parse(JsonObject().apply {
            add("appRules", JsonObject().apply { add("com,x", PolicyCodec.ruleToJson(rule)) })
        })
        assertEquals(rule, parsed.appRules["com.x"])
    }

    @Test fun ruleUpdateUsesPathsAndDeletesEmptyRules() {
        val base = FamilyPolicy(appRules = mapOf("com.a" to AppRule(blocked = true)))
        val update = PolicyCodec.ruleUpdate("com.a", AppRule(), base, 5L)
        assertTrue(update.get("appRules/com,a").isJsonNull)
        assertEquals(0, update.getAsJsonArray("blockedPackages").size())
        assertEquals(5L, update.get("updatedAt").asLong)
    }

    @Test fun limitForHonoursPerDayAndBonus() {
        val rule = AppRule(dailyLimitSec = 3600, daySec = mapOf(7 to 7200), bonusDay = "20261121", bonusSec = 600)
        assertEquals(3600 + 600, rule.limitFor(2, "20261121"))
        assertEquals(3600, rule.limitFor(2, "20261122"))
        assertEquals(7200, rule.limitFor(7, "20261122"))
        assertNull(AppRule(dailyLimitSec = AppRule.UNLIMITED_SEC).limitFor(1, "x"))
        assertNull(AppRule().limitFor(1, "x"))
    }

    @Test fun malformedPolicyDoesNotCrash() {
        val policy = PolicyCodec.parse(json("""{"appRules":{"com,a":"oops","com,b":{"dailySec":"abc"}},"schedules":[1,{"days":[]}]}"""))
        assertTrue(policy.appRules.isEmpty())
        assertTrue(policy.schedules.isEmpty())
    }

    // ---- today's adjustment (extra time / time taken away) ----

    private val today = "20261123"
    private val dow = 2

    @Test fun savingANewLimitReplacesTheOldOneInsteadOfAddingToIt() {
        val two = AppRule(dailyLimitSec = 2 * 3600)
        val three = two.copy(dailyLimitSec = 3 * 3600)
        assertEquals(3 * 3600, three.limitFor(dow, today))
        // and through the wire format the server stores
        val update = PolicyCodec.ruleUpdate("com.yt", three, FamilyPolicy(appRules = mapOf("com.yt" to two)), 1L)
        val written = update.getAsJsonObject("appRules/com,yt")
        assertEquals(3 * 3600, written.get("dailySec").asInt)
        assertFalse(written.has("bonusSec"))
    }

    @Test fun extraTimeIsKeptSeparateFromTheNormalLimit() {
        val rule = AppRule(dailyLimitSec = 3 * 3600).adjustedBy(dow, today, 2 * 3600)
        assertEquals(3 * 3600, rule.baseLimitFor(dow))
        assertEquals(2 * 3600, rule.extraFor(today))
        assertEquals(5 * 3600, rule.limitFor(dow, today))
        assertEquals(0, rule.extraFor("20261124"))
        assertEquals(3 * 3600, rule.limitFor(dow, "20261124"))
    }

    @Test fun adjustmentsAddUpAndCanGoBothWays() {
        var rule = AppRule(dailyLimitSec = 3600)
        rule = rule.adjustedBy(dow, today, 10 * 60)
        rule = rule.adjustedBy(dow, today, 30 * 60)
        assertEquals(40 * 60, rule.extraFor(today))
        rule = rule.adjustedBy(dow, today, -60 * 60)
        assertEquals(-20 * 60, rule.extraFor(today))
        assertEquals(40 * 60, rule.limitFor(dow, today))
        rule = rule.adjustedBy(dow, today, 20 * 60)
        assertEquals(0, rule.extraFor(today))
        assertEquals("", rule.bonusDay)
        assertEquals(AppRule(dailyLimitSec = 3600), rule)
    }

    @Test fun adjustmentNeverTakesTheLimitBelowZeroOrAboveTheCap() {
        val cut = AppRule(dailyLimitSec = 30 * 60).adjustedBy(dow, today, -60 * 60)
        assertEquals(0, cut.limitFor(dow, today))
        assertEquals(-30 * 60, cut.extraFor(today))
        val big = AppRule(dailyLimitSec = 3600).adjustedBy(dow, today, 100 * 3600)
        assertEquals(AppRule.MAX_EXTRA_SEC, big.extraFor(today))
    }

    @Test fun adjustmentIsIgnoredForUnlimitedApps() {
        val unlimited = AppRule()
        assertEquals(unlimited, unlimited.adjustedBy(dow, today, 600))
        val all = AppRule(dailyLimitSec = AppRule.UNLIMITED_SEC)
        assertEquals(all, all.adjustedBy(dow, today, 600))
    }

    @Test fun staleAdjustmentFromYesterdayStartsFromScratch() {
        val old = AppRule(dailyLimitSec = 3600, bonusDay = "20261122", bonusSec = 1800)
        val next = old.adjustedBy(dow, today, 600)
        assertEquals(600, next.extraFor(today))
        assertEquals(3600 + 600, next.limitFor(dow, today))
    }

    @Test fun negativeAdjustmentSurvivesTheWireFormat() {
        val rule = AppRule(dailyLimitSec = 3600, bonusDay = today, bonusSec = -900)
        val parsed = PolicyCodec.parse(JsonObject().apply {
            add("appRules", JsonObject().apply { add("com,x", PolicyCodec.ruleToJson(rule)) })
        })
        assertEquals(rule, parsed.appRules["com.x"])
        assertEquals(2700, parsed.appRules.getValue("com.x").limitFor(dow, today))
    }

    @Test fun enforcementUsesTheAdjustedLimit() {
        val clock = DayClock(dow, today, 600, 0L)
        val rule = AppRule(dailyLimitSec = 3600, bonusDay = today, bonusSec = -1800)
        val policy = FamilyPolicy(appRules = mapOf("com.yt" to rule))
        assertFalse(Enforcement.decide("com.yt", policy, 1700, 0L, clock, emptySet()).blocked)
        assertTrue(Enforcement.decide("com.yt", policy, 1800, 0L, clock, emptySet()).blocked)
    }
}
