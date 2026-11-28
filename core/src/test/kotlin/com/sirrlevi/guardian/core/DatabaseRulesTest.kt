package com.sirrlevi.guardian.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real firebase/database.rules.json against who may do what. The tiny rules evaluator below understands exactly the
 * expressions used in that file (it fails loudly on anything else), including cascading: a grant higher up applies below it.
 * It is not Firebase itself, so the rules should still be tried in the Firebase console simulator once after publishing.
 */
class DatabaseRulesTest {
    private val now = 1_800_000_000_000L
    private val family = "P1"       // the parent's uid is the family id
    private val kid = "C1"          // a paired phone
    private val sibling = "C2"      // another paired phone of the same family
    private val stranger = "S1"     // signed in, belongs to no family
    private val otherParent = "P2"

    private val rules: JsonObject = run {
        val f = listOf(File("firebase/database.rules.json"), File("../firebase/database.rules.json")).first { it.exists() }
        JsonParser.parseString(f.readText()).asJsonObject.getAsJsonObject("rules")
    }

    private fun path(s: String) = s.split("/").filter { it.isNotEmpty() }

    /** The database as it is when both phones are paired and the parent has already set things up. */
    private fun db(extra: Map<String, Any?> = emptyMap(), members: List<String> = listOf(kid, sibling)): Map<String, Any?> {
        val base = mapOf(
            "pairingCodes" to mapOf("12345678" to mapOf("familyId" to family, "expiresAt" to now + 600_000L)),
            "families" to mapOf(
                family to mapOf(
                    "policies" to mapOf("x" to 1L),
                    "members" to members.associateWith { mapOf("role" to "child") },
                    "devices" to mapOf(
                        kid to mapOf(
                            "safety" to mapOf("settings" to mapOf("on" to true)),
                            "settings" to mapOf("media" to mapOf("on" to true)),
                            "control" to mapOf("inbox" to mapOf("cmd1" to mapOf("t" to "lock"))),
                            "capture" to mapOf("inbox" to mapOf("cmd" to mapOf("c1" to mapOf("t" to "start")))),
                            "requests" to mapOf("pend" to mapOf("status" to "pending", "p" to "app"), "done" to mapOf("status" to "approved", "p" to "app")),
                            "daily" to mapOf("20261128" to mapOf("t" to 1L))
                        ),
                        sibling to mapOf("daily" to mapOf("20261128" to mapOf("t" to 2L)), "safety" to mapOf("settings" to mapOf("on" to true)))
                    )
                )
            )
        )
        return base + extra
    }

    private fun read(uid: String?, p: String, data: Map<String, Any?> = db()) = Rules(rules, now).allowed("read", uid, path(p), data, data)
    private fun write(uid: String?, p: String, value: Any? = mapOf("x" to 1L), data: Map<String, Any?> = db()) =
        Rules(rules, now).allowed("write", uid, path(p), data, Rules.set(data, path(p), value))
    private fun delete(uid: String?, p: String, data: Map<String, Any?> = db()) = write(uid, p, null, data)

    private val dayKey = "20261128"
    private fun d(sub: String) = "families/$family/devices/$kid/$sub"

    // ---------------------------------------------------------------- nobody else

    @Test fun anyoneNotSignedInOrOutsideTheFamilyGetsNothing() {
        for (who in listOf<String?>(null, stranger, otherParent)) {
            assertFalse("read family as $who", read(who, "families/$family"))
            assertFalse("read device as $who", read(who, d("daily/$dayKey")))
            assertFalse("read policies as $who", read(who, "families/$family/policies"))
            assertFalse("write device as $who", write(who, d("daily/$dayKey")))
            assertFalse("write policies as $who", write(who, "families/$family/policies"))
            assertFalse("write safety as $who", write(who, GuardianPaths.safetySettings(family, kid)))
        }
        assertFalse(read(kid, "somethingElse"))
        assertFalse(write(family, "somethingElse"))
        assertFalse("the root is closed", read(family, ""))
    }

