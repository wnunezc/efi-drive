package com.efidriver.icarosnet.services.monitoring

import android.os.SystemClock
import android.util.Log

class RuntimeFlowTracer(
    private val tag: String = TAG_RUNTIME_TRACE,
    private val isEnabled: () -> Boolean = { Log.isLoggable(TAG_RUNTIME_TRACE, Log.VERBOSE) },
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var eventCount = 0
    private var lastEventWindowMs = 0L

    fun now(): Long = nowMs()

    fun recordEvent() {
        val now = nowMs()
        if (now - lastEventWindowMs > 1000) {
            if (eventCount > 0) {
                mark("DIAG_EVENT_DENSITY", "eventsPerSec=$eventCount")
            }
            eventCount = 0
            lastEventWindowMs = now
        }
        eventCount++
    }

    fun mark(event: String, details: String = "") {
        if (!isEnabled()) return
        Log.v(tag, buildString {
            append(event)
            append(" atMs=")
            append(nowMs())
            if (details.isNotBlank()) {
                append(' ')
                append(details)
            }
        })
    }

    fun end(event: String, startedAt: Long, details: String = "") {
        if (!isEnabled()) return
        Log.v(tag, buildString {
            append(event)
            append(" durationMs=")
            append(nowMs() - startedAt)
            if (details.isNotBlank()) {
                append(' ')
                append(details)
            }
        })
    }

    fun <T> measure(event: String, details: () -> String = { "" }, block: () -> T): T {
        val startedAt = nowMs()
        return try {
            block()
        } finally {
            end(event, startedAt, details())
        }
    }

    private companion object {
        private const val TAG_RUNTIME_TRACE = "EfiRuntimeTrace"
    }
}
