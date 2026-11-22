package com.sirrlevi.guardian.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    @Test fun readsVersionCodeFromReleaseTag() {
        assertEquals(42, UpdateRules.versionFromTag("guardian-v1.0.42"))
        assertEquals(7, UpdateRules.versionFromTag("guardian-v1.0.7 "))
        assertNull(UpdateRules.versionFromTag("latest"))
    }

    @Test fun onlyStrictlyNewerReleasesAreUpdates() {
        assertTrue(UpdateRules.isNewer(30, 29))
        assertFalse(UpdateRules.isNewer(29, 29))
        assertFalse(UpdateRules.isNewer(28, 29))
    }

    @Test fun mapsEachAppToItsOwnAsset() {
        assertEquals("Guardian-Parent.apk", UpdateRules.assetNameFor(UpdateRules.PARENT_PACKAGE))
        assertEquals("Guardian-Child.apk", UpdateRules.assetNameFor(UpdateRules.CHILD_PACKAGE))
        assertNull(UpdateRules.assetNameFor("com.example.other"))
    }

    @Test fun computesDownloadProgress() {
        assertEquals(0, UpdateRules.percent(10, 0))
        assertEquals(50, UpdateRules.percent(512, 1024))
        assertEquals(100, UpdateRules.percent(2048, 1024))
    }

    @Test fun formatsSizes() {
        assertEquals("512 B", UpdateRules.formatSize(512))
        assertEquals("2 KB", UpdateRules.formatSize(2048))
        assertEquals("1.5 MB", UpdateRules.formatSize(1_572_864))
    }

    @Test fun explainsCommonGithubFailures() {
        assertTrue(UpdateRules.explainHttpFailure(401).contains("token"))
        assertTrue(UpdateRules.explainHttpFailure(404).contains("release"))
        assertTrue(UpdateRules.explainHttpFailure(500).contains("500"))
    }

    // ---- release list handling (the "latest release has no Guardian-Parent.apk" bug)

    private fun release(tag: String, vararg assets: String, prerelease: Boolean = false, draft: Boolean = false): String {
        val items = assets.mapIndexed { i, name -> """{"id": ${tag.hashCode().toLong().and(0xffff) * 10 + i}, "name": "$name", "size": 1000}""" }
        return """{"tag_name": "$tag", "name": "Release $tag", "body": "notes", "html_url": "https://github.com/x/y/releases/tag/$tag",
            "prerelease": $prerelease, "draft": $draft, "assets": [${items.joinToString(",")}]}"""
    }

    private fun list(vararg releases: String) = "[" + releases.joinToString(",") + "]"

    @Test fun skipsReleasesWithoutThisAppsApk() {
        // Exactly the failing situation: newest "latest" release only carries debug APKs.
        val json = list(
            release("guardian-v1.0.9", "Guardian-Parent-debug.apk", "Guardian-Child-debug.apk"),
            release("guardian-v1.0.45", "Guardian-Parent.apk", "Guardian-Child.apk")
        )
        val picked = UpdateRules.pickRelease(json, "Guardian-Parent.apk")
        assertEquals("guardian-v1.0.45", picked?.tag)
        assertEquals(45, picked?.versionCode)
        assertEquals("Guardian-Parent.apk", picked?.assetName)
    }

    @Test fun picksTheHighestVersionNotTheFirstInTheList() {
        val json = list(
            release("guardian-v1.0.7", "Guardian-Child.apk"),
            release("guardian-v1.0.52", "Guardian-Child.apk"),
            release("guardian-v1.0.40", "Guardian-Child.apk")
        )
        assertEquals(52, UpdateRules.pickRelease(json, "Guardian-Child.apk")?.versionCode)
    }

    @Test fun ignoresPrereleasesAndDrafts() {
        val json = list(
            release("guardian-debug-v1.0.60", "Guardian-Parent.apk", prerelease = true),
            release("guardian-v1.0.61", "Guardian-Parent.apk", draft = true),
            release("guardian-v1.0.30", "Guardian-Parent.apk")
        )
        assertEquals("guardian-v1.0.30", UpdateRules.pickRelease(json, "Guardian-Parent.apk")?.tag)
    }

    @Test fun returnsNullWhenNothingIsInstallable() {
        val json = list(release("guardian-v1.0.9", "Guardian-Parent-debug.apk"), release("notes-only"))
        assertNull(UpdateRules.pickRelease(json, "Guardian-Parent.apk"))
        assertNull(UpdateRules.pickRelease("[]", "Guardian-Parent.apk"))
    }

    @Test fun parentAndChildEachGetTheirOwnApk() {
        val json = list(release("guardian-v1.0.5", "Guardian-Parent.apk"), release("guardian-v1.0.6", "Guardian-Child.apk"))
        assertEquals(5, UpdateRules.pickRelease(json, "Guardian-Parent.apk")?.versionCode)
        assertEquals(6, UpdateRules.pickRelease(json, "Guardian-Child.apk")?.versionCode)
    }

    @Test fun rejectsAnswersThatAreNotAReleaseList() {
        assertTrue(runCatching { UpdateRules.pickRelease("""{"message": "Not Found"}""", "Guardian-Parent.apk") }.isFailure)
        assertTrue(runCatching { UpdateRules.pickRelease("<html>", "Guardian-Parent.apk") }.isFailure)
    }

    // ---- signing compatibility

    @Test fun sameCertificateIsCompatibleRegardlessOfCase() {
        assertTrue(UpdateRules.sameSigners(setOf("AB12CD"), setOf("ab12cd")))
    }

    @Test fun differentCertificatesAreNotCompatible() {
        assertFalse(UpdateRules.sameSigners(setOf("ab12cd"), setOf("ff00ee")))
    }

    @Test fun unreadableCertificatesLeaveTheDecisionToAndroid() {
        assertTrue(UpdateRules.sameSigners(emptySet(), setOf("ab12cd")))
        assertTrue(UpdateRules.sameSigners(setOf("ab12cd"), emptySet()))
    }

    @Test fun buildsADirectBrowserDownloadLink() {
        assertEquals(
            "https://github.com/Sirrlevi/Guardian/releases/download/guardian-v1.0.45/Guardian-Parent.apk",
            UpdateRules.directDownloadUrl("guardian-v1.0.45", "Guardian-Parent.apk")
        )
    }

    @Test fun publicRepoFailureMessagesDoNotDemandAToken() {
        assertFalse(UpdateRules.explainHttpFailure(404, hadToken = false).startsWith("Add"))
        assertTrue(UpdateRules.explainHttpFailure(403).contains("rate limit"))
    }
}
