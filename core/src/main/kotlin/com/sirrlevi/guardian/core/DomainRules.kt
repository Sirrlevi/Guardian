package com.sirrlevi.guardian.core

import java.net.URI
import java.util.Locale

object DomainRules {
    fun hostOf(value: String): String? {
        val candidate = value.trim()
        if (candidate.isBlank()) return null
        return try {
            val uri = URI(if ("://" in candidate) candidate else "https://$candidate")
            uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.trimEnd('.')
        } catch (_: Exception) { null }
    }

    fun isBlockedHost(host: String?, rules: Collection<String>): Boolean {
        val normalizedHost = host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.trimEnd('.')
            ?: return false
        return rules.asSequence()
            .mapNotNull { hostOf(it) ?: it.trim().lowercase(Locale.ROOT).removePrefix("www.").takeIf(String::isNotBlank) }
            .any { rule -> normalizedHost == rule || normalizedHost.endsWith(".$rule") }
    }

    fun isBlockedUrl(url: String, domainRules: Collection<String>, urlRules: Collection<String>): Boolean {
        val normalized = url.trim()
        if (isBlockedHost(hostOf(normalized), domainRules)) return true
        return urlRules.asSequence().map(String::trim).filter(String::isNotBlank).any { raw ->
            val prefix = raw.removeSuffix("*")
            normalized.equals(prefix, ignoreCase = true) ||
                (raw.endsWith("*") && normalized.startsWith(prefix, ignoreCase = true))
        }
    }
}