    @Test fun theParentOwnsTheWholeFamilyAndNothingOutsideIt() {
        assertTrue(read(family, "families/$family"))
        assertTrue(read(family, d("comms/$dayKey")))
        assertTrue(write(family, "families/$family/policies"))
        assertFalse(read(family, "families/$otherParent"))
        assertFalse(write(family, "families/$otherParent/policies"))
    }

    // ---------------------------------------------------------------- what the phone does

    @Test fun aPairedPhoneCanWriteEverythingTheAppSends() {
        val f = family; val k = kid
        val paths = listOf(
            GuardianPaths.realtime(f, k) + "/live", GuardianPaths.realtime(f, k) + "/ack", GuardianPaths.info(f, k), GuardianPaths.catalog(f, k), GuardianPaths.icons(f, k),
            GuardianPaths.sessions(f, k, dayKey) + "/12345", GuardianPaths.screen(f, k, dayKey) + "/9", GuardianPaths.daily(f, k) + "/$dayKey", GuardianPaths.hourly(f, k, dayKey),
            GuardianPaths.commsDay(f, k, dayKey), GuardianPaths.comms(f, k),
            GuardianPaths.mediaStatus(f, k), GuardianPaths.mediaItem(f, k, "m1"), GuardianPaths.mediaBlob(f, k, "m1"),
            GuardianPaths.captureState(f, k), GuardianPaths.captureAck(f, k, "a1"), GuardianPaths.captureShot(f, k, "s1"), GuardianPaths.captureShotBlob(f, k, "s1"),
            GuardianPaths.captureLive(f, k), GuardianPaths.captureLiveBlob(f, k), GuardianPaths.captureLog(f, k) + "/e1",
            GuardianPaths.controlStatus(f, k), GuardianPaths.controlAck(f, k, "a1"), GuardianPaths.controlLog(f, k) + "/e1",
            GuardianPaths.wallpaper(f, k), GuardianPaths.wallpaperBlob(f, k),
            GuardianPaths.recoveryState(f, k), GuardianPaths.recoveryLast(f, k), GuardianPaths.recoveryPoint(f, k, "p1"), GuardianPaths.recoveryHistory(f, k),
            d("status"), d("usage/today"), d("usage/week"), d("recentActivities"), d("apps/installed"), d("browserHistory/-Nabc"),
            d("deviceName"), d("manufacturer"), d("androidVersion"), d("joinedAt"), d("lastSeen"), d("monitoringDisclosure"), d("requests/1700000000000")
        )
        for (p in paths) assertTrue("the phone must be able to write $p", write(kid, p))
    }

    @Test fun aPairedPhoneCanReadItsOwnDataAndTheFamilyPolicyOnly() {
        val f = family; val k = kid
        val readable = listOf(
            GuardianPaths.policy(f), GuardianPaths.mediaSettings(f, k), GuardianPaths.mediaDeleted(f, k), GuardianPaths.mediaItems(f, k),
            GuardianPaths.captureInbox(f, k), GuardianPaths.captureState(f, k), GuardianPaths.captureLog(f, k), GuardianPaths.captureShots(f, k), GuardianPaths.captureAcks(f, k),
            GuardianPaths.controlInbox(f, k), GuardianPaths.controlAcks(f, k), GuardianPaths.controlLog(f, k),
            GuardianPaths.recoverySettings(f, k), GuardianPaths.recoveryHistory(f, k), GuardianPaths.daily(f, k) + "/$dayKey", GuardianPaths.comms(f, k), "families/$f/members/$k"
        )
        for (p in readable) assertTrue("the phone must be able to read $p", read(kid, p))
        assertFalse("the family record as a whole", read(kid, "families/$f"))
        assertFalse("the member list", read(kid, "families/$f/members"))
        assertFalse("another phone's data", read(kid, "families/$f/devices/$sibling/daily/$dayKey"))
        assertFalse("another phone's data (whole node)", read(kid, "families/$f/devices/$sibling"))
        assertFalse("every phone at once", read(kid, "families/$f/devices"))
        assertFalse("its own whole node, which holds the parent's alerts", read(kid, "families/$f/devices/$k"))
    }

