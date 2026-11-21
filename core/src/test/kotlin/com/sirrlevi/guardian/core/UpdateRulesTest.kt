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
}
