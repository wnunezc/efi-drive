package com.efidriver.icarosnet.services.monitoring

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.models.TripStatus

class TripLifecycleMonitor {
    private val lifecycles = mutableMapOf<String, ListTripLifecycle>()
    private var scanSequence = 0L

    private data class ListTripLifecycle(
        val key: String,
        val firstSeenAt: Long,
        val firstScanId: Long,
        var lastSeenAt: Long,
        var lastScanId: Long,
        var lastBounds: Rect,
        var overlayFirstAddedAt: Long = 0L,
        var overlayLastUpdatedAt: Long = 0L,
        var overlayLastRemovedAt: Long = 0L,
        var clickAt: Long = 0L,
        var lastStatus: TripStatus? = null,
        var lastPreview: Boolean = true
    )

    fun nextScanId(): Long {
        return ++scanSequence
    }

    fun markTripSeen(
        tripKey: String,
        scanId: Long,
        bounds: Rect,
        price: Double,
        pickupDistanceKm: Double,
        activeOverlayCount: Int
    ) {
        val now = nowMs()
        val existing = lifecycles[tripKey]
        if (existing == null) {
            lifecycles[tripKey] = ListTripLifecycle(
                key = tripKey,
                firstSeenAt = now,
                firstScanId = scanId,
                lastSeenAt = now,
                lastScanId = scanId,
                lastBounds = Rect(bounds)
            )
            log(
                "${TraceEvent.TRIP_FIRST_SEEN} key=$tripKey scan=$scanId bounds=$bounds " +
                    "price=$price pickupKm=$pickupDistanceKm activeOverlays=$activeOverlayCount"
            )
            prune(now)
        } else {
            existing.lastSeenAt = now
            existing.lastScanId = scanId
            existing.lastBounds = Rect(bounds)
        }
    }

    fun markOverlayAdded(tripKey: String, result: ProfitabilityResult, bounds: Rect, activeOverlayCount: Int) {
        val now = nowMs()
        val lifecycle = lifecycles[tripKey]
        if (lifecycle != null) {
            lifecycle.overlayFirstAddedAt = lifecycle.overlayFirstAddedAt.takeIf { it > 0L } ?: now
            lifecycle.overlayLastUpdatedAt = now
            lifecycle.lastStatus = result.status
            lifecycle.lastPreview = result.isPreview
            log(
                "${TraceEvent.OVERLAY_ADDED} key=$tripKey scan=${lifecycle.lastScanId} " +
                    "seenToOverlayMs=${now - lifecycle.firstSeenAt} lastSeenToOverlayMs=${now - lifecycle.lastSeenAt} " +
                    "status=${result.status} preview=${result.isPreview} bounds=$bounds active=$activeOverlayCount"
            )
        } else {
            log(
                "${TraceEvent.OVERLAY_ADDED}_WITHOUT_SEEN key=$tripKey status=${result.status} " +
                    "preview=${result.isPreview} bounds=$bounds active=$activeOverlayCount"
            )
        }
    }

    fun markOverlayUpdated(tripKey: String, result: ProfitabilityResult, reason: String, verbose: Boolean) {
        val now = nowMs()
        val lifecycle = lifecycles[tripKey] ?: return
        lifecycle.overlayLastUpdatedAt = now
        lifecycle.lastStatus = result.status
        lifecycle.lastPreview = result.isPreview
        if (verbose) {
            log(
                "${TraceEvent.OVERLAY_UPDATED} key=$tripKey reason=$reason scan=${lifecycle.lastScanId} " +
                    "seenToUpdateMs=${now - lifecycle.firstSeenAt} status=${result.status} preview=${result.isPreview}"
            )
        }
    }

    fun markTripClicked(
        tripKey: String,
        attemptId: Long,
        activeOverlayCount: Int,
        trackedViewCount: Int
    ) {
        val now = nowMs()
        val lifecycle = lifecycles[tripKey]
        if (lifecycle == null) {
            log(
                "${TraceEvent.TRIP_CLICKED}_WITHOUT_LIFECYCLE key=$tripKey attempt=$attemptId " +
                    "activeOverlays=$activeOverlayCount trackedViews=$trackedViewCount"
            )
            return
        }
        lifecycle.clickAt = now
        log(
            "${TraceEvent.TRIP_CLICKED} key=$tripKey attempt=$attemptId " +
                "seenToClickMs=${now - lifecycle.firstSeenAt} overlayAgeMs=${deltaMs(lifecycle.overlayFirstAddedAt, now) ?: "none"} " +
                "lastSeenAgeMs=${now - lifecycle.lastSeenAt} status=${lifecycle.lastStatus ?: "unknown"} " +
                "preview=${lifecycle.lastPreview} activeOverlays=$activeOverlayCount"
        )
    }

    fun markOverlayRemoved(tripKey: String, reason: String) {
        val now = nowMs()
        val lifecycle = lifecycles[tripKey]
        if (lifecycle == null) {
            log("${TraceEvent.OVERLAY_REMOVED}_WITHOUT_LIFECYCLE key=$tripKey reason=$reason")
            return
        }
        lifecycle.overlayLastRemovedAt = now
        log(
            "${TraceEvent.OVERLAY_REMOVED} key=$tripKey reason=$reason " +
                "seenToRemoveMs=${now - lifecycle.firstSeenAt} overlayAgeMs=${deltaMs(lifecycle.overlayFirstAddedAt, now) ?: "none"} " +
                "clickAgeMs=${deltaMs(lifecycle.clickAt, now) ?: "none"} lastSeenAgeMs=${now - lifecycle.lastSeenAt} " +
                "status=${lifecycle.lastStatus ?: "unknown"} preview=${lifecycle.lastPreview}"
        )
    }

    fun ageMs(tripKey: String): Long? {
        return lifecycles[tripKey]?.let { nowMs() - it.firstSeenAt }
    }

    fun logScanEnd(scanId: Long, nodeCount: Int, foundCount: Int, activeOverlayCount: Int, durationMs: Long) {
        log("${TraceEvent.LIST_SCAN_END} scan=$scanId nodes=$nodeCount found=$foundCount active=$activeOverlayCount durationMs=$durationMs")
    }

    private fun prune(now: Long = nowMs()) {
        val maxAgeMs = 10 * 60 * 1000L
        if (lifecycles.size <= 80) return
        val staleKeys = lifecycles
            .filter { (_, lifecycle) -> now - lifecycle.lastSeenAt > maxAgeMs }
            .keys
            .toList()
        staleKeys.forEach { lifecycles.remove(it) }
    }

    private fun deltaMs(from: Long, to: Long): Long? {
        return if (from > 0L && to > 0L) to - from else null
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private companion object {
        private const val TAG = "EfiTripLifecycle"
    }
}