    @Test fun aPhoneCanNeverSeeOrChangeTheParentsSafetyAlerts() {
        val f = family; val k = kid
        assertFalse(read(kid, GuardianPaths.safety(f, k)))
        assertFalse(read(kid, GuardianPaths.safetySettings(f, k)))
        assertFalse(read(kid, GuardianPaths.safetyDismissed(f, k)))
        assertFalse(read(kid, GuardianPaths.safetyEvents(f, k)))
        assertFalse(read(kid, GuardianPaths.safetyPerms(f, k)))
        assertFalse(write(kid, GuardianPaths.safetySettings(f, k)))
        assertFalse(write(kid, GuardianPaths.safetyDismissal(f, k, "a1"), 1L))
        assertFalse(delete(kid, GuardianPaths.safetyDismissal(f, k, "a1")))
        assertFalse(write(kid, GuardianPaths.safetyEvent(f, k, "a1")))
        assertFalse(write(kid, GuardianPaths.safetyPerms(f, k)))
        assertFalse(delete(kid, GuardianPaths.safety(f, k)))
        assertFalse("not even by replacing the whole phone record", write(kid, "families/$f/devices/$k"))
        assertFalse("nor the whole devices list", write(kid, "families/$f/devices"))
        assertFalse("nor the whole family", write(kid, "families/$f"))
    }

    @Test fun aPhoneCannotWriteWhatOnlyTheParentSets() {
        val f = family; val k = kid
        assertFalse(write(kid, GuardianPaths.mediaSettings(f, k)))
        assertFalse(write(kid, GuardianPaths.mediaDeleted(f, k) + "/m1", 1L))
        assertFalse(write(kid, GuardianPaths.recoverySettings(f, k)))
        assertFalse(write(kid, GuardianPaths.policy(f)))
        assertFalse(write(kid, GuardianPaths.captureViewer(f, k), 1L))
        assertFalse(write(kid, GuardianPaths.captureCommand(f, k, "new1")))
        assertFalse(write(kid, GuardianPaths.controlCommand(f, k, "new1")))
        assertFalse("a command can't be forged into the inbox", write(kid, GuardianPaths.controlInbox(f, k), mapOf("forged" to mapOf("t" to "erase"))))
        assertFalse(write(kid, "families/$f/members/$sibling"))
        assertFalse("settings as a whole", write(kid, d("settings"), mapOf("media" to mapOf("on" to false))))
    }

    @Test fun aPhoneMayOnlyClearACommandItHasHandledNeverCreateOrChangeOne() {
        val f = family; val k = kid
        assertTrue(delete(kid, GuardianPaths.controlCommand(f, k, "cmd1")))
        assertTrue(delete(kid, GuardianPaths.captureCommand(f, k, "c1")))
        assertFalse(write(kid, GuardianPaths.controlCommand(f, k, "cmd1"), mapOf("t" to "erase", "ok" to "ERASE")))
        assertFalse(write(kid, GuardianPaths.captureCommand(f, k, "c1"), mapOf("t" to "other")))
        assertTrue("the parent creates them", write(family, GuardianPaths.controlCommand(f, k, "new1"), mapOf("t" to "lock")))
        assertTrue(write(family, GuardianPaths.captureCommand(f, k, "new2"), mapOf("t" to "start")))
        assertTrue(delete(family, GuardianPaths.controlCommand(f, k, "cmd1")))
    }

    @Test fun aPhoneCannotTouchAnotherPhoneOrAnotherFamily() {
        val f = family
        assertFalse(write(kid, GuardianPaths.daily(f, sibling) + "/$dayKey"))
        assertFalse(write(kid, GuardianPaths.controlAck(f, sibling, "a1")))
        assertFalse(write(kid, GuardianPaths.info(otherParent, kid)))
        assertFalse(read(kid, GuardianPaths.policy(otherParent)))
        assertFalse(write(sibling, GuardianPaths.daily(f, kid) + "/$dayKey"))
    }

