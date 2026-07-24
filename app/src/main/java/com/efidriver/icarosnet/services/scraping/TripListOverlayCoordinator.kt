package com.efidriver.icarosnet.services.scraping

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
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class TripListOverlayCoordinator(
    private val scope: CoroutineScope,
    private val settingsManager: SettingsManager,
    private val tripListScanner: TripListScanner,
    private val tripActionExecutor: TripActionExecutor,
    private val tripLifecycleMonitor: TripLifecycleMonitor,
    private val listOverlayManager: ListOverlayManager,
    private val tripEvaluationCache: TripEvaluationCache,
    private val isListRenderingBlocked: (AccessibilityNodeInfo) -> Boolean,
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

    private val activeTripJobs = ConcurrentHashMap<String, Job>()
    private var lastScanFinishedAt = 0L
    private val minScanIntervalMs = 60L
    private var lastScanSignature = ""

    fun process(rootNode: AccessibilityNodeInfo?, reason: String): ProcessResult {
        val now = nowMs()
        if (rootNode == null || now - lastScanFinishedAt < minScanIntervalMs) {
            return ProcessResult(scanned = false, rowCount = 0, foundCount = 0, activeOverlayCount = listOverlayManager.activeCount)
        }

        if (isListRenderingBlocked(rootNode)) {
            cancelAllActiveJobs()
            lastScanSignature = "" // Resetear firma al bloquearse
            clearAllOverlays(
                OverlayRemovalRequest(
                    type = OverlayRemovalType.GROUP,
                    reason = "process_blocked_by_detail_or_modal",
                    trigger = "list_scan_blocked"
                )
            )
            return ProcessResult(scanned = false, rowCount = 0, foundCount = 0, activeOverlayCount = listOverlayManager.activeCount)
        }

        val scanId = tripLifecycleMonitor.nextScanId()
        val rows = tripListScanner.scan(rootNode)
        
        // FIRMA ESTRUCTURAL: Comparamos si los viajes y sus posiciones son idénticos al escaneo anterior.
        // Formato: "id1:top1;id2:top2;..."
        val currentSignature = rows.joinToString(";") { "${it.trip.fingerprint}:${it.bounds.top}" }
        if (currentSignature == lastScanSignature && rows.isNotEmpty()) {
            // Si nada ha cambiado, no actualizamos nada para ahorrar CPU y evitar lag del WindowManager.
            lastScanFinishedAt = now
            return ProcessResult(scanned = true, rowCount = rows.size, foundCount = 0, activeOverlayCount = listOverlayManager.activeCount)
        }
        lastScanSignature = currentSignature

        val foundKeysInThisScan = rows.map { it.trip.fingerprint }.toSet()
        val activeKeysBefore = listOverlayManager.keys
        
        val settings = SettingsSnapshot(
            maxPickupDistanceKm = settingsManager.maxPickupDistance,
            minUsdPerKm = settingsManager.minUsdPerKm,
            previewTripDistanceKm = settingsManager.previewTripDistanceKm,
            commissionPercent = settingsManager.commissionPercent
        )

        runtimeTracer.mark("LIST_SCAN_STARTED", "scan=$scanId reason=$reason rows=${rows.size}")

        // 1. REMOCIÓN ATÓMICA O INDIVIDUAL
        if (rows.isEmpty() && activeKeysBefore.isNotEmpty()) {
            // OPTIMIZACIÓN: Si la lista desapareció por completo (ej. abriste tarjeta), 
            // limpiamos todo de un solo golpe atómico para evitar el lag de 500ms.
            cancelAllActiveJobs()
            clearAllOverlays(
                OverlayRemovalRequest(
                    type = OverlayRemovalType.GROUP,
                    reason = "instant_removal_list_disappeared",
                    trigger = "list_scan_empty"
                )
            )
        } else {
            // Remoción individual para cambios normales en la lista
            activeKeysBefore.filter { it !in foundKeysInThisScan }.forEach { staleKey ->
                activeTripJobs[staleKey]?.cancel()
                activeTripJobs.remove(staleKey)
                removeOverlay(
                    staleKey,
                    OverlayRemovalRequest(
                        type = OverlayRemovalType.INDIVIDUAL,
                        reason = "instant_removal_not_in_xml",
                        trigger = "list_scan_diff",
                        targetKey = staleKey
                    )
                )
            }
        }

        // 2. PROCESAMIENTO PARALELO ATÓMICO POR VIAJE
        rows.forEach { row ->
            val tripKey = row.trip.fingerprint
            activeTripJobs[tripKey]?.cancel() // Solo cancelamos el job anterior de ESTE viaje
            
            activeTripJobs[tripKey] = scope.launch(Dispatchers.Main) {
                val rowStartedAt = nowMs()
                val trip = row.trip
                val currentBounds = row.bounds

                tripLifecycleMonitor.markTripSeen(tripKey, scanId, currentBounds, trip.price, trip.pickupDistance, listOverlayManager.activeCount)

                val result = withContext(Dispatchers.Default) {
                    val realSnapshot = tripEvaluationCache.findReal(trip.identity, nowMs())
                    realSnapshot?.let { snapshot ->
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
                }

                if (result.status == TripStatus.RENTABLE) {
                    listOverlayManager.sync(tripKey, currentBounds, result)
                } else if (trip.pickupDistance > 0.05) {
                    tripActionExecutor.hideTrip(row.node, "parallel_not_rentable key=$tripKey")
                }
                
                activeTripJobs.remove(tripKey)
            }
        }

        // 3. LIMPIEZA POR ESTADO VACÍO
        if (foundKeysInThisScan.isEmpty() && listOverlayManager.activeCount > 0) {
            if (tripListScanner.isTripSearchEmptyStateVisible(rootNode)) {
                clearAllOverlays(
                    OverlayRemovalRequest(
                        type = OverlayRemovalType.GROUP,
                        reason = "trip_list_empty_search_state",
                        trigger = "list_scan_empty_confirmed"
                    )
                )
            }
        }

        lastScanFinishedAt = nowMs()
        return ProcessResult(scanned = true, rowCount = rows.size, foundCount = foundKeysInThisScan.size, activeOverlayCount = listOverlayManager.activeCount)
    }

    private fun removeOverlay(tripKey: String, request: OverlayRemovalRequest) {
        val activeBefore = listOverlayManager.activeCount
        val trackedBefore = listOverlayManager.trackedViewCount
        val keysBefore = listOverlayManager.keys.toList()
        logOverlayRemovalTrigger(request, activeBefore, trackedBefore, keysBefore)
        val result = listOverlayManager.remove(tripKey, request.reason)
        logOverlayRemoval(request, result, activeBefore, trackedBefore, keysBefore)
    }

    fun cancelAllActiveJobs() {
        if (activeTripJobs.isNotEmpty()) {
            runtimeTracer.mark("DIAG_COORD_KILL_ALL", "count=${activeTripJobs.size}")
            activeTripJobs.values.forEach { it.cancel() }
            activeTripJobs.clear()
        }
    }
}
