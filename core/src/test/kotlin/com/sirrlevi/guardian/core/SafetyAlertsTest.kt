package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SafetyAlertsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L // 2027-01-15 08:00 UTC

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, min, 0) }.timeInMillis

    private fun msg(id: String, sender: String?, text: String?, timeMs: Long, app: String = "WhatsApp", dir: CommDirection = CommDirection.INCOMING) =
        CommRecord(id, "com.whatsapp", app, sender, null, false, text, timeMs, CommApps.CATEGORY_MESSAGE, dir, text != null)

    private fun input(
        comms: List<CommRecord> = emptyList(), apps: List<SafetyApp> = emptyList(), sessions: Map<String, List<SafetySession>> = emptyMap(),
        days: List<SafetyDay> = emptyList(), lastSeen: Long = now - 60_000L, reportedAt: Long = now - 60_000L,
        usage: Boolean? = null, notif: Boolean? = null, service: Boolean? = null
    ) = SafetyInput("Aarav's phone", now, utc, comms, apps, sessions, days, lastSeen, reportedAt, usage, notif, service)

    private fun eval(i: SafetyInput, s: SafetySettings = SafetySettings()) = SafetyEvaluator.evaluate(i, s)

    // ---------------------------------------------------------------- neutral wording

    @Test fun everyKindUsesOneOfTheNeutralLabels() {
        val allowed = setOf(SafetyLabels.SUSPICIOUS, SafetyLabels.REVIEW, SafetyLabels.REPEATED, SafetyLabels.UNKNOWN_SENDER)
        assertTrue(AlertKind.values().all { it.label in allowed })
        val forbidden = listOf("groom", "predator", "criminal", "abuse", "danger", "threat detected", "paedophile", "pedophile")
        AlertKind.values().forEach { k -> forbidden.forEach { assertFalse("${k.label} ${k.setting}", (k.label + k.setting).lowercase().contains(it)) } }
    }

    @Test fun everyAlertSaysWhyAndNeverClaimsCertainty() {
        val comms = listOf(
            msg("a1", "Old Friend", "hi", now - 6 * day), msg("a2", "Old Friend", "hello", now - 5 * day),
            msg("b1", "Sam", "check this http://bit.ly/abc", now - 3 * hour), msg("b2", "Sam", "are you home alone", now - 2 * hour), msg("b3", "Sam", "hey", now - hour)
        )
        val alerts = eval(
            input(
                comms, apps = listOf(SafetyApp("p.new", "Chatty", false, now - 5 * hour)), lastSeen = now - 10 * hour, usage = false, notif = false, service = false,
                days = listOf(SafetyDay("20270114", 7 * 3600, listOf("YouTube" to 4 * 3600)))
            )
        )
        assertTrue(alerts.size >= 7)
        val banned = listOf("proves", "proof that", "is a groomer", "is a predator", "definitely", "confirmed", "is dangerous", "will harm", "criminal")
        for (a in alerts) {
            assertTrue(a.id, SafetyIds.isValid(a.id))
            assertTrue("reason for ${a.title}", a.reason.length > 40)
            banned.forEach { assertFalse("${a.title}: $it", a.reason.lowercase().contains(it)) }
            assertTrue(a.label in setOf(SafetyLabels.SUSPICIOUS, SafetyLabels.REVIEW, SafetyLabels.REPEATED))
        }
    }

    // ---------------------------------------------------------------- unknown sender, repeated contact

    private val history = listOf(msg("h1", "Mum", "dinner?", now - 6 * day), msg("h2", "Mum", "ok", now - 5 * day), msg("h3", "Riya", "hi", now - 4 * day))

    @Test fun aNewSenderWhoKeepsWritingIsShownAsARepeatedContactPattern() {
        val comms = history + listOf(msg("s1", "Sam", "hey", now - 5 * hour), msg("s2", "Sam", "you there", now - 3 * hour), msg("s3", "Sam", "hello??", now - hour))
        val a = eval(input(comms)).single { it.kind == AlertKind.UNKNOWN_SENDER }
        assertEquals("${SafetyLabels.UNKNOWN_SENDER}: Sam", a.title)
        assertTrue(a.reason.contains("3 times in the last 24 hours"))
        assertTrue(a.reason.contains(SafetyPolicy.SELF_NOTE))
        assertEquals(now - hour, a.ts)
    }

    @Test fun twoMessagesAreNotAPattern() {
        val comms = history + listOf(msg("s1", "Sam", "hey", now - 3 * hour), msg("s2", "Sam", "you there", now - hour))
        assertTrue(eval(input(comms)).none { it.kind == AlertKind.UNKNOWN_SENDER })
    }

    @Test fun someoneWhoWroteBeforeIsFamiliar() {
        val comms = history + listOf(msg("o", "Sam", "old", now - 4 * day)) +
            listOf(msg("s1", "Sam", "hey", now - 5 * hour), msg("s2", "Sam", "you there", now - 3 * hour), msg("s3", "Sam", "hello", now - hour))
        assertTrue(eval(input(comms)).none { it.kind == AlertKind.UNKNOWN_SENDER })
    }

    @Test fun withTooLittleHistoryNobodyIsCalledNew() {
        val comms = listOf(msg("s1", "Sam", "hey", now - 5 * hour), msg("s2", "Sam", "you there", now - 3 * hour), msg("s3", "Sam", "hello", now - hour))
        assertTrue("a fresh install knows nobody, so it can't tell who is new", eval(input(comms)).none { it.kind == AlertKind.UNKNOWN_SENDER })
    }

    @Test fun theChildsOwnRepliesNeverCountAsContact() {
        val comms = history + listOf(
            msg("s1", "Sam", "a", now - 5 * hour, dir = CommDirection.OUTGOING), msg("s2", "Sam", "b", now - 3 * hour, dir = CommDirection.OUTGOING),
            msg("s3", "Sam", "c", now - hour, dir = CommDirection.OUTGOING)
        )
        assertTrue(eval(input(comms)).none { it.kind == AlertKind.UNKNOWN_SENDER })
    }

    @Test fun theSameRepeatedContactKeepsTheSameIdSoADismissalSticks() {
        val comms = history + listOf(msg("s1", "Sam", "hey", now - 5 * hour), msg("s2", "Sam", "you there", now - 3 * hour), msg("s3", "Sam", "hello??", now - hour))
        val first = eval(input(comms)).single { it.kind == AlertKind.UNKNOWN_SENDER }.id
        val later = SafetyInput("Aarav's phone", now + 2 * hour, utc, comms + msg("s4", "Sam", "more", now + hour))
        assertEquals(first, SafetyEvaluator.evaluate(later, SafetySettings()).single { it.kind == AlertKind.UNKNOWN_SENDER }.id)
    }

    // ---------------------------------------------------------------- links

    @Test fun linksThatHideTheirDestinationAreFlaggedWithTheReason() {
        assertEquals(1, LinkRules.findings("look http://bit.ly/3xYz").size)
        assertTrue(LinkRules.findings("go to http://192.168.4.7/login").single().reasons.single().contains("bare number"))
        assertTrue(LinkRules.findings("https://xn--pple-43d.com/id").single().reasons.single().contains("look-alike"))
        assertTrue(LinkRules.findings("https://google.com@evil.example/x").single().reasons.single().contains("@"))
        assertTrue(LinkRules.findings("win now https://prize-claim.xyz/a").single().reasons.single().contains(".xyz"))
    }

    @Test fun ordinaryLinksAndPlainTextAreLeftAlone() {
        assertTrue(LinkRules.findings("https://www.wikipedia.org/wiki/Cat").isEmpty())
        assertTrue(LinkRules.findings("see https://youtube.com/watch?v=abc").isEmpty())
        assertTrue(LinkRules.findings("meet at 5, bring the file.zip").isEmpty())
        assertTrue(LinkRules.findings(null).isEmpty())
        assertTrue(LinkRules.findings("").isEmpty())
    }

    @Test fun aLinkAlertNamesNoVerdictAndSaysTheLinkWasNotOpened() {
        val a = eval(input(listOf(msg("l1", "Sam", "free skins http://bit.ly/abc", now - hour)))).single { it.kind == AlertKind.SUSPICIOUS_LINK }
        assertEquals(SafetyLabels.SUSPICIOUS, a.label)
        assertTrue(a.reason.contains("did not open it"))
        assertEquals(AlertLevel.NOTE, a.level)
    }

    // ---------------------------------------------------------------- wording

    @Test fun phrasesAreMatchedAsWholePhrasesNotInsideOtherWords() {
        assertEquals(WordRules.Group.SECRECY, WordRules.matches("Please don’t tell your parents about this", true).single().group)
        assertEquals(WordRules.Group.PICTURES, WordRules.matches("ARE YOU HOME ALONE?", true).single().group)
        assertTrue(WordRules.matches("the kyshu festival", true).isEmpty())
        assertTrue(WordRules.matches("our secretary called", true).isEmpty())
        assertTrue(WordRules.matches("nudestone paint", true).isEmpty())
    }

    @Test fun ordinaryChatDoesNotMatch() {
        for (t in listOf("see you at football", "can you send me the notes", "i'm home alone watching a film?", "dinner at 8", "that exam made me want to die laughing")) {
            val hits = WordRules.matches(t, true)
            // "want to die" is a phrase on purpose: it is shown as wellbeing wording for a calm look, never as a finding.
            if (t.contains("want to die")) assertEquals(WordRules.Group.WELLBEING, hits.single().group) else assertTrue("'$t'", hits.isEmpty())
        }
    }

    @Test fun onlyWellbeingWordingCountsFromTheChildsOwnSide() {
        assertTrue(WordRules.matches("don't tell your parents", incoming = false).isEmpty())
        assertEquals(WordRules.Group.WELLBEING, WordRules.matches("i want to die", incoming = false).single().group)
    }

    @Test fun aWordsAlertShowsTheMatchedPhraseTheGroupAndAContextCaution() {
        val a = eval(input(listOf(msg("w1", "Sam", "this is our little secret ok", now - hour)))).single { it.kind == AlertKind.SUSPICIOUS_WORDS }
        assertEquals(SafetyLabels.SUSPICIOUS, a.label)
        assertTrue(a.reason.contains("\"our little secret\""))
        assertTrue(a.reason.contains("words alone prove nothing"))
    }

    @Test fun aMessageWithNoReadableContentCreatesNoAlert() {
        assertTrue(eval(input(listOf(msg("n1", "Sam", null, now - hour)))).isEmpty())
    }

    @Test fun oldPreviewsAreNotReportedAgain() {
        assertTrue(eval(input(listOf(msg("o1", "Sam", "our little secret", now - 9 * day)))).isEmpty())
    }

    // ---------------------------------------------------------------- late night

    @Test fun nightOverlapSplitsASessionThatCrossesMidnightAndIgnoresDaytime() {
        val night = SafetyDays.nightOverlap(at(2027, 1, 14, 22, 30), at(2027, 1, 15, 0, 30), utc, 23, 5)
        assertEquals(mapOf("20270114" to 90 * 60_000L), night)
        assertTrue(SafetyDays.nightOverlap(at(2027, 1, 14, 10, 0), at(2027, 1, 14, 12, 0), utc, 23, 5).isEmpty())
        assertEquals(mapOf("20270113" to 60 * 60_000L), SafetyDays.nightOverlap(at(2027, 1, 14, 3, 0), at(2027, 1, 14, 4, 0), utc, 23, 5))
        assertTrue(SafetyDays.nightOverlap(5L, 5L, utc, 23, 5).isEmpty())
    }

    @Test fun halfAnHourAtNightIsReportedWithTheAppsAndTheTiming() {
        val s = mapOf("20270114" to listOf(SafetySession("p.yt", "YouTube", at(2027, 1, 15, 1, 0), at(2027, 1, 15, 1, 40))))
        val a = eval(input(sessions = s)).single { it.kind == AlertKind.LATE_NIGHT }
        assertTrue(a.reason.contains("40 min"))
        assertTrue(a.reason.contains("23:00 and 05:00"))
        assertTrue(a.reason.contains("YouTube"))
        assertTrue(a.reason.contains("only about timing"))
    }

    @Test fun aFewMinutesAtNightIsNotWorthAnAlert() {
        val s = mapOf("20270114" to listOf(SafetySession("p.yt", "YouTube", at(2027, 1, 15, 1, 0), at(2027, 1, 15, 1, 5))))
        assertTrue(eval(input(sessions = s)).none { it.kind == AlertKind.LATE_NIGHT })
    }

    @Test fun theNightWindowFollowsTheParentsSetting() {
        val s = mapOf("20270114" to listOf(SafetySession("p.yt", "YouTube", at(2027, 1, 14, 22, 0), at(2027, 1, 14, 22, 40))))
        assertTrue(eval(input(sessions = s)).none { it.kind == AlertKind.LATE_NIGHT })
        assertTrue(eval(input(sessions = s), SafetySettings(lateFromHour = 21)).any { it.kind == AlertKind.LATE_NIGHT })
    }

    // ---------------------------------------------------------------- long screen time, new apps

    @Test fun aLongDayIsReportedOnlyAboveTheLimit() {
        val days = listOf(SafetyDay("20270113", 4 * 3600 + 59 * 60, emptyList()), SafetyDay("20270114", 5 * 3600, listOf("YouTube" to 3 * 3600, "Roblox" to 3600)))
        val a = eval(input(days = days)).single { it.kind == AlertKind.EXCESSIVE_USAGE }
        assertTrue(a.reason.contains("5 h 0 min"))
        assertTrue(a.reason.contains("YouTube 3 h 0 min"))
        assertTrue(eval(input(days = days), SafetySettings(usageHours = 6)).none { it.kind == AlertKind.EXCESSIVE_USAGE })
    }

    @Test fun onlyRecentlyInstalledNonSystemAppsAreMentionedAndNotGuardianItself() {
        val apps = listOf(
            SafetyApp("p.game", "Cool Game", false, now - 3 * hour), SafetyApp("p.old", "Old App", false, now - 20 * day),
            SafetyApp("p.sys", "System Thing", true, now - hour), SafetyApp("com.sirrlevi.guardian.child", "Guardian", false, now - hour), SafetyApp("p.zero", "Unknown", false, 0L)
        )
        val a = eval(input(apps = apps)).filter { it.kind == AlertKind.NEW_APP }
        assertEquals(listOf("New app: Cool Game"), a.map { it.title })
        assertTrue(a.single().reason.contains("doesn't judge apps"))
    }

    // ---------------------------------------------------------------- the phone itself

    @Test fun switchedOffAccessIsReportedAsWhatTheLastReportSaid() {
        val a = eval(input(usage = false, notif = false, service = false)).filter { it.kind == AlertKind.MONITORING_OFF }
        assertEquals(3, a.size)
        assertTrue(a.all { it.reason.contains("as the phone last reported") })
        assertTrue(a.any { it.title == "Usage access is off" })
    }

    @Test fun anUnknownStateIsNeverReportedAsOff() {
        assertTrue(eval(input(usage = null, notif = null, service = null)).none { it.kind == AlertKind.MONITORING_OFF })
        assertTrue(eval(input(usage = true, notif = true, service = true)).none { it.kind == AlertKind.MONITORING_OFF })
    }

    @Test fun anOldReportIsNotUsedToClaimSomethingIsOffNow() {
        assertTrue(eval(input(reportedAt = now - 14 * hour, lastSeen = now - 14 * hour, usage = false)).none { it.kind == AlertKind.MONITORING_OFF })
        assertTrue(eval(input(reportedAt = 0L, usage = false)).none { it.kind == AlertKind.MONITORING_OFF })
    }

    @Test fun aPhoneThatWentQuietIsReportedOncePerEpisodeAndNeverAsProofOfTampering() {
        assertTrue(eval(input(lastSeen = now - 5 * hour)).none { it.kind == AlertKind.DISCONNECTED })
        val first = eval(input(lastSeen = now - 7 * hour)).single { it.kind == AlertKind.DISCONNECTED }
        assertTrue(first.reason.contains("can't tell which"))
        val later = SafetyEvaluator.evaluate(SafetyInput("Aarav's phone", now + 3 * hour, utc, lastSeenMs = now - 7 * hour), SafetySettings())
        assertEquals("the same quiet spell keeps one id", first.id, later.single { it.kind == AlertKind.DISCONNECTED }.id)
        val another = eval(input(lastSeen = now - 9 * hour)).single { it.kind == AlertKind.DISCONNECTED }
        assertFalse(first.id == another.id)
        assertTrue("never reported for a phone that never reported at all", eval(input(lastSeen = 0L)).none { it.kind == AlertKind.DISCONNECTED })
    }

    // ---------------------------------------------------------------- settings

    @Test fun aTurnedOffCategoryProducesNothing() {
        val comms = listOf(msg("w1", "Sam", "our little secret", now - hour))
        assertTrue(eval(input(comms), SafetySettings().withKind(AlertKind.SUSPICIOUS_WORDS, false)).isEmpty())
        assertEquals(1, eval(input(comms), SafetySettings().withKind(AlertKind.SUSPICIOUS_WORDS, false).withKind(AlertKind.SUSPICIOUS_WORDS, true)).size)
    }

    @Test fun settingsSurviveTheDatabaseAndDamagedDataMeansDefaultsNotSilence() {
        val s = SafetySettings(off = setOf(AlertKind.NEW_APP), lateFromHour = 22, lateToHour = 6, usageHours = 4, disconnectedHours = 12, ts = 9L)
        assertEquals(s, SafetySettingsCodec.fromJson(SafetySettingsCodec.toJson(s)))
        assertEquals(SafetySettings(), SafetySettingsCodec.fromJson(null))
        assertEquals("nothing is switched off by accident", emptySet<AlertKind>(), SafetySettingsCodec.fromJson(com.google.gson.JsonObject()).off)
        val weird = SafetySettingsCodec.toJson(s).also { it.addProperty("usageH", "lots"); it.add("kinds", com.google.gson.JsonPrimitive("none")) }
        val back = SafetySettingsCodec.fromJson(weird)
        assertEquals(5, back.usageHours)
        assertTrue(back.off.isEmpty())
    }

    @Test fun limitsAreKeptSensible() {
        val n = SafetySettings(lateFromHour = 3, lateToHour = 15, usageHours = 0, disconnectedHours = 1000).normalized()
        assertEquals(18, n.lateFromHour); assertEquals(9, n.lateToHour); assertEquals(1, n.usageHours); assertEquals(48, n.disconnectedHours)
    }

    // ---------------------------------------------------------------- permission changes

    @Test fun aPermissionThatWasOnAndIsOffIsReportedOnlyWithSomethingToCompareWith() {
        assertTrue(PermissionWatch.diff(null, mapOf("usage" to false)).isEmpty())
        assertTrue(PermissionWatch.diff(emptyMap(), mapOf("usage" to false)).isEmpty())
        assertEquals(listOf("overlay", "usage"), PermissionWatch.diff(mapOf("usage" to true, "overlay" to true, "vpn" to false), mapOf("usage" to false, "overlay" to false, "vpn" to false)))
        assertTrue("turning something on is not an alert", PermissionWatch.diff(mapOf("usage" to false), mapOf("usage" to true)).isEmpty())
        assertTrue("unknown names are ignored", PermissionWatch.diff(mapOf("x" to true), mapOf("x" to false)).isEmpty())
        assertTrue("the web filter goes off whenever the parent turns it off, so it is not watched", PermissionWatch.diff(mapOf("vpn" to true, "battery" to true), mapOf("vpn" to false, "battery" to false)).isEmpty())
    }

    @Test fun permissionAlertsAdmitTheyCannotSayWhen() {
        val alerts = PermissionWatch.alerts(listOf("usage"), input(), now)
        assertEquals("Usage access was switched off", alerts.single().title)
        assertTrue(alerts.single().reason.contains("can't say exactly when or why"))
        assertEquals(AlertKind.PERMISSION_CHANGE, alerts.single().kind)
    }

    // ---------------------------------------------------------------- storage

    @Test fun alertsSurviveTheDatabaseButBadOnesAreDropped() {
        val a = SafetyAlert(SafetyIds.id(AlertKind.PERMISSION_CHANGE, "x"), AlertKind.PERMISSION_CHANGE, AlertLevel.REVIEW, "Usage access was switched off", "Because…", 5L, "src")
        assertEquals(a, SafetyAlertCodec.fromJson(a.id, SafetyAlertCodec.toJson(a)))
        assertNull(SafetyAlertCodec.fromJson("not a valid id!", SafetyAlertCodec.toJson(a)))
        assertNull(SafetyAlertCodec.fromJson(a.id, com.google.gson.JsonObject()))
        assertNull(SafetyAlertCodec.fromJson(a.id, null))
        assertNotNull(SafetyAlertCodec.fromJson(a.id, SafetyAlertCodec.toJson(a).also { it.addProperty("lv", "???") }))
    }

    @Test fun idsRevealNothingAboutTheirSeedAndAreSafeDatabaseKeys() {
        val id = SafetyIds.id(AlertKind.UNKNOWN_SENDER, "sam|20270115")
        assertFalse(id.contains("sam"))
        assertTrue(id.none { it in ".$#[]/ " })
        assertEquals(id, SafetyIds.id(AlertKind.UNKNOWN_SENDER, "sam|20270115"))
        assertFalse(id == SafetyIds.id(AlertKind.UNKNOWN_SENDER, "sam|20270116"))
    }

    @Test fun theListIsNewestFirstAndCapped() {
        val comms = (1..150).map { msg("m$it", "Sam", "our little secret", now - it * 60_000L) }
        val out = eval(input(comms))
        assertEquals(SafetyPolicy.MAX_ALERTS, out.size)
        assertEquals(out.sortedByDescending { it.ts }, out)
    }
}
