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
}