    @Test fun aRequestForMoreTimeStaysPendingForThePhoneAndOnlyTheParentDecidesIt() {
        val f = family; val k = kid
        val pending = mapOf("p" to "app", "n" to "App", "reason" to "TIME", "ts" to 1L, "status" to "pending")
        assertTrue("new request", write(kid, d("requests/1700000000001"), pending))
        assertTrue("re-sent while still pending", write(kid, d("requests/pend"), pending))
        assertFalse("can't approve itself", write(kid, d("requests/pend/status"), "approved"))
        assertFalse("can't send it as approved", write(kid, d("requests/1700000000002"), pending + ("status" to "approved")))
        assertFalse("can't change one the parent decided", write(kid, d("requests/done"), pending))
        assertTrue("the parent decides", write(family, d("requests/pend/status"), "approved"))
        assertTrue(write(family, GuardianPaths.device(f, k) + "/requests/pend/status", "denied"))
    }

    @Test fun whatTheParentWritesStillWorks() {
        val f = family; val k = kid
        val own = listOf(
            GuardianPaths.mediaSettings(f, k), GuardianPaths.mediaDeleted(f, k) + "/m1", GuardianPaths.mediaItem(f, k, "m1"), GuardianPaths.mediaBlob(f, k, "m1"),
            GuardianPaths.captureCommand(f, k, "n"), GuardianPaths.captureViewer(f, k), GuardianPaths.captureAck(f, k, "a"), GuardianPaths.captureShot(f, k, "s"), GuardianPaths.captureShotBlob(f, k, "s"),
            GuardianPaths.controlCommand(f, k, "n"), GuardianPaths.controlAck(f, k, "a"), GuardianPaths.recoverySettings(f, k), GuardianPaths.recoveryHistory(f, k), GuardianPaths.recoveryLast(f, k),
            GuardianPaths.wallpaper(f, k), GuardianPaths.wallpaperBlob(f, k), GuardianPaths.policy(f),
            GuardianPaths.safetySettings(f, k), GuardianPaths.safetyDismissal(f, k, "a1"), GuardianPaths.safetyEvent(f, k, "a1"), GuardianPaths.safetyPerms(f, k)
        )
        for (p in own) assertTrue("the parent must be able to write $p", write(family, p))
        assertTrue(read(family, GuardianPaths.safetyDismissed(f, k)))
    }

    @Test fun aRemovedPhoneLosesEverything() {
        val gone = db(members = listOf(sibling))
        assertFalse(write(kid, GuardianPaths.daily(family, kid) + "/$dayKey", mapOf("x" to 1L), gone))
        assertFalse(read(kid, GuardianPaths.policy(family), gone))
        assertFalse(read(kid, GuardianPaths.captureInbox(family, kid), gone))
        assertTrue("the parent still has its data", read(family, GuardianPaths.daily(family, kid), gone))
        assertTrue("a phone that is still paired is unaffected", write(sibling, GuardianPaths.daily(family, sibling) + "/$dayKey", mapOf("x" to 1L), gone))
    }

    // ---------------------------------------------------------------- pairing

    private fun joinAs(uid: String, code: String?, fam: String = family, data: Map<String, Any?> = db()) =
        write(uid, "families/$fam/members/$uid", buildMap<String, Any?> { put("role", "child"); put("joinedAt", now); if (code != null) put("code", code) }, data)

    @Test fun aNewPhoneCanJoinOnlyWithACurrentCodeOfThatFamily() {
        assertTrue(joinAs("C3", "12345678"))
        assertFalse("no code", joinAs("C3", null))
        assertFalse("a code that doesn't exist", joinAs("C3", "99999999"))
        assertFalse("someone else's family", joinAs("C3", "12345678", fam = otherParent))
        val expired = db(mapOf("pairingCodes" to mapOf("12345678" to mapOf("familyId" to family, "expiresAt" to now - 1L))))
        assertFalse("an expired code", joinAs("C3", "12345678", data = expired))
        assertFalse("joining as somebody else", write("C3", "families/$family/members/C4", mapOf("role" to "child", "code" to "12345678")))
    }

