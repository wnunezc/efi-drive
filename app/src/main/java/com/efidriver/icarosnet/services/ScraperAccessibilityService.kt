package com.efidriver.icarosnet.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.efidriver.icarosnet.engine.TripEvaluationCache
import com.efidriver.icarosnet.license.AppLicenseManager
import com.efidriver.icarosnet.vision.RouteLabelDetector
import com.efidriver.icarosnet.vision.RouteLabelOcr
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.engine.SettingsManager
import com.efidriver.icarosnet.services.monitoring.AccessibilityEventSnapshot
import com.efidriver.icarosnet.services.detail.TripDetailFlowStage
import com.efidriver.icarosnet.services.detail.TripFlowContext
import com.efidriver.icarosnet.services.monitoring.DetailFlowTimingTracker
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalEvidence
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalLogger
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalRequest
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalTriggerEvidence
import com.efidriver.icarosnet.services.monitoring.OverlayRemovalType
import com.efidriver.icarosnet.services.monitoring.RuntimeFlowTracer
import com.efidriver.icarosnet.services.overlay.DetailOverlayManager
import com.efidriver.icarosnet.services.monitoring.TripLifecycleMonitor
import com.efidriver.icarosnet.services.ocr.RouteLabelAnalysisCoordinator
import com.efidriver.icarosnet.services.ocr.ScreenshotBitmapExtractor
import com.efidriver.icarosnet.services.profitability.DetailProfitabilityCoordinator
import com.efidriver.icarosnet.services.overlay.ListOverlayManager
import com.efidriver.icarosnet.services.scraping.InDriveDetailDetector
import com.efidriver.icarosnet.services.scraping.PendingTripClick
import com.efidriver.icarosnet.services.scraping.TripActionExecutor
import com.efidriver.icarosnet.services.scraping.TripDetailParser
import com.efidriver.icarosnet.services.scraping.TripListScanner
import com.efidriver.icarosnet.services.scraping.TripListOverlayCoordinator
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@SuppressLint("AccessibilityPolicy")
class ScraperAccessibilityService : AccessibilityService() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var licenseManager: AppLicenseManager
    private lateinit var windowManager: WindowManager
    private val TAG_HUD = "EfiHUD"
    private val TAG_FLOW = "EfiTripFlow"
    private val TAG_RUNTIME_TRACE = "EfiRuntimeTrace"
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val runtimeTracer = RuntimeFlowTracer()
    private val detailFlowTimingTracker = DetailFlowTimingTracker()
    private val tripLifecycleMonitor = TripLifecycleMonitor()
    private val overlayRemovalLogger = OverlayRemovalLogger()
    private val routeLabelAnalysisCoordinator = RouteLabelAnalysisCoordinator()
    private val detailDetector = InDriveDetailDetector()
    private val tripDetailParser = TripDetailParser(detailDetector)
    private val tripListScanner = TripListScanner()
    private val tripActionExecutor = TripActionExecutor { message -> logOverlayTrace { message } }
    private lateinit var listOverlayManager: ListOverlayManager
    private lateinit var detailOverlayManager: DetailOverlayManager
    private lateinit var detailProfitabilityCoordinator: DetailProfitabilityCoordinator
    private lateinit var tripListOverlayCoordinator: TripListOverlayCoordinator

    private var detailFlowStage = TripDetailFlowStage.IDLE
    private var pendingTripClick: PendingTripClick? = null
    private var activeFlowContext: TripFlowContext? = null
    private var screenshotInFlight = false
    private var screenshotInFlightAttempt = 0L
    private var detailFlowAttempt = 0L
    private var lastFlowEventSummary = "none"
    private var lastScreenshotReason = "none"
    private var lastModalSummary = "none"
    private var lastRouteScanSummary = "none"
    private var lastOcrSummary = "none"
    private var routeLabelMonitorActive = false
    private var routeLabelAwaitingMapEventRescan = false
    private var routeLabelScanCount = 0
    private var routeLabelOcrRetryCount = 0
    private var listOverlayRenderingBlocked = false
    private var licenseBlockedActive = false
    private var licenseAllowedCache = false
    private var licenseAllowedCacheAt = 0L
    private var lastCardClickForCleanupAt = 0L
    private var listWithoutModalEventCount = 0
    private var currentEventSnapshot = AccessibilityEventSnapshot.from(null)
    private val tripEvaluationCache = TripEvaluationCache()
    private val maxOcrIncompleteRetries = 6
    private val maxRouteLabelAnalysisMs = 10_000L
    private val modalFallbackCheckIntervalMs = 100L
    private val postDetailListRefreshAttempts = 8
    private val postDetailListRefreshIntervalMs = 100L
    private val detailCardFlowEnabled = false
    private val licenseCheckCacheMs = 1_000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(this)
        licenseManager = AppLicenseManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        listOverlayManager = ListOverlayManager(
            context = this,
            windowManager = windowManager,
            lifecycleMonitor = tripLifecycleMonitor,
            isRenderingBlocked = { isTripDetailFlowActive() || listOverlayRenderingBlocked },
            isVerbose = { isRuntimeVerboseEnabled() },
            trace = { message -> logOverlayTrace { message } }
        )
        detailOverlayManager = DetailOverlayManager(
            context = this,
            windowManager = windowManager,
            trace = { message -> logFlowDebug { message } }
        )
        detailProfitabilityCoordinator = DetailProfitabilityCoordinator(
            settingsManager = settingsManager,
            tripEvaluationCache = tripEvaluationCache,
            tripDetailParser = tripDetailParser,
            nowMs = { nowMs() }
        )
        tripListOverlayCoordinator = TripListOverlayCoordinator(
            settingsManager = settingsManager,
            tripListScanner = tripListScanner,
            tripActionExecutor = tripActionExecutor,
            tripLifecycleMonitor = tripLifecycleMonitor,
            listOverlayManager = listOverlayManager,
            tripEvaluationCache = tripEvaluationCache,
            isListRenderingBlocked = { rootNode ->
                isTripDetailFlowActive() || listOverlayRenderingBlocked || detailDetector.isTripDetailModalVisible(rootNode)
            },
            isVerbose = { isRuntimeVerboseEnabled() },
            clearAllOverlays = { request -> clearAllOverlays(request) },
            logOverlayRemoval = { request, result, activeBefore, trackedBefore, keysBefore ->
                logOverlayRemovalEvidence(request, result, activeBefore, trackedBefore, keysBefore)
            },
            logOverlayRemovalTrigger = { request, activeBefore, trackedBefore, keysBefore ->
                logOverlayRemovalTrigger(request, activeBefore, trackedBefore, keysBefore)
            },
            traceOverlay = { message -> logOverlayTrace { message } },
            traceFlow = { message -> logFlowDebug { message } },
            nowMs = { nowMs() },
            runtimeTracer = runtimeTracer
        )
        Log.d(TAG_HUD, "--- SERVICIO ESTABLE v7.2 - HUD PROTEGIDO ---")
        runtimeTracer.mark("SERVICE_CONNECTED", "package=com.efidriver.icarosnet")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventStartedAt = nowMs()
        val packageName = event?.packageName?.toString() ?: ""
        currentEventSnapshot = AccessibilityEventSnapshot.from(event)
        
        if (packageName == "sinet.startup.inDriver") {
            if (!isLicenseAllowedForService()) {
                return
            }
            if (detailCardFlowEnabled) {
                observeTripDetailFlow(event)
            }
            val afterObserveAt = nowMs()

            // 1. HUD Y FILTRO (Producci??n)
            if (isTripDetailFlowActive() || listOverlayRenderingBlocked) {
                if (listOverlayManager.activeCount > 0 || listOverlayManager.trackedViewCount > 0) {
                    clearAllOverlays(
                        OverlayRemovalRequest(
                            type = OverlayRemovalType.GROUP,
                            reason = "indriver_event_detail_flow_blocked",
                            trigger = "accessibility_event_guard",
                            fallback = "skip_when_no_active_or_tracked_overlays"
                        )
                    )
                } else {
                    logOverlayTrace {
                        "LIST_OVERLAY_CLEAR_SKIPPED reason=detail_flow_blocked active=0 trackedViews=0 stage=$detailFlowStage"
                    }
                }
            } else {
                processMainFlow()
            }
            val afterHudAt = nowMs()
            
            val eventDurationMs = nowMs() - eventStartedAt
            if (isRuntimeVerboseEnabled() && (detailFlowStage != TripDetailFlowStage.IDLE || listOverlayRenderingBlocked || eventDurationMs > 50L)) {
                logFlowDebug {
                    "ACCESS_EVENT_TIMING attempt=$detailFlowAttempt type=${event?.eventType ?: -1} " +
                        "class=${event?.className ?: "none"} stage=$detailFlowStage " +
                        "observe=${afterObserveAt - eventStartedAt}ms hud=${afterHudAt - afterObserveAt}ms " +
                        "total=${eventDurationMs}ms"
                }
            }
        } else {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    if (packageName != "com.efidriver.icarosnet") {
                        resetTripDetailFlow("external_package=$packageName")
                    clearAllOverlays(
                        OverlayRemovalRequest(
                            type = OverlayRemovalType.GROUP,
                            reason = "external_package=$packageName",
                            trigger = "window_state_changed_external_package",
                            fallback = "none"
                        )
                    )
                    detailOverlayManager.remove()
                }
            }
        }
    }

    private fun isLicenseAllowedForService(): Boolean {
        if (!::licenseManager.isInitialized) {
            licenseBlockedActive = false
            return true
        }
        val now = nowMs()
        if (now - licenseAllowedCacheAt <= licenseCheckCacheMs) {
            if (licenseAllowedCache) {
                licenseBlockedActive = false
            }
            return licenseAllowedCache
        }
        val allowed = licenseManager.hasUsableLicense()
        licenseAllowedCache = allowed
        licenseAllowedCacheAt = now
        if (allowed) {
            licenseBlockedActive = false
            return true
        }

        if (!licenseBlockedActive) {
            licenseBlockedActive = true
            Log.w(TAG_HUD, "LICENSE_BLOCKED overlays=${listOverlayManager.activeCount} detailOverlay=${detailOverlayManager.hasOverlay}")
            clearAllOverlays(
                OverlayRemovalRequest(
                    type = OverlayRemovalType.GROUP,
                    reason = "license_not_valid",
                    trigger = "license_guard",
                    fallback = "licenseBlockedActive_prevents_repeated_clear"
                )
            )
            detailOverlayManager.remove()
            resetTripDetailFlow("license_not_valid")
        }
        return false
    }

    private fun observeTripDetailFlow(event: AccessibilityEvent?) {
        if (event == null) return
        lastFlowEventSummary = describeFlowEvent(event)

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val tripClick = extractClickedTrip(event) ?: return
                if (detailFlowStage != TripDetailFlowStage.IDLE && detailFlowStage != TripDetailFlowStage.MAP_CHANGED) {
                    logFlowIncomplete("new_trip_clicked_before_completion")
                }
                detailFlowAttempt += 1
                detailFlowTimingTracker.prune(detailFlowAttempt)
                pendingTripClick = tripClick
                activeFlowContext = TripFlowContext(detailFlowAttempt, tripClick)
                detailFlowTimingTracker.timingFor(detailFlowAttempt).cardClickedAt = nowMs()
                runtimeTracer.mark(
                    "DETAIL_CARD_CLICKED",
                    "attempt=$detailFlowAttempt fp=${tripClick.fingerprint} activeOverlays=${listOverlayManager.activeCount}"
                )
                lastCardClickForCleanupAt = nowMs()
                detailFlowStage = TripDetailFlowStage.CARD_CLICKED
                listOverlayRenderingBlocked = true
                listWithoutModalEventCount = 0
                tripLifecycleMonitor.markTripClicked(
                    tripClick.fingerprint,
                    detailFlowAttempt,
                    listOverlayManager.activeCount,
                    listOverlayManager.trackedViewCount
                )
                Log.d(
                    TAG_RUNTIME_TRACE,
                    "CARD_CLICK_CLEANUP_REQUEST attempt=$detailFlowAttempt trackedOverlays=${listOverlayManager.activeCount} " +
                        "trackedViews=${listOverlayManager.trackedViewCount} fp=${tripClick.fingerprint} price=${tripClick.priceText}"
                )
                clearAllOverlays(
                    OverlayRemovalRequest(
                        type = OverlayRemovalType.GROUP,
                        reason = "card_clicked_opening_detail",
                        trigger = "trip_card_click",
                        fallback = "none",
                        targetKey = tripClick.fingerprint
                    )
                )
                detailOverlayManager.remove()
                lastScreenshotReason = "none"
                lastModalSummary = "none"
                lastRouteScanSummary = "none"
                lastOcrSummary = "none"
                routeLabelScanCount = 0
                routeLabelOcrRetryCount = 0
                logFlowDebug {
                    "CARD_CLICKED attempt=$detailFlowAttempt fp=${tripClick.fingerprint} " +
                        "name=${tripClick.passengerName} pickup=${tripClick.pickupDistanceText} " +
                        "price=${tripClick.priceText} from=${tripClick.pickupAddress} " +
                        "to=${tripClick.destinationAddress}"
                }
                if (!isRuntimeVerboseEnabled()) {
                    Log.d(TAG_FLOW, "CARD_CLICKED attempt=$detailFlowAttempt price=${tripClick.priceText} pickup=${tripClick.pickupDistanceText}")
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (detailFlowStage == TripDetailFlowStage.CARD_CLICKED && isTripDetailWindowEvent(event)) {
                    detailFlowStage = TripDetailFlowStage.MODAL_RENDERED
                    activeFlowContext?.let { detailFlowTimingTracker.timingFor(it.attemptId).modalRenderedAt = nowMs() }
                    logFlowDebug {
                        "MODAL_RENDERED_BY_WINDOW ${flowContextLog()} " +
                            "eventClass=${event.className}"
                    }
                    startRouteLabelMonitor("modal_window_event")
                }

                if (
                    (detailFlowStage == TripDetailFlowStage.CARD_CLICKED || detailFlowStage == TripDetailFlowStage.MODAL_RENDERED) &&
                    detailDetector.isGoogleMapEvent(event)
                ) {
                    activeFlowContext?.let { context ->
                        val timing = detailFlowTimingTracker.timingFor(context.attemptId)
                        if (timing.modalRenderedAt <= 0L) {
                            timing.modalRenderedAt = nowMs()
                        }
                    }
                    detailFlowStage = TripDetailFlowStage.MAP_CHANGED
                    logFlowDebug {
                        "MAP_CHANGED_BY_EVENT ${flowContextLog()} " +
                            "eventClass=${event.className} desc=${event.contentDescription}"
                    }
                    if (routeLabelMonitorActive) {
                        requestRouteLabelScreenshotOnMapEvent("early_map_event")
                    } else {
                        startRouteLabelMonitor("early_google_map_event")
                    }
                }

                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    if (detailFlowStage != TripDetailFlowStage.IDLE && detailFlowStage != TripDetailFlowStage.MAP_CHANGED) {
                        logFlowWaiting("root_unavailable")
                    }
                    return
                }

                if (
                    detailFlowStage == TripDetailFlowStage.CARD_CLICKED &&
                    detailDetector.isTripDetailShellVisible(rootNode)
                ) {
                    listWithoutModalEventCount = 0
                    val mapVisible = detailDetector.isGoogleMapVisible(rootNode)
                    activeFlowContext?.let { context ->
                        val timing = detailFlowTimingTracker.timingFor(context.attemptId)
                        if (timing.modalRenderedAt <= 0L) {
                            timing.modalRenderedAt = nowMs()
                        }
                    }
                    detailFlowStage = if (mapVisible) TripDetailFlowStage.MAP_CHANGED else TripDetailFlowStage.MODAL_RENDERED
                    runtimeTracer.mark(
                        "DETAIL_SHELL_VISIBLE",
                        "attempt=$detailFlowAttempt mapVisible=$mapVisible eventClass=${event.className}"
                    )
                    logFlowDebug {
                        "MODAL_SHELL_VISIBLE ${flowContextLog()} mapVisible=$mapVisible eventClass=${event.className}"
                    }
                    if (mapVisible) {
                        startRouteLabelMonitor("modal_shell_map_visible")
                        return
                    }
                }

                if (detailFlowStage != TripDetailFlowStage.IDLE && tripListScanner.isTripListVisible(rootNode) && !detailDetector.isTripDetailModalVisible(rootNode)) {
                    listWithoutModalEventCount += 1
                    if (
                        !shouldResetDetailFlowFromTripListOnly() &&
                        (listOverlayManager.activeCount > 0 || listOverlayManager.trackedViewCount > 0)
                    ) {
                        clearAllOverlays(
                            OverlayRemovalRequest(
                                type = OverlayRemovalType.GROUP,
                                reason = "list_visible_while_detail_flow_pending",
                                trigger = "detail_flow_list_without_modal",
                                fallback = "resetTripDetailFlow_when_shouldResetDetailFlowFromTripListOnly_true"
                            )
                        )
                    }
                    if (shouldResetDetailFlowFromTripListOnly()) {
                        resetTripDetailFlow("trip_list_visible_without_modal events=$listWithoutModalEventCount")
                    } else {
                        logFlowWaiting("trip_list_visible_ignored_while_detail_flow_active events=$listWithoutModalEventCount")
                    }
                    return
                }

                if (
                    (detailFlowStage == TripDetailFlowStage.CARD_CLICKED || detailFlowStage == TripDetailFlowStage.MODAL_RENDERED) &&
                    detailDetector.isTripDetailModalVisible(rootNode)
                ) {
                    listWithoutModalEventCount = 0
                    val modalTrip = tripDetailParser.extractModalTrip(rootNode)
                    enrichActiveFlowWithModalTrip(modalTrip)
                    lastModalSummary = "visible=true modal=${modalTrip?.fingerprint ?: "unknown"} " +
                        "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                    detailFlowStage = TripDetailFlowStage.MODAL_RENDERED
                    activeFlowContext?.let {
                        val timing = detailFlowTimingTracker.timingFor(it.attemptId)
                        if (timing.modalRenderedAt <= 0L) {
                            timing.modalRenderedAt = nowMs()
                        }
                    }
                    logFlowDebug {
                        "MODAL_RENDERED ${flowContextLog()} " +
                            "modal=${modalTrip?.fingerprint ?: "unknown"} " +
                            "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                    }
                    if (modalTrip != null && modalTrip.fingerprint != pendingTripClick?.fingerprint) {
                        logFlowWaiting("modal_trip_fingerprint_mismatch modal=${modalTrip.fingerprint}")
                    }
                    if (detailDetector.isGoogleMapVisible(rootNode)) {
                        startRouteLabelMonitor("modal_tree_confirmed_map_visible")
                    }
                }

                if (
                    detailFlowStage == TripDetailFlowStage.MODAL_RENDERED &&
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    detailDetector.isTripDetailModalVisible(rootNode)
                ) {
                    listWithoutModalEventCount = 0
                    detailFlowStage = TripDetailFlowStage.DETAIL_CONTENT_CHANGED
                    logFlowDebug {
                        "DETAIL_CONTENT_CHANGED ${flowContextLog()} " +
                            "eventClass=${event.className} modalVisible=true"
                    }
                    startRouteLabelMonitor("detail_content_changed")
                }

                if (
                    (detailFlowStage == TripDetailFlowStage.MODAL_RENDERED || detailFlowStage == TripDetailFlowStage.DETAIL_CONTENT_CHANGED) &&
                    detailDetector.isGoogleMapEvent(event) &&
                    detailDetector.isTripDetailModalVisible(rootNode)
                ) {
                    listWithoutModalEventCount = 0
                    detailFlowStage = TripDetailFlowStage.MAP_CHANGED
                    logFlowDebug {
                        "MAP_CHANGED ${flowContextLog()} " +
                            "eventClass=${event.className} desc=${event.contentDescription}"
                    }
                    requestRouteLabelScreenshotOnMapEvent("map_event")
                }

                if (
                    routeLabelMonitorActive &&
                    routeLabelAwaitingMapEventRescan &&
                    !screenshotInFlight &&
                    detailDetector.isTripDetailShellVisible(rootNode) &&
                    (
                        detailFlowStage == TripDetailFlowStage.MAP_CHANGED ||
                            detailFlowStage == TripDetailFlowStage.MODAL_RENDERED ||
                            detailFlowStage == TripDetailFlowStage.DETAIL_CONTENT_CHANGED
                    )
                ) {
                    requestRouteLabelScreenshotOnMapEvent("content_changed_event")
                }
            }
        }
    }

    private fun isTripDetailWindowEvent(event: AccessibilityEvent): Boolean {
        return detailDetector.isTripDetailWindowEvent(event)
    }

    private fun isTripDetailFlowActive(): Boolean {
        return detailFlowStage != TripDetailFlowStage.IDLE
    }

    private fun isTripDetailFlowComplete(): Boolean {
        return detailFlowStage == TripDetailFlowStage.MAP_CHANGED ||
            detailFlowStage == TripDetailFlowStage.OCR_COMPLETED
    }

    private fun shouldResetDetailFlowFromTripListOnly(): Boolean {
        return when (detailFlowStage) {
            TripDetailFlowStage.IDLE -> false
            TripDetailFlowStage.CARD_CLICKED -> {
                if (routeLabelMonitorActive) {
                    activeFlowElapsedMs() >= maxRouteLabelAnalysisMs
                } else {
                    listWithoutModalEventCount >= 3
                }
            }
            TripDetailFlowStage.OCR_COMPLETED -> true
            TripDetailFlowStage.MODAL_RENDERED,
            TripDetailFlowStage.DETAIL_CONTENT_CHANGED,
            TripDetailFlowStage.MAP_CHANGED -> false
            TripDetailFlowStage.OCR_REQUESTED -> {
                activeFlowElapsedMs() >= maxRouteLabelAnalysisMs ||
                    (routeLabelOcrRetryCount > 0 && isTripListVisibleWithoutDetailModal())
            }
        }
    }

    private fun activeFlowElapsedMs(): Long {
        val attemptId = activeFlowContext?.attemptId ?: return 0L
        return detailFlowTimingTracker.timingFor(attemptId).cardClickedAt
            .takeIf { it > 0L }
            ?.let { nowMs() - it }
            ?: 0L
    }

    private fun isTripDetailModalCurrentlyVisible(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return detailDetector.isTripDetailModalVisible(rootNode)
    }

    private fun isTripListVisibleWithoutDetailModal(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return tripListScanner.isTripListVisible(rootNode) &&
            !detailDetector.isTripDetailModalVisible(rootNode) &&
            !detailDetector.isTripDetailShellVisible(rootNode)
    }

    private fun startRouteLabelMonitor(reason: String) {
        val context = activeFlowContext
        if (routeLabelMonitorActive && context?.attemptId == screenshotInFlightAttempt) {
            runtimeTracer.mark(
                "ROUTE_MONITOR_DUPLICATE_IGNORED",
                "attempt=${context.attemptId} reason=$reason stage=$detailFlowStage screenshotInFlight=$screenshotInFlight"
            )
            return
        }
        listOverlayRenderingBlocked = true
        routeLabelMonitorActive = true
        routeLabelAwaitingMapEventRescan = false
        val startedAt = nowMs()
        context?.let { detailFlowTimingTracker.timingFor(it.attemptId).routeMonitorStartedAt = startedAt }
        logFlowDebug {
            "ROUTE_MONITOR_START ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "modalToMonitor=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).modalRenderedAt, startedAt) } ?: "na"}ms " +
                "activeOverlays=${listOverlayManager.activeCount} overlayGeneration=${listOverlayManager.generation}"
        }
        requestRouteLabelScreenshotWhenOverlaysGone(reason, listOverlayManager.generation)
    }

    private fun scheduleModalVisibilityFallback(attemptId: Long) {
        mainHandler.postDelayed({
            val context = activeFlowContext ?: return@postDelayed
            if (context.attemptId != attemptId || detailFlowStage != TripDetailFlowStage.CARD_CLICKED) {
                return@postDelayed
            }

            val elapsedMs = detailFlowTimingTracker.timingFor(attemptId).cardClickedAt
                .takeIf { it > 0L }
                ?.let { nowMs() - it }
                ?: 0L
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                if (elapsedMs < maxRouteLabelAnalysisMs) {
                    scheduleModalVisibilityFallback(attemptId)
                } else {
                    resetTripDetailFlow("modal_fallback_root_unavailable elapsedMs=$elapsedMs")
                }
                return@postDelayed
            }

            if (detailDetector.isTripDetailShellVisible(rootNode)) {
                listWithoutModalEventCount = 0
                val modalTrip = tripDetailParser.extractModalTrip(rootNode)
                enrichActiveFlowWithModalTrip(modalTrip)
                lastModalSummary = "visible=true modal=${modalTrip?.fingerprint ?: "unknown"} " +
                    "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                val mapVisible = detailDetector.isGoogleMapVisible(rootNode)
                detailFlowStage = if (mapVisible) TripDetailFlowStage.MAP_CHANGED else TripDetailFlowStage.MODAL_RENDERED
                val timing = detailFlowTimingTracker.timingFor(attemptId)
                if (timing.modalRenderedAt <= 0L) {
                    timing.modalRenderedAt = nowMs()
                }
                logFlowDebug {
                    "MODAL_RENDERED_BY_FALLBACK ${flowContextLog(context)} elapsedMs=$elapsedMs " +
                        "modal=${modalTrip?.fingerprint ?: "unknown"} " +
                        "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint} mapVisible=$mapVisible"
                }
                if (!isRuntimeVerboseEnabled()) {
                    Log.d(TAG_FLOW, "MODAL_RENDERED_BY_FALLBACK attempt=$attemptId elapsedMs=$elapsedMs")
                }
                if (mapVisible) {
                    startRouteLabelMonitor("modal_visibility_fallback_map_visible")
                } else {
                    startRouteLabelMonitor("modal_visibility_fallback_shell")
                }
                return@postDelayed
            }

            if (tripListScanner.isTripListVisible(rootNode)) {
                listWithoutModalEventCount += 1
                if (routeLabelMonitorActive && elapsedMs < maxRouteLabelAnalysisMs) {
                    scheduleModalVisibilityFallback(attemptId)
                } else if (shouldResetDetailFlowFromTripListOnly() || elapsedMs >= maxRouteLabelAnalysisMs) {
                    resetTripDetailFlow("modal_not_opened_after_click elapsedMs=$elapsedMs events=$listWithoutModalEventCount")
                } else {
                    scheduleModalVisibilityFallback(attemptId)
                }
                return@postDelayed
            }

            if (elapsedMs < maxRouteLabelAnalysisMs) {
                scheduleModalVisibilityFallback(attemptId)
            } else {
                resetTripDetailFlow("modal_fallback_expired elapsedMs=$elapsedMs")
            }
        }, modalFallbackCheckIntervalMs)
    }

    private fun requestRouteLabelScreenshotWhenOverlaysGone(reason: String, requiredOverlayGeneration: Long) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateRouteLabelScreenshotGate(reason, requiredOverlayGeneration)
        } else {
            mainHandler.post {
                evaluateRouteLabelScreenshotGate(reason, requiredOverlayGeneration)
            }
        }
    }

    private fun evaluateRouteLabelScreenshotGate(reason: String, requiredOverlayGeneration: Long) {
        if (!routeLabelMonitorActive) return
        val context = activeFlowContext
        val postEnteredAt = nowMs()
        context?.let { detailFlowTimingTracker.timingFor(it.attemptId).overlayGatePostEnteredAt = postEnteredAt }
        logFlowDebug {
            "ROUTE_MONITOR_POST_ENTERED ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "monitorToPost=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).routeMonitorStartedAt, postEnteredAt) } ?: "na"}ms " +
                "modalToPost=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).modalRenderedAt, postEnteredAt) } ?: "na"}ms " +
                "activeOverlays=${listOverlayManager.activeCount} overlayGeneration=${listOverlayManager.generation} required=$requiredOverlayGeneration"
        }
        if (listOverlayManager.activeCount > 0 || listOverlayManager.generation < requiredOverlayGeneration) {
            logFlowWaiting(
                "waiting_overlay_clear reason=$reason activeOverlays=${listOverlayManager.activeCount} " +
                    "overlayGeneration=${listOverlayManager.generation} required=$requiredOverlayGeneration"
            )
            mainHandler.postDelayed({
                requestRouteLabelScreenshotWhenOverlaysGone(reason, requiredOverlayGeneration)
            }, 80L)
            return
        }

        context?.let { detailFlowTimingTracker.timingFor(it.attemptId).overlayGatePassedAt = nowMs() }
        logFlowDebug {
            "ROUTE_OVERLAY_GATE_PASSED ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "postToGate=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).overlayGatePostEnteredAt, detailFlowTimingTracker.timingFor(it.attemptId).overlayGatePassedAt) } ?: "na"}ms " +
                "modalToGate=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).modalRenderedAt, detailFlowTimingTracker.timingFor(it.attemptId).overlayGatePassedAt) } ?: "na"}ms"
        }
        requestRouteLabelScreenshotAfterGate(reason)
    }

    private fun requestRouteLabelScreenshotAfterGate(reason: String) {
        if (!routeLabelMonitorActive) return
        val context = activeFlowContext
        val screenshotPostEnteredAt = nowMs()
        context?.let { detailFlowTimingTracker.timingFor(it.attemptId).screenshotPostEnteredAt = screenshotPostEnteredAt }
        logFlowDebug {
            "ROUTE_SCREENSHOT_POST_ENTERED ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "gateToScreenshotPost=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).overlayGatePassedAt, screenshotPostEnteredAt) } ?: "na"}ms " +
                "modalToScreenshotPost=${context?.let { deltaMs(detailFlowTimingTracker.timingFor(it.attemptId).modalRenderedAt, screenshotPostEnteredAt) } ?: "na"}ms"
        }
        requestRouteLabelScreenshot(reason)
    }

    private fun requestRouteLabelScreenshot(reason: String) {
        val context = activeFlowContext ?: pendingTripClick?.let { TripFlowContext(detailFlowAttempt, it) }
        val requestAttemptId = context?.attemptId ?: detailFlowAttempt
        if (screenshotInFlight) {
            if (screenshotInFlightAttempt == requestAttemptId) {
                logFlowWaiting("screenshot_already_in_flight reason=$reason")
                scheduleRouteLabelRescan("screenshot_in_flight")
                return
            }
            runtimeTracer.mark(
                "DETAIL_SCREENSHOT_STALE_IN_FLIGHT_REPLACED",
                "activeAttempt=$requestAttemptId staleAttempt=$screenshotInFlightAttempt reason=$reason"
            )
            screenshotInFlight = false
            screenshotInFlightAttempt = 0L
        }
        routeLabelAwaitingMapEventRescan = false
        screenshotInFlight = true
        screenshotInFlightAttempt = requestAttemptId
        routeLabelScanCount += 1
        lastScreenshotReason = reason
        if (reason.startsWith("visual_rescan_after_ocr_parse_incomplete")) {
            detailOverlayManager.remove()
        }
        context?.let { detailFlowTimingTracker.timingFor(it.attemptId).screenshotRequestedAt = nowMs() }
        runtimeTracer.mark(
            "DETAIL_SCREENSHOT_REQUESTED",
            "attempt=${context?.attemptId ?: detailFlowAttempt} reason=$reason scan=$routeLabelScanCount stage=$detailFlowStage"
        )
        logFlowDebug { "SCREENSHOT_REQUEST ${flowContextLog(context)} scan=$routeLabelScanCount reason=$reason stage=$detailFlowStage" }

        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val callbackStartedAt = nowMs()
                        try {
                            context?.let { detailFlowTimingTracker.timingFor(it.attemptId).screenshotCallbackAt = nowMs() }
                            val bitmap = ScreenshotBitmapExtractor.extract(screenshot)
                            runtimeTracer.end(
                                "DETAIL_SCREENSHOT_CALLBACK_READY",
                                callbackStartedAt,
                                "attempt=$requestAttemptId bitmap=${bitmap != null}"
                            )

                            if (bitmap == null) {
                                logFlowDebug { "SCREENSHOT_EMPTY ${flowContextLog(context)}" }
                                logFlowIncomplete("screenshot_empty", context)
                                return
                            }
                            if (isContextStale(context)) {
                                runtimeTracer.mark(
                                    "DETAIL_SCREENSHOT_STALE_IGNORED",
                                    "attempt=$requestAttemptId activeAttempt=${activeFlowContext?.attemptId ?: "none"} reason=$reason"
                                )
                                bitmap.recycle()
                                return
                            }

                            context?.let { detailFlowTimingTracker.timingFor(it.attemptId).scanStartedAt = nowMs() }
                            val roiStartedAt = nowMs()
                            val detectionSummary = routeLabelAnalysisCoordinator.detect(bitmap)
                            val result = detectionSummary.detection
                            context?.let { detailFlowTimingTracker.timingFor(it.attemptId).scanCompletedAt = nowMs() }
                            runtimeTracer.end(
                                "DETAIL_ROI_DETECT_COMPLETED",
                                roiStartedAt,
                                "attempt=${context?.attemptId ?: detailFlowAttempt} scan=$routeLabelScanCount visible=${result.routeLabelsVisible} " +
                                    "blue=${result.bluePixelCount} green=${result.greenPixelCount}"
                            )
                            lastRouteScanSummary = detectionSummary.text
                            logFlowDebug {
                                "ROUTE_LABEL_SCAN ${flowContextLog(context)} scan=$routeLabelScanCount " +
                                    "afterFlowReset=${isContextStale(context)} $lastRouteScanSummary"
                            }

                            var bitmapOwnedByOcr = false
                            if (result.routeLabelsVisible) {
                                context?.let { detailFlowTimingTracker.timingFor(it.attemptId).labelsVisibleAt = nowMs() }
                                runtimeTracer.mark(
                                    "DETAIL_ROUTE_LABELS_VISIBLE",
                                    "attempt=${context?.attemptId ?: detailFlowAttempt} scan=$routeLabelScanCount"
                                )
                                if (!isContextStale(context)) {
                                    routeLabelMonitorActive = false
                                    detailFlowStage = TripDetailFlowStage.MAP_CHANGED
                                }
                                logFlowDebug { "ROUTE_LABELS_VISIBLE ${flowContextLog(context)} afterFlowReset=${isContextStale(context)}" }
                                bitmapOwnedByOcr = true
                                requestRouteLabelOcr(bitmap, result, context)
                            } else {
                                logFlowWaiting("route_labels_not_visible_after_screenshot", context)
                                if (!isContextStale(context)) {
                                    scheduleRouteLabelRescan("route_labels_not_visible")
                                }
                            }
                            if (!bitmapOwnedByOcr) {
                                bitmap.recycle()
                            }
                        } catch (e: Exception) {
                            logFlowIncomplete("screenshot_analysis_failed message=${e.message}", context)
                            Log.e(TAG_FLOW, "SCREENSHOT_ANALYSIS_FAILED ${flowContextLog(context)} ${e.message}", e)
                        } finally {
                            if (screenshotInFlightAttempt == requestAttemptId) {
                                screenshotInFlight = false
                                screenshotInFlightAttempt = 0L
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (screenshotInFlightAttempt == requestAttemptId) {
                            screenshotInFlight = false
                            screenshotInFlightAttempt = 0L
                        }
                        if (isContextStale(context)) {
                            runtimeTracer.mark(
                                "DETAIL_SCREENSHOT_FAILURE_STALE_IGNORED",
                                "attempt=$requestAttemptId code=$errorCode activeAttempt=${activeFlowContext?.attemptId ?: "none"}"
                            )
                            return
                        }
                        logFlowDebug { "SCREENSHOT_FAILED ${flowContextLog(context)} code=$errorCode" }
                        logFlowIncomplete("screenshot_failed code=$errorCode", context)
                    }
                }
            )
        } catch (e: SecurityException) {
            if (screenshotInFlightAttempt == requestAttemptId) {
                screenshotInFlight = false
                screenshotInFlightAttempt = 0L
            }
            logFlowIncomplete("screenshot_not_allowed message=${e.message}", context)
            Log.e(TAG_FLOW, "SCREENSHOT_NOT_ALLOWED ${e.message}", e)
        }
    }

    private fun requestRouteLabelOcr(
        bitmap: Bitmap,
        detection: RouteLabelDetector.DetectionResult,
        context: TripFlowContext?
    ) {
        if (!isContextStale(context)) {
            routeLabelMonitorActive = false
            detailFlowStage = TripDetailFlowStage.OCR_REQUESTED
        }
        logFlowDebug {
            "ROUTE_LABEL_OCR_REQUEST ${flowContextLog(context)} afterFlowReset=${isContextStale(context)} " +
                "blueBox=${detection.pickupLabel?.bounds ?: "none"} greenBox=${detection.destinationLabel?.bounds ?: "none"}"
        }
        context?.let { detailFlowTimingTracker.timingFor(it.attemptId).ocrRequestedAt = nowMs() }
        runtimeTracer.mark(
            "DETAIL_OCR_REQUESTED",
            "attempt=${context?.attemptId ?: detailFlowAttempt} blueBox=${detection.pickupLabel?.bounds ?: "none"} " +
                "greenBox=${detection.destinationLabel?.bounds ?: "none"}"
        )

        routeLabelAnalysisCoordinator.recognize(
            bitmap = bitmap,
            detection = detection,
            onSuccess = ocrSuccess@{ result ->
                bitmap.recycle()
                if (isContextStale(context)) {
                    runtimeTracer.mark(
                        "DETAIL_OCR_STALE_IGNORED",
                        "attempt=${context?.attemptId ?: detailFlowAttempt} activeAttempt=${activeFlowContext?.attemptId ?: "none"} " +
                            "complete=${result.complete}"
                    )
                    return@ocrSuccess
                }
                context?.let { detailFlowTimingTracker.timingFor(it.attemptId).ocrCompletedAt = nowMs() }
                runtimeTracer.mark(
                    "DETAIL_OCR_COMPLETED",
                    "attempt=${context?.attemptId ?: detailFlowAttempt} complete=${result.complete} " +
                        "pickupKm=${result.pickup.distanceKm} destinationKm=${result.destination.distanceKm}"
                )
                lastOcrSummary = routeLabelAnalysisCoordinator.summarizeOcr(result)
                logFlowDebug {
                    "ROUTE_LABEL_OCR_RESULT ${flowContextLog(context)} afterFlowReset=${isContextStale(context)} $lastOcrSummary"
                }

                if (result.complete) {
                    context?.let { detailFlowTimingTracker.timingFor(it.attemptId).profitabilityStartedAt = nowMs() }
                    val profitabilityStartedAt = nowMs()
                    val profitability = detailProfitabilityCoordinator.calculateRealProfitability(context?.trip, result)
                    context?.let { detailFlowTimingTracker.timingFor(it.attemptId).profitabilityCompletedAt = nowMs() }
                    runtimeTracer.end(
                        "DETAIL_PROFITABILITY_CALCULATED",
                        profitabilityStartedAt,
                        "attempt=${context?.attemptId ?: detailFlowAttempt} available=${profitability != null} " +
                            "status=${profitability?.status ?: "none"} usdKm=${profitability?.expectedUsdPerKm ?: "none"}"
                    )
                    if (profitability != null) {
                        if (detailProfitabilityCoordinator.storeRealTripEvaluation(context?.trip, result, profitability)) {
                            Log.d(TAG_FLOW, "REAL_EVALUATION_STORED attempt=${context?.attemptId ?: detailFlowAttempt} status=${profitability.status} usdKm=${profitability.expectedUsdPerKm}")
                        }
                    }
                    if (!isContextStale(context)) {
                        detailFlowStage = TripDetailFlowStage.OCR_COMPLETED
                        if (profitability != null) {
                            if (!isTripDetailModalCurrentlyVisible()) {
                                logFlowWaiting("modal_tree_unavailable_at_ocr_complete_showing_overlay_from_active_context", context)
                            }
                            val offerStartedAt = nowMs()
                            context?.let { detailFlowTimingTracker.timingFor(it.attemptId).offerStartedAt = offerStartedAt }
                            val offerRecommendation = findOfferRecommendation(context, result, profitability)
                            context?.let { detailFlowTimingTracker.timingFor(it.attemptId).offerCompletedAt = nowMs() }
                            runtimeTracer.end(
                                "DETAIL_OFFER_RECOMMENDATION_COMPLETED",
                                offerStartedAt,
                                "attempt=${context?.attemptId ?: detailFlowAttempt} offer=${offerRecommendation?.price ?: "none"}"
                            )
                            showDetailProfitabilityOverlay(
                                profitability,
                                result,
                                offerRecommendation
                            )
                        }
                    }
                    logFlowDebug {
                        "ROUTE_LABEL_METRICS_READY ${flowContextLog(context)} afterFlowReset=${isContextStale(context)} " +
                            "pickupMin=${result.pickup.minutes} pickupKm=${result.pickup.distanceKm} " +
                            "destinationMin=${result.destination.minutes} destinationKm=${result.destination.distanceKm} " +
                            "profitability=${profitability?.expectedUsdPerKm ?: "none"} profit=${profitability?.trueProfit ?: "none"}"
                    }
                } else {
                    retryRouteLabelOcrAfterIncompleteResult(context, result)
                }
            },
            onFailure = ocrFailure@{ exception ->
                bitmap.recycle()
                if (isContextStale(context)) {
                    runtimeTracer.mark(
                        "DETAIL_OCR_FAILURE_STALE_IGNORED",
                        "attempt=${context?.attemptId ?: detailFlowAttempt} activeAttempt=${activeFlowContext?.attemptId ?: "none"} " +
                            "message=${exception.message}"
                    )
                    return@ocrFailure
                }
                lastOcrSummary = "failed=${exception.message}"
                logFlowIncomplete("ocr_failed message=${exception.message}", context)
            }
        )
    }

    private fun retryRouteLabelOcrAfterIncompleteResult(
        context: TripFlowContext?,
        result: RouteLabelOcr.OcrResult
    ) {
        if (isContextStale(context)) {
            runtimeTracer.mark(
                "DETAIL_OCR_RETRY_STALE_IGNORED",
                "attempt=${context?.attemptId ?: detailFlowAttempt} activeAttempt=${activeFlowContext?.attemptId ?: "none"}"
            )
            return
        }
        val analysisElapsedMs = context
            ?.let { detailFlowTimingTracker.timingFor(it.attemptId).cardClickedAt }
            ?.takeIf { it > 0L }
            ?.let { nowMs() - it }
            ?: 0L
        val modalVisible = isTripDetailModalCurrentlyVisible()
        val listVisibleWithoutModal = isTripListVisibleWithoutDetailModal()

        if (
            isContextStale(context) ||
            (!modalVisible && listVisibleWithoutModal) ||
            routeLabelOcrRetryCount >= maxOcrIncompleteRetries ||
            analysisElapsedMs >= maxRouteLabelAnalysisMs
        ) {
            logFlowIncomplete(
                "ocr_parse_incomplete retries=$routeLabelOcrRetryCount elapsedMs=$analysisElapsedMs " +
                    "pickupComplete=${result.pickup.complete} pickupRaw=${result.pickup.rawText} " +
                    "pickupMin=${result.pickup.minutes} pickupKm=${result.pickup.distanceKm} " +
                    "destinationComplete=${result.destination.complete} destinationRaw=${result.destination.rawText} " +
                    "destinationMin=${result.destination.minutes} destinationKm=${result.destination.distanceKm}",
                context
            )
            resetTripDetailFlow(
                "ocr_parse_incomplete_after_retries retries=$routeLabelOcrRetryCount " +
                    "elapsedMs=$analysisElapsedMs modalVisible=$modalVisible listVisibleWithoutModal=$listVisibleWithoutModal"
            )
            return
        }

        routeLabelOcrRetryCount += 1
        routeLabelMonitorActive = true
        detailFlowStage = TripDetailFlowStage.MAP_CHANGED
        logFlowDebug {
            "ROUTE_LABEL_OCR_RETRY ${flowContextLog(context)} retry=$routeLabelOcrRetryCount " +
                "pickupComplete=${result.pickup.complete} destinationComplete=${result.destination.complete} " +
                "lastOcr=[$lastOcrSummary]"
        }
        scheduleRouteLabelRescan("ocr_parse_incomplete_retry_$routeLabelOcrRetryCount")
    }

    private fun findOfferRecommendation(
        context: TripFlowContext?,
        ocrResult: RouteLabelOcr.OcrResult,
        currentProfitability: ProfitabilityResult
    ): DetailProfitabilityCoordinator.OfferRecommendation? {
        val trip = context?.trip ?: return null
        val visibleOfferPriceTexts = rootInActiveWindow
            ?.takeIf { detailDetector.isTripDetailModalVisible(it) }
            ?.let { tripDetailParser.extractOfferPriceTexts(it, trip.priceText) }
            .orEmpty()
        return detailProfitabilityCoordinator.findOfferRecommendation(trip, ocrResult, currentProfitability, visibleOfferPriceTexts)
    }

    private fun scheduleRouteLabelRescan(reason: String) {
        if (!routeLabelMonitorActive) return
        routeLabelAwaitingMapEventRescan = true
        logFlowDebug { "ROUTE_LABEL_RESCAN_ARMED_WAITING_EVENT ${flowContextLog()} reason=$reason" }
    }

    private fun requestRouteLabelScreenshotOnMapEvent(reason: String) {
        if (!routeLabelMonitorActive || !routeLabelAwaitingMapEventRescan || screenshotInFlight) return
        routeLabelAwaitingMapEventRescan = false
        logFlowDebug { "ROUTE_LABEL_RESCAN_BY_MAP_EVENT ${flowContextLog()} reason=$reason" }
        requestRouteLabelScreenshotWhenOverlaysGone(
            "visual_rescan_after_$reason",
            listOverlayManager.generation
        )
    }

    private fun extractClickedTrip(event: AccessibilityEvent): PendingTripClick? {
        if (tripDetailParser.isTripClickFromIgnoredSurface(event.source)) {
            logFlowDebug { "CARD_CLICK_IGNORED ignoredSurface event=${describeFlowEvent(event)}" }
            return null
        }
        return tripDetailParser.extractClickedTrip(event)
    }

    private fun enrichActiveFlowWithModalTrip(modalTrip: PendingTripClick?) {
        val currentContext = activeFlowContext ?: return
        val trip = modalTrip ?: return
        if (trip.fingerprint != currentContext.fingerprint) return

        pendingTripClick = trip
        activeFlowContext = currentContext.copy(trip = trip)
        logFlowDebug {
            "MODAL_TRIP_ENRICHED ${flowContextLog(activeFlowContext)} offers=${trip.offerPriceTexts.joinToString("|")}"
        }
    }

    private fun resetTripDetailFlow(reason: String) {
        if (detailFlowStage == TripDetailFlowStage.IDLE && pendingTripClick == null) return
        if (!isTripDetailFlowComplete()) {
            logFlowIncomplete("reset_before_route_labels_visible reason=$reason")
        }
        routeLabelMonitorActive = false
        routeLabelAwaitingMapEventRescan = false
        screenshotInFlight = false
        screenshotInFlightAttempt = 0L
        detailOverlayManager.remove()
        if (isRuntimeVerboseEnabled()) {
            Log.d(TAG_FLOW, "FLOW_RESET attempt=$detailFlowAttempt reason=$reason previousStage=$detailFlowStage pending=${pendingTripClick?.fingerprint ?: "none"}")
        } else {
            Log.d(TAG_FLOW, "FLOW_RESET attempt=$detailFlowAttempt reason=$reason previousStage=$detailFlowStage")
        }
        detailFlowStage = TripDetailFlowStage.IDLE
        pendingTripClick = null
        activeFlowContext = null
        listOverlayRenderingBlocked = false
        listWithoutModalEventCount = 0
        processMainFlow("post_detail_reset_immediate reason=$reason")
        schedulePostDetailListRefresh(postDetailListRefreshAttempts)
    }

    private fun schedulePostDetailListRefresh(remainingAttempts: Int) {
        if (remainingAttempts <= 0) return
        mainHandler.postDelayed({
            if (detailFlowStage != TripDetailFlowStage.IDLE || listOverlayRenderingBlocked) {
                logOverlayTrace {
                    "POST_DETAIL_REFRESH_SKIPPED remaining=$remainingAttempts stage=$detailFlowStage " +
                        "blocked=$listOverlayRenderingBlocked active=${listOverlayManager.activeCount}"
                }
                return@postDelayed
            }
            val result = processMainFlowWithResult("post_detail_refresh remaining=$remainingAttempts")
            if ((result?.foundCount ?: 0) > 0 && (result?.activeOverlayCount ?: 0) > 0) {
                logOverlayTrace {
                    "POST_DETAIL_REFRESH_COMPLETED remaining=$remainingAttempts found=${result?.foundCount} active=${result?.activeOverlayCount}"
                }
                return@postDelayed
            }
            schedulePostDetailListRefresh(remainingAttempts - 1)
        }, postDetailListRefreshIntervalMs)
    }

    private fun logFlowWaiting(reason: String, context: TripFlowContext? = activeFlowContext) {
        logFlowDebug {
            "FLOW_WAITING ${flowContextLog(context)} reason=$reason " +
                "stage=$detailFlowStage afterFlowReset=${isContextStale(context)} " +
                "lastEvent=[$lastFlowEventSummary] lastScreenshotReason=$lastScreenshotReason " +
                "lastModal=[$lastModalSummary] lastRouteScan=[$lastRouteScanSummary] lastOcr=[$lastOcrSummary]"
        }
    }

    private fun logFlowIncomplete(reason: String, context: TripFlowContext? = activeFlowContext) {
        if (isRuntimeVerboseEnabled()) {
            Log.w(
                TAG_RUNTIME_TRACE,
                "FLOW_INCOMPLETE ${flowContextLog(context)} reason=$reason " +
                    "failedAt=$detailFlowStage afterFlowReset=${isContextStale(context)} " +
                    "livePending=${pendingTripClick?.fingerprint ?: "none"} screenshotInFlight=$screenshotInFlight lastEvent=[$lastFlowEventSummary] " +
                    "lastScreenshotReason=$lastScreenshotReason lastModal=[$lastModalSummary] " +
                    "lastRouteScan=[$lastRouteScanSummary] lastOcr=[$lastOcrSummary]"
            )
        } else {
            Log.w(
                TAG_FLOW,
                "FLOW_INCOMPLETE attempt=${context?.attemptId ?: detailFlowAttempt} reason=$reason " +
                    "failedAt=$detailFlowStage afterFlowReset=${isContextStale(context)}"
            )
        }
    }

    private fun isRuntimeVerboseEnabled(): Boolean {
        return Log.isLoggable(TAG_RUNTIME_TRACE, Log.VERBOSE)
    }

    private inline fun logFlowDebug(message: () -> String) {
        if (isRuntimeVerboseEnabled()) {
            Log.v(TAG_RUNTIME_TRACE, message())
        }
    }

    private inline fun logOverlayTrace(message: () -> String) {
        if (isRuntimeVerboseEnabled()) {
            Log.v(TAG_RUNTIME_TRACE, "OVERLAY_TRACE ${message()}")
        }
    }

    private fun compactKeys(keys: Collection<String>): String {
        if (keys.isEmpty()) return "[]"
        val visibleKeys = keys.take(5).joinToString(",")
        return if (keys.size > 5) "[$visibleKeys,+${keys.size - 5}]" else "[$visibleKeys]"
    }

    private fun flowContextLog(context: TripFlowContext? = activeFlowContext): String {
        return "attempt=${context?.attemptId ?: detailFlowAttempt} pending=${context?.fingerprint ?: "none"}"
    }

    private fun isContextStale(context: TripFlowContext?): Boolean {
        return context != null && activeFlowContext?.attemptId != context.attemptId
    }

    private fun describeFlowEvent(event: AccessibilityEvent): String {
        val eventText = event.text
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString("|")
            .ifEmpty { "none" }
        return "type=${event.eventType} class=${event.className ?: "none"} " +
            "contentDesc=${event.contentDescription ?: "none"} text=$eventText"
    }

    private fun processMainFlow(reason: String = "accessibility_event") {
        if (!::settingsManager.isInitialized) {
            logOverlayTrace { "LIST_SCAN_SKIPPED reason=$reason cause=settings_not_initialized" }
            return
        }
        tripListOverlayCoordinator.process(rootInActiveWindow, reason)
    }

    private fun processMainFlowWithResult(reason: String): TripListOverlayCoordinator.ProcessResult? {
        if (!::settingsManager.isInitialized) {
            logOverlayTrace { "LIST_SCAN_SKIPPED reason=$reason cause=settings_not_initialized" }
            return null
        }
        return tripListOverlayCoordinator.process(rootInActiveWindow, reason)
    }

    private fun showDetailProfitabilityOverlay(
        result: ProfitabilityResult,
        ocrResult: RouteLabelOcr.OcrResult,
        offerRecommendation: DetailProfitabilityCoordinator.OfferRecommendation?
    ) {
        val overlayStartedAt = nowMs()
        activeFlowContext?.let { context ->
            detailFlowTimingTracker.timingFor(context.attemptId).overlayShowStartedAt = overlayStartedAt
        }
        if (detailOverlayManager.showProfitability(result, ocrResult, offerRecommendation?.price)) {
            activeFlowContext?.let { context ->
                val timing = detailFlowTimingTracker.timingFor(context.attemptId)
                timing.overlayShownAt = nowMs()
                runtimeTracer.end(
                    "DETAIL_OVERLAY_PAINTED",
                    overlayStartedAt,
                    "attempt=${context.attemptId} status=${result.status} clickToOverlay=${deltaMs(timing.cardClickedAt, timing.overlayShownAt) ?: "na"}ms"
                )
                logFlowDebug { "FLOW_TIMING ${flowContextLog(context)} ${detailFlowTimingTracker.format(timing)}" }
            }
            Log.d(
                TAG_FLOW,
                if (isRuntimeVerboseEnabled()) {
                    "DETAIL_PROFITABILITY_OVERLAY_SHOWN ${flowContextLog()} " +
                        "status=${result.status} usdKm=${result.expectedUsdPerKm} profit=${result.trueProfit} " +
                        "pickupKm=${result.pickupDistanceKm} totalKm=${result.totalDistanceKm} " +
                        "offer=${offerRecommendation?.price ?: "none"}"
                } else {
                    "DETAIL_PROFITABILITY_OVERLAY_SHOWN attempt=$detailFlowAttempt status=${result.status} usdKm=${result.expectedUsdPerKm}"
                }
            )
        }
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun deltaMs(from: Long, to: Long): Long? {
        return if (from > 0L && to > 0L) to - from else null
    }

    private fun clearAllOverlays(request: OverlayRemovalRequest) {
        val activeBefore = listOverlayManager.activeCount
        val trackedBefore = listOverlayManager.trackedViewCount
        val keysBefore = listOverlayManager.keys.toList()
        logOverlayRemovalTrigger(request, activeBefore, trackedBefore, keysBefore)
        val result = listOverlayManager.clear(
            reason = request.reason,
            stage = detailFlowStage.toString(),
            blocked = listOverlayRenderingBlocked,
            sinceCardClickMs = deltaMs(lastCardClickForCleanupAt, nowMs())
        )
        if (::tripListOverlayCoordinator.isInitialized) {
            tripListOverlayCoordinator.clearMissingState()
        }
        logOverlayRemovalEvidence(
            request = request,
            removedKeys = result.removedKeys,
            durationMs = result.durationMs,
            activeBefore = activeBefore,
            trackedBefore = trackedBefore,
            keysBefore = keysBefore
        )
        if (result.removedCount > 0) {
            Log.d(
                TAG_RUNTIME_TRACE,
                "OVERLAYS_CLEARED attempt=$detailFlowAttempt reason=${request.reason} removed=${result.removedCount} " +
                    "generation=${result.generation} keys=${compactKeys(result.removedKeys)} durationMs=${result.durationMs}"
            )
        }
    }

    private fun logOverlayRemovalTrigger(
        request: OverlayRemovalRequest,
        activeBefore: Int,
        trackedBefore: Int,
        keysBefore: List<String>
    ) {
        val visualState = captureOverlayRemovalVisualState()
        overlayRemovalLogger.logTrigger(
            OverlayRemovalTriggerEvidence(
                wallTimeMs = System.currentTimeMillis(),
                elapsedMs = nowMs(),
                type = request.type,
                reason = request.reason,
                trigger = request.trigger,
                fallback = request.fallback,
                eventType = currentEventSnapshot.eventType,
                eventClass = currentEventSnapshot.eventClass,
                packageName = currentEventSnapshot.packageName,
                detailFlowStage = detailFlowStage.toString(),
                listOverlayRenderingBlocked = listOverlayRenderingBlocked,
                tripDetailFlowActive = isTripDetailFlowActive(),
                tripListVisible = visualState.tripListVisible,
                detailModalVisible = visualState.detailModalVisible,
                detailShellVisible = visualState.detailShellVisible,
                rowsFound = request.rowsFound,
                activeBefore = activeBefore,
                trackedViewsBefore = trackedBefore,
                keysBefore = keysBefore,
                keysFound = request.keysFound,
                targetKey = request.targetKey
            )
        )
    }

    private fun logOverlayRemovalEvidence(
        request: OverlayRemovalRequest,
        result: ListOverlayManager.RemovalResult,
        activeBefore: Int,
        trackedBefore: Int,
        keysBefore: List<String>
    ) {
        logOverlayRemovalEvidence(
            request = request,
            removedKeys = result.removedKeys,
            durationMs = result.durationMs,
            activeBefore = activeBefore,
            trackedBefore = trackedBefore,
            keysBefore = keysBefore
        )
    }

    private fun logOverlayRemovalEvidence(
        request: OverlayRemovalRequest,
        removedKeys: List<String>,
        durationMs: Long?,
        activeBefore: Int,
        trackedBefore: Int,
        keysBefore: List<String>
    ) {
        val visualState = captureOverlayRemovalVisualState()
        overlayRemovalLogger.log(
            OverlayRemovalEvidence(
                wallTimeMs = System.currentTimeMillis(),
                elapsedMs = nowMs(),
                type = request.type,
                reason = request.reason,
                trigger = request.trigger,
                fallback = request.fallback,
                eventType = currentEventSnapshot.eventType,
                eventClass = currentEventSnapshot.eventClass,
                packageName = currentEventSnapshot.packageName,
                detailFlowStage = detailFlowStage.toString(),
                listOverlayRenderingBlocked = listOverlayRenderingBlocked,
                tripDetailFlowActive = isTripDetailFlowActive(),
                tripListVisible = visualState.tripListVisible,
                detailModalVisible = visualState.detailModalVisible,
                detailShellVisible = visualState.detailShellVisible,
                rowsFound = request.rowsFound,
                activeBefore = activeBefore,
                trackedViewsBefore = trackedBefore,
                activeAfter = listOverlayManager.activeCount,
                trackedViewsAfter = listOverlayManager.trackedViewCount,
                keysBefore = keysBefore,
                keysFound = request.keysFound,
                removedKeys = removedKeys,
                targetKey = request.targetKey,
                durationMs = durationMs
            )
        )
    }

    private data class OverlayRemovalVisualState(
        val tripListVisible: Boolean?,
        val detailModalVisible: Boolean?,
        val detailShellVisible: Boolean?
    )

    private fun captureOverlayRemovalVisualState(): OverlayRemovalVisualState {
        val rootNode = rootInActiveWindow ?: return OverlayRemovalVisualState(
            tripListVisible = null,
            detailModalVisible = null,
            detailShellVisible = null
        )
        return OverlayRemovalVisualState(
            tripListVisible = runCatching { tripListScanner.isTripListVisible(rootNode) }.getOrNull(),
            detailModalVisible = runCatching { detailDetector.isTripDetailModalVisible(rootNode) }.getOrNull(),
            detailShellVisible = runCatching { detailDetector.isTripDetailShellVisible(rootNode) }.getOrNull()
        )
    }

    override fun onDestroy() {
        clearAllOverlays(
            OverlayRemovalRequest(
                type = OverlayRemovalType.GROUP,
                reason = "service_destroy",
                trigger = "accessibility_service_destroy",
                fallback = "none"
            )
        )
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}
}

