package com.efidriver.icarosnet.services.scraping

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.engine.SettingsManager
import com.efidriver.icarosnet.engine.TripEvaluationCache
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalRequest
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalType
import com.efidriver.icarosnet.services.monitoring.RuntimeFlowTracer
import com.efidriver.icarosnet.services.monitoring.TripLifecycleMonitor
import com.efidriver.icarosnet.services.overlay.ListOverlayManager
import kotlin.math.max
import kotlin.math.min

class TripListOverlayCoordinator(
    private val settingsManager: SettingsManager,
    private val tripListScanner: TripListScanner,
    private val tripActionExecutor: TripActionExecutor,
    private val tripLifecycleMonitor: TripLifecycleMonitor,
    private val listOverlayManager: ListOverlayManager,
    private val tripEvaluationCache: TripEvaluationCache,
    private val isListRenderingBlocked: (AccessibilityNodeInfo) -> Boolean,
    private val isVerbose: () -> Boolean,
    private val clearAllOverlays: (OverlayRemovalRequest) -> Unit,
    private val logOverlayRemoval: (OverlayRemovalRequest, ListOverlayManager.RemovalResult, Int, Int, List<String>) -> Unit,
    private val logOverlayRemovalTrigger: (OverlayRemovalRequest, Int, Int, List<String>) -> Unit,
    private val traceOverlay: (String) -> Unit,
    private val traceFlow: (String) -> Unit,
    private val nowMs: () -> Long,
    private val runtimeTracer: RuntimeFlowTracer
) {
    data class ProcessResult(
        val scanned: Boolean,
        val rowCount: Int,
        val foundCount: Int,
        val activeOverlayCount: Int
    )

    private data class SettingsSnapshot(
        val maxPickupDistanceKm: Double,
        val minUsdPerKm: Double,
        val previewTripDistanceKm: Double,
        val commissionPercent: Double
    )

    private val overlayMissingScanCounts = mutableMapOf<String, Int>()

    fun process(rootNode: AccessibilityNodeInfo?, reason: String): ProcessResult {
        if (rootNode == null) {
            traceOverlay("LIST_SCAN_SKIPPED reason=$reason cause=root_null active=${listOverlayManager.activeCount}")
            return ProcessResult(scanned = false, rowCount = 0, foundCount = 0, activeOverlayCount = listOverlayManager.activeCount)
        }

        if (isListRenderingBlocked(rootNode)) {
            clearAllOverlays(
                OverlayRemovalRequest(
                    type = OverlayRemovalType.GROUP,
                    reason = "process_blocked_by_detail_or_modal",
                    trigger = "list_scan_blocked",
                    fallback = "none"
                )
            )
            traceFlow("LIST_OVERLAY_RENDER_BLOCKED reason=$reason activeOverlays=${listOverlayManager.activeCount}")
            return ProcessResult(scanned = false, rowCount = 0, foundCount = 0, activeOverlayCount = listOverlayManager.activeCount)
        }

        val processStartedAt = nowMs()
        val scanId = tripLifecycleMonitor.nextScanId()
        val scannerStartedAt = nowMs()
        val rows = tripListScanner.scan(rootNode)
        val scannerDurationMs = nowMs() - scannerStartedAt
        val settings = SettingsSnapshot(
            maxPickupDistanceKm = settingsManager.maxPickupDistance,
            minUsdPerKm = settingsManager.minUsdPerKm,
            previewTripDistanceKm = settingsManager.previewTripDistanceKm,
            commissionPercent = settingsManager.commissionPercent
        )
        val foundKeysInThisScan = mutableSetOf<String>()
        val foundBoundsInThisScan = mutableMapOf<String, Rect>()
        runtimeTracer.mark(
            "LIST_SCAN_STARTED",
            "scan=$scanId reason=$reason active=${listOverlayManager.activeCount}"
        )
        traceOverlay(
            "LIST_SCAN_START reason=$reason nodes=${rows.size} active=${listOverlayManager.activeCount} " +
                "activeKeys=${compactKeys(listOverlayManager.keys)}"
        )

        for (row in rows) {
            val rowStartedAt = nowMs()
            val trip = row.trip
            val tripKey = trip.fingerprint
            foundKeysInThisScan.add(tripKey)
            overlayMissingScanCounts.remove(tripKey)
            val currentBounds = row.bounds
            foundBoundsInThisScan[tripKey] = Rect(currentBounds)
            tripLifecycleMonitor.markTripSeen(
                tripKey,
                scanId,
                currentBounds,
                trip.price,
                trip.pickupDistance,
                listOverlayManager.activeCount
            )

            val calcStartedAt = nowMs()
            val realSnapshot = tripEvaluationCache.findReal(trip.identity, nowMs())
            val result = realSnapshot?.let { snapshot ->
                ProfitabilityEngine.calculate(
                    tripPrice = trip.price,
                    pickupDistanceKm = snapshot.pickupDistanceKm,
                    tripDistanceKm = snapshot.tripDistanceKm,
                    maxPickupDistanceKm = settings.maxPickupDistanceKm,
                    minUsdPerKm = settings.minUsdPerKm,
                    commissionPercent = settings.commissionPercent,
                    isPreview = false
                )
            } ?: ProfitabilityEngine.calculate(
                tripPrice = trip.price,
                pickupDistanceKm = trip.pickupDistance,
                tripDistanceKm = settings.previewTripDistanceKm,
                maxPickupDistanceKm = settings.maxPickupDistanceKm,
                minUsdPerKm = settings.minUsdPerKm,
                commissionPercent = settings.commissionPercent,
                isPreview = true
            )
            val calcDurationMs = nowMs() - calcStartedAt

            if (realSnapshot != null) {
                val syncStartedAt = nowMs()
                val synced = listOverlayManager.sync(tripKey, currentBounds, result)
                val syncDurationMs = nowMs() - syncStartedAt
                traceFlow(
                    "LIST_REAL_OVERLAY_USED key=$tripKey status=${result.status} " +
                        "storedPrice=${realSnapshot.price} currentPrice=${trip.price} " +
                        "pickupKm=${realSnapshot.pickupDistanceKm} tripKm=${realSnapshot.tripDistanceKm}"
                )
                runtimeTracer.mark(
                    "LIST_ROW_PROCESSED",
                    "scan=$scanId key=$tripKey mode=real status=${result.status} synced=$synced " +
                        "calcMs=$calcDurationMs syncMs=$syncDurationMs totalRowMs=${nowMs() - rowStartedAt}"
                )
            } else if (result.status != TripStatus.RENTABLE) {
                traceOverlay(
                    "LIST_OVERLAY_SKIP_HIDE_PREVIEW_NON_RENTABLE key=$tripKey status=${result.status} " +
                        "price=${trip.price} pickup=${trip.pickupDistance} foundKeys=${compactKeys(foundKeysInThisScan)}"
                )
                if (trip.pickupDistance > 0.05) {
                    val hideStartedAt = nowMs()
                    Log.d(
                        TAG_RUNTIME_TRACE,
                        "PREVIEW_HIDE_REQUEST key=$tripKey scan=$scanId seenToHide=${tripLifecycleMonitor.ageMs(tripKey)}ms " +
                            "price=${trip.price} pickup=${trip.pickupDistance} status=${result.status}"
                    )
                    tripActionExecutor.hideTrip(row.node, "preview_not_rentable key=$tripKey")
                    runtimeTracer.end(
                        "LIST_ROW_HIDE_COMPLETED",
                        hideStartedAt,
                        "scan=$scanId key=$tripKey status=${result.status} calcMs=$calcDurationMs totalRowMs=${nowMs() - rowStartedAt}"
                    )
                }
            } else {
                val syncStartedAt = nowMs()
                val synced = listOverlayManager.sync(tripKey, currentBounds, result)
                val syncDurationMs = nowMs() - syncStartedAt
                runtimeTracer.mark(
                    "LIST_ROW_PROCESSED",
                    "scan=$scanId key=$tripKey mode=preview status=${result.status} synced=$synced " +
                        "calcMs=$calcDurationMs syncMs=$syncDurationMs totalRowMs=${nowMs() - rowStartedAt}"
                )
            }
        }

        val keysToRemove = listOverlayManager.keys.filter { !foundKeysInThisScan.contains(it) }
        traceOverlay(
            "LIST_SCAN_END nodes=${rows.size} found=${foundKeysInThisScan.size} " +
                "foundKeys=${compactKeys(foundKeysInThisScan)} staleKeys=${compactKeys(keysToRemove)} " +
                "activeBeforeRemove=${compactKeys(listOverlayManager.keys)}"
        )
        if (isVerbose()) {
            tripLifecycleMonitor.logScanEnd(scanId, rows.size, foundKeysInThisScan.size, listOverlayManager.activeCount, nowMs() - processStartedAt)
        }
        runtimeTracer.end(
            "LIST_SCAN_COMPLETED",
            processStartedAt,
            "scan=$scanId reason=$reason rows=${rows.size} found=${foundKeysInThisScan.size} " +
                "scannerMs=$scannerDurationMs active=${listOverlayManager.activeCount} stale=${keysToRemove.size}"
        )
        if (foundKeysInThisScan.isEmpty() && listOverlayManager.activeCount > 0) {
            if (tripListScanner.isTripSearchEmptyStateVisible(rootNode)) {
                val activeBeforeClear = listOverlayManager.activeCount
                clearAllOverlays(
                    OverlayRemovalRequest(
                        type = OverlayRemovalType.GROUP,
                        reason = "trip_list_empty_search_state",
                        trigger = "list_scan_empty_confirmed",
                        fallback = "empty_scan_ignored_unless_search_state_visible",
                        rowsFound = rows.size,
                        keysFound = foundKeysInThisScan.toList()
                    )
                )
                traceOverlay("LIST_SCAN_EMPTY_CONFIRMED_SEARCH_STATE nodes=${rows.size} activeBefore=$activeBeforeClear")
                return ProcessResult(scanned = true, rowCount = rows.size, foundCount = foundKeysInThisScan.size, activeOverlayCount = listOverlayManager.activeCount)
            }
            traceOverlay(
                "LIST_SCAN_EMPTY_IGNORED_FOR_STALE nodes=${rows.size} active=${listOverlayManager.activeCount} " +
                    "activeKeys=${compactKeys(listOverlayManager.keys)}"
            )
            return ProcessResult(scanned = true, rowCount = rows.size, foundCount = foundKeysInThisScan.size, activeOverlayCount = listOverlayManager.activeCount)
        }
        keysToRemove.forEach { tripKey ->
            val staleBounds = listOverlayManager.lastBounds(tripKey)
            val overlappingBounds = staleBounds?.let { bounds ->
                foundBoundsInThisScan.entries.firstOrNull { hasMeaningfulVerticalOverlap(bounds, it.value) }
            }
            if (overlappingBounds != null) {
                removeOverlay(
                    tripKey,
                    OverlayRemovalRequest(
                        type = OverlayRemovalType.INDIVIDUAL,
                        reason = "stale_bounds_overlap_live_row foundKey=${overlappingBounds.key} " +
                            "staleBounds=$staleBounds liveBounds=${overlappingBounds.value}",
                        trigger = "list_scan_stale_overlap",
                        fallback = "bypass_three_scan_confirmation_to_prevent_overlap",
                        rowsFound = rows.size,
                        keysFound = foundKeysInThisScan.toList(),
                        targetKey = tripKey
                    )
                )
                return@forEach
            }
            val missingCount = (overlayMissingScanCounts[tripKey] ?: 0) + 1
            overlayMissingScanCounts[tripKey] = missingCount
            if (missingCount >= STALE_OVERLAY_REMOVAL_CONFIRMATIONS) {
                removeOverlay(
                    tripKey,
                    OverlayRemovalRequest(
                        type = OverlayRemovalType.INDIVIDUAL,
                        reason = "not_found_in_current_list_scan confirmed=$missingCount found=${foundKeysInThisScan.size}",
                        trigger = "list_scan_stale_confirmed",
                        fallback = "required_absent_scans=$STALE_OVERLAY_REMOVAL_CONFIRMATIONS",
                        rowsFound = rows.size,
                        keysFound = foundKeysInThisScan.toList(),
                        targetKey = tripKey
                    )
                )
                overlayMissingScanCounts.remove(tripKey)
            } else {
                traceOverlay(
                    "LIST_OVERLAY_STALE_PENDING key=$tripKey missingCount=$missingCount " +
                        "required=$STALE_OVERLAY_REMOVAL_CONFIRMATIONS found=${foundKeysInThisScan.size}"
                )
            }
        }
        return ProcessResult(scanned = true, rowCount = rows.size, foundCount = foundKeysInThisScan.size, activeOverlayCount = listOverlayManager.activeCount)
    }

    fun clearMissingState() {
        overlayMissingScanCounts.clear()
    }

    private fun removeOverlay(tripKey: String, request: OverlayRemovalRequest) {
        val activeBefore = listOverlayManager.activeCount
        val trackedBefore = listOverlayManager.trackedViewCount
        val keysBefore = listOverlayManager.keys.toList()
        logOverlayRemovalTrigger(request, activeBefore, trackedBefore, keysBefore)
        val result = listOverlayManager.remove(tripKey, request.reason)
        logOverlayRemoval(request, result, activeBefore, trackedBefore, keysBefore)
        overlayMissingScanCounts.remove(tripKey)
    }

    private fun hasMeaningfulVerticalOverlap(first: Rect, second: Rect): Boolean {
        val overlap = min(first.bottom, second.bottom) - max(first.top, second.top)
        if (overlap <= 0) return false

        val minHeight = min(first.height(), second.height()).coerceAtLeast(1)
        val requiredOverlap = max(32, (minHeight * 0.35f).toInt())
        return overlap >= requiredOverlap
    }

    private fun compactKeys(keys: Collection<String>): String {
        if (keys.isEmpty()) return "[]"
        val visibleKeys = keys.take(5).joinToString(",")
        return if (keys.size > 5) "[$visibleKeys,+${keys.size - 5}]" else "[$visibleKeys]"
    }

    private companion object {
        private const val TAG_RUNTIME_TRACE = "EfiRuntimeTrace"
        private const val STALE_OVERLAY_REMOVAL_CONFIRMATIONS = 3
    }
}