    @Test fun afterJoiningAPhoneCanRegisterItselfFieldByFieldButNotReplaceItsRecord() {
        val joined = db(members = listOf(kid, sibling, "C3"))
        for (field in listOf("deviceName", "manufacturer", "androidVersion", "joinedAt", "lastSeen", "monitoringDisclosure"))
            assertTrue(field, write("C3", "families/$family/devices/C3/$field", "x", joined))
        assertFalse(write("C3", "families/$family/devices/C3", mapOf("deviceName" to "x"), joined))
        assertFalse("not joined yet", write(stranger, "families/$family/devices/$stranger/deviceName", "x"))
    }

    @Test fun anInvitationCanOnlyBeCreatedByItsOwnerForAShortTime() {
        val code = "pairingCodes/55555555"
        assertTrue(write(family, code, mapOf("familyId" to family, "createdAt" to now, "expiresAt" to now + 900_000L)))
        assertFalse("for another family", write(stranger, code, mapOf("familyId" to family, "expiresAt" to now + 900_000L)))
        assertFalse("for a very long time", write(family, code, mapOf("familyId" to family, "expiresAt" to now + 86_400_000L)))
        assertFalse("overwriting somebody else's live invitation", write(stranger, "pairingCodes/12345678", mapOf("familyId" to stranger, "expiresAt" to now + 900_000L)))
        assertFalse("deleting somebody else's invitation", delete(stranger, "pairingCodes/12345678"))
        assertTrue("the owner may withdraw it", delete(family, "pairingCodes/12345678"))
        assertTrue("an invitation can be read by whoever has the code while it is live", read(stranger, "pairingCodes/12345678"))
        val expired = db(mapOf("pairingCodes" to mapOf("12345678" to mapOf("familyId" to family, "expiresAt" to now - 1L))))
        assertFalse("but not after it expired", read(stranger, "pairingCodes/12345678", expired))
        assertFalse("and nobody can list them", read(stranger, "pairingCodes"))
    }

    @Test fun theRulesFileHasNoOpenGrants() {
        val text = File(listOf("firebase/database.rules.json", "../firebase/database.rules.json").first { File(it).exists() }).readText()
        assertFalse(Regex("\"\\.(read|write)\"\\s*:\\s*true").containsMatchIn(text))
        assertFalse(text.contains("auth != null\""))
        assertEquals(false, rules.get(".read").asBoolean)
        assertEquals(false, rules.get(".write").asBoolean)
    }
}

/** Evaluates the rules the way Realtime Database does for the expressions in our file; anything else throws so a new kind of rule can't pass unnoticed. */
private class Rules(private val rules: JsonObject, private val now: Long) {
    private class Auth(val uid: String)
    private class Snap(val v: Any?)

    fun allowed(kind: String, uid: String?, p: List<String>, data: Map<String, Any?>, newData: Map<String, Any?>): Boolean {
        val vars = HashMap<String, String>()
        var node: JsonObject = rules
        var i = 0
        while (true) {
            node.get(".$kind")?.let { r ->
                val ok = if (r.asJsonPrimitive.isBoolean) r.asBoolean else Eval(r.asString, uid, vars, data, newData, at(data, p.take(i)), at(newData, p.take(i)), now).bool()
                if (ok) return true
            }
            if (i == p.size) return false
            val key = p[i]
            val next = node.get(key)?.takeIf { it.isJsonObject && !key.startsWith(".") }?.asJsonObject
                ?: node.entrySet().firstOrNull { it.key.startsWith("$") && it.value.isJsonObject }?.also { vars[it.key] = key }?.value?.asJsonObject
                ?: return false
            node = next
            i++
        }
    }

