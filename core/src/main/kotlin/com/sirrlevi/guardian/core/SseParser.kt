package com.sirrlevi.guardian.core

/**
 * Minimal Server-Sent-Events line parser: "event:" and "data:" lines are collected and one
 * event is dispatched when a blank line arrives. Keep-alives and cancel/auth_revoked are
 * reported like any other event so the caller can decide what to do.
 */
class SseParser(private val dispatch: (event: String, data: String) -> Unit) {
    private var event = ""
    private val data = StringBuilder()

    fun feed(line: String) {
        when {
            line.isEmpty() -> {
                if (event.isNotEmpty() || data.isNotEmpty()) dispatch(event.ifEmpty { "message" }, data.toString())
                event = ""
                data.setLength(0)
            }
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trim())
            }
        }
    }
}
