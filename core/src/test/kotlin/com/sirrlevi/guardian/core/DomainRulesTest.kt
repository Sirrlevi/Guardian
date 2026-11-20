package com.sirrlevi.guardian.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {
    @Test fun blocksExactAndSubdomains() {
        assertTrue(DomainRules.isBlockedHost("www.example.com", listOf("example.com")))
        assertTrue(DomainRules.isBlockedHost("shop.example.com", listOf("example.com")))
        assertFalse(DomainRules.isBlockedHost("notexample.com", listOf("example.com")))
    }

    @Test fun supportsUrlPathRulesInManagedBrowser() {
        assertTrue(DomainRules.isBlockedUrl("https://example.com/private/page", emptyList(), listOf("https://example.com/private/*")))
        assertFalse(DomainRules.isBlockedUrl("https://example.com/public", emptyList(), listOf("https://example.com/private/*")))
    }
}