    private class Eval(
        text: String, private val uid: String?, private val vars: Map<String, String>, private val root: Map<String, Any?>, private val rootNew: Map<String, Any?>,
        private val data: Any?, private val newData: Any?, private val now: Long
    ) {
        private val tokens = Regex("""\s*(&&|\|\||==|!=|>=|<=|>|<|!|\+|\(|\)|\.|,|'[^']*'|\d+|[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*)""").findAll(text).map { it.groupValues[1] }.toList()
        private var pos = 0

        init { require(tokens.joinToString("").length >= text.replace(" ", "").length - 0) { "unparsed rule text: $text" } }

        fun bool(): Boolean {
            val v = or()()
            check(pos == tokens.size) { "trailing tokens in rule: ${tokens.drop(pos)}" }
            return v == true
        }

        private fun peek() = tokens.getOrNull(pos)
        private fun eat(t: String) = if (peek() == t) { pos++; true } else false
        private fun expect(t: String) = check(eat(t)) { "expected $t at ${tokens.drop(pos)}" }

        private fun or(): () -> Any? {
            var l = and()
            while (eat("||")) { val a = l; val b = and(); l = { if (a() == true) true else b() == true } }
            return l
        }
        private fun and(): () -> Any? {
            var l = cmp()
            while (eat("&&")) { val a = l; val b = cmp(); l = { if (a() != true) false else b() == true } }
            return l
        }
        private fun cmp(): () -> Any? {
            val l = add()
            val op = peek()
            if (op in setOf("==", "!=", ">", "<", ">=", "<=")) {
                pos++
                val r = add()
                return {
                    val a = norm(l()); val b = norm(r())
                    when (op) {
                        "==" -> a == b
                        "!=" -> a != b
                        else -> if (a is Long && b is Long) when (op) { ">" -> a > b; "<" -> a < b; ">=" -> a >= b; else -> a <= b } else false
                    }
                }
            }
            return l
        }
        private fun add(): () -> Any? {
            var l = unary()
            while (eat("+")) { val a = l; val b = unary(); l = { (norm(a()) as Long) + (norm(b()) as Long) } }
            return l
        }
        private fun unary(): () -> Any? {
            if (eat("!")) { val x = unary(); return { x() != true } }
            return postfix()
        }
        private fun postfix(): () -> Any? {
            var cur = primary()
            while (peek() == ".") {
                pos++
                val name = tokens[pos++]
                val base = cur
                when (name) {
                    "child" -> { expect("("); val k = or(); expect(")"); cur = { val s = base() as Snap; Snap((s.v as? Map<*, *>)?.get(norm(k()).toString())) } }
                    "exists" -> { expect("("); expect(")"); cur = { (base() as Snap).v.let { it != null && !(it is Map<*, *> && it.isEmpty()) } } }
                    "val" -> { expect("("); expect(")"); cur = { (base() as Snap).v.let { if (it is Map<*, *>) null else it } } }
                    "uid" -> cur = { (base() as? Auth)?.uid }
                    else -> error("unsupported member .$name")
                }
            }
            return cur
        }
        private fun primary(): () -> Any? {
            val t = tokens[pos++]
            return when {
                t == "(" -> { val e = or(); expect(")"); e }
                t.startsWith("'") -> { val s = t.substring(1, t.length - 1); { s } }
                t[0].isDigit() -> { val n = t.toLong(); { n } }
                t == "null" -> { { null } }
                t == "true" -> { { true } }
                t == "false" -> { { false } }
                t == "now" -> { { now } }
                t == "auth" -> { { uid?.let { Auth(it) } } }
                t == "root" -> { { Snap(root) } }
                t == "data" -> { { Snap(data) } }
                t == "newData" -> { { Snap(newData) } }
                t.startsWith("$") -> { { vars[t] ?: error("unbound $t") } }
                else -> error("unsupported token $t")
            }
        }
        private fun norm(v: Any?): Any? = when (v) { is Int -> v.toLong(); is Snap -> v.v; else -> v }
    }

    companion object {
        fun at(tree: Any?, p: List<String>): Any? = p.fold(tree) { acc, k -> (acc as? Map<*, *>)?.get(k) }

        /** The tree after writing [value] at [p]; null deletes (and drops containers left empty). */
        @Suppress("UNCHECKED_CAST")
        fun set(tree: Map<String, Any?>, p: List<String>, value: Any?): Map<String, Any?> {
            if (p.isEmpty()) return (value as? Map<String, Any?>) ?: emptyMap()
            val child = tree[p[0]]
            val updated = if (p.size == 1) value else set((child as? Map<String, Any?>) ?: emptyMap(), p.drop(1), value).takeIf { it.isNotEmpty() }
            return if (updated == null) tree - p[0] else tree + (p[0] to updated)
        }
    }
}
