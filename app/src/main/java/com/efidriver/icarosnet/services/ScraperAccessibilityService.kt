package com.efidriver.icarosnet.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.efidriver.icarosnet.engine.TripEvaluationCache
import com.efidriver.icarosnet.engine.TripEvaluationKind
import com.efidriver.icarosnet.engine.TripEvaluationSnapshot
import com.efidriver.icarosnet.license.AppLicenseManager
import com.efidriver.icarosnet.vision.RouteLabelDetector
import com.efidriver.icarosnet.vision.RouteLabelOcr
import com.efidriver.icarosnet.models.Trip
import com.efidriver.icarosnet.models.TripIdentity
import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.engine.SettingsManager
import java.util.Collections
import java.util.IdentityHashMap
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
    private val TAG_LIFECYCLE = "EfiTripLifecycle"
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val activeOverlays = mutableMapOf<String, OverlayRecord>()
    private val activeListOverlayViews = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
    private val listTripLifecycles = mutableMapOf<String, ListTripLifecycle>()
    private val overlayMissingScanCounts = mutableMapOf<String, Int>()
    private var detailProfitabilityOverlay: View? = null

    private enum class DetailFlowStage {
        IDLE,
        CARD_CLICKED,
        MODAL_RENDERED,
        DETAIL_CONTENT_CHANGED,
        MAP_CHANGED,
        OCR_REQUESTED,
        OCR_COMPLETED
    }

    private data class PendingTripClick(
        val passengerName: String,
        val pickupDistanceText: String,
        val priceText: String,
        val pickupAddress: String,
        val destinationAddress: String,
        val offerPriceTexts: List<String> = emptyList()
    ) {
        val identity: TripIdentity = TripIdentity.from(passengerName, pickupAddress, destinationAddress)
        val fingerprint: String = identity.bestKey
    }

    private data class OfferRecommendation(
        val price: Double,
        val profitability: ProfitabilityResult
    )

    private data class TripFlowContext(
        val attemptId: Long,
        val trip: PendingTripClick
    ) {
        val fingerprint: String
            get() = trip.fingerprint
    }

    private data class FlowTiming(
        var cardClickedAt: Long = 0L,
        var modalRenderedAt: Long = 0L,
        var routeMonitorStartedAt: Long = 0L,
        var overlayGatePostEnteredAt: Long = 0L,
        var overlayGatePassedAt: Long = 0L,
        var screenshotPostEnteredAt: Long = 0L,
        var screenshotRequestedAt: Long = 0L,
        var screenshotCallbackAt: Long = 0L,
        var scanStartedAt: Long = 0L,
        var scanCompletedAt: Long = 0L,
        var labelsVisibleAt: Long = 0L,
        var ocrRequestedAt: Long = 0L,
        var ocrCompletedAt: Long = 0L,
        var overlayShownAt: Long = 0L
    )

    private var detailFlowStage = DetailFlowStage.IDLE
    private var pendingTripClick: PendingTripClick? = null
    private var activeFlowContext: TripFlowContext? = null
    private var screenshotInFlight = false
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
    private var overlayClearGeneration = 0L
    private var listOverlayRenderingBlocked = false
    private var licenseBlockedActive = false
    private var lastCardClickForCleanupAt = 0L
    private var listWithoutModalEventCount = 0
    private var listScanSequence = 0L
    private val flowTimings = mutableMapOf<Long, FlowTiming>()
    private val tripEvaluationCache = TripEvaluationCache()
    private val maxOcrIncompleteRetries = 6
    private val maxRouteLabelAnalysisMs = 10_000L
    private val modalFallbackCheckIntervalMs = 250L
    private val postDetailListRefreshAttempts = 30
    private val postDetailListRefreshIntervalMs = 100L
    private val staleOverlayRemovalConfirmations = 3

    private data class OverlayRecord(
        val view: View,
        val textContainer: LinearLayout,
        var lastBounds: Rect
    )

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(this)
        licenseManager = AppLicenseManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG_HUD, "--- SERVICIO ESTABLE v7.2 - HUD PROTEGIDO ---")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventStartedAt = nowMs()
        val packageName = event?.packageName?.toString() ?: ""
        
        if (packageName == "sinet.startup.inDriver") {
            if (!isLicenseAllowedForService()) {
                return
            }
            observeTripDetailFlow(event)
            val afterObserveAt = nowMs()

            // 1. HUD Y FILTRO (Producci??n)
            if (isTripDetailFlowActive() || listOverlayRenderingBlocked) {
                clearAllOverlays("indriver_event_detail_flow_blocked")
            } else {
                processMainFlow()
            }
            val afterHudAt = nowMs()
            
            val eventDurationMs = nowMs() - eventStartedAt
            if (isRuntimeVerboseEnabled() && (detailFlowStage != DetailFlowStage.IDLE || listOverlayRenderingBlocked || eventDurationMs > 50L)) {
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
                    clearAllOverlays("external_package=$packageName")
                    removeDetailProfitabilityOverlay()
                }
            }
        }
    }

    private fun isLicenseAllowedForService(): Boolean {
        if (!::licenseManager.isInitialized || licenseManager.hasUsableLicense()) {
            licenseBlockedActive = false
            return true
        }

        if (!licenseBlockedActive) {
            licenseBlockedActive = true
            Log.w(TAG_HUD, "LICENSE_BLOCKED overlays=${activeOverlays.size} detailOverlay=${detailProfitabilityOverlay != null}")
            clearAllOverlays("license_not_valid")
            removeDetailProfitabilityOverlay()
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
                if (detailFlowStage != DetailFlowStage.IDLE && detailFlowStage != DetailFlowStage.MAP_CHANGED) {
                    logFlowIncomplete("new_trip_clicked_before_completion")
                }
                detailFlowAttempt += 1
                pruneFlowTimings()
                pendingTripClick = tripClick
                activeFlowContext = TripFlowContext(detailFlowAttempt, tripClick)
                timingFor(detailFlowAttempt).cardClickedAt = nowMs()
                lastCardClickForCleanupAt = nowMs()
                detailFlowStage = DetailFlowStage.CARD_CLICKED
                listOverlayRenderingBlocked = true
                listWithoutModalEventCount = 0
                markTripClicked(tripClick.fingerprint)
                Log.d(
                    TAG_RUNTIME_TRACE,
                    "CARD_CLICK_CLEANUP_REQUEST attempt=$detailFlowAttempt trackedOverlays=${activeOverlays.size} " +
                        "trackedViews=${activeListOverlayViews.size} fp=${tripClick.fingerprint} price=${tripClick.priceText}"
                )
                clearAllOverlays("card_clicked_opening_detail")
                removeDetailProfitabilityOverlay()
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
                startRouteLabelMonitor("card_click_visual_probe")
                scheduleModalVisibilityFallback(detailFlowAttempt)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (detailFlowStage == DetailFlowStage.CARD_CLICKED && isTripDetailWindowEvent(event)) {
                    detailFlowStage = DetailFlowStage.MODAL_RENDERED
                    activeFlowContext?.let { timingFor(it.attemptId).modalRenderedAt = nowMs() }
                    logFlowDebug {
                        "MODAL_RENDERED_BY_WINDOW ${flowContextLog()} " +
                            "eventClass=${event.className}"
                    }
                    startRouteLabelMonitor("modal_window_event")
                }

                if (
                    (detailFlowStage == DetailFlowStage.CARD_CLICKED || detailFlowStage == DetailFlowStage.MODAL_RENDERED) &&
                    isGoogleMapEvent(event)
                ) {
                    detailFlowStage = DetailFlowStage.MAP_CHANGED
                    logFlowDebug {
                        "MAP_CHANGED_BY_EVENT ${flowContextLog()} " +
                            "eventClass=${event.className} desc=${event.contentDescription}"
                    }
                    requestRouteLabelScreenshotOnMapEvent("early_map_event")
                }

                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    if (detailFlowStage != DetailFlowStage.IDLE && detailFlowStage != DetailFlowStage.MAP_CHANGED) {
                        logFlowWaiting("root_unavailable")
                    }
                    return
                }

                if (detailFlowStage != DetailFlowStage.IDLE && isTripListVisible(rootNode) && !isTripDetailModalVisible(rootNode)) {
                    listWithoutModalEventCount += 1
                    if (!shouldResetDetailFlowFromTripListOnly()) {
                        clearAllOverlays("list_visible_while_detail_flow_pending")
                    }
                    if (shouldResetDetailFlowFromTripListOnly()) {
                        resetTripDetailFlow("trip_list_visible_without_modal events=$listWithoutModalEventCount")
                    } else {
                        logFlowWaiting("trip_list_visible_ignored_while_detail_flow_active events=$listWithoutModalEventCount")
                    }
                    return
                }

                if (detailFlowStage == DetailFlowStage.CARD_CLICKED && isTripDetailModalVisible(rootNode)) {
                    listWithoutModalEventCount = 0
                    val modalTrip = extractModalTrip(rootNode)
                    enrichActiveFlowWithModalTrip(modalTrip)
                    lastModalSummary = "visible=true modal=${modalTrip?.fingerprint ?: "unknown"} " +
                        "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                    detailFlowStage = DetailFlowStage.MODAL_RENDERED
                    activeFlowContext?.let { timingFor(it.attemptId).modalRenderedAt = nowMs() }
                    logFlowDebug {
                        "MODAL_RENDERED ${flowContextLog()} " +
                            "modal=${modalTrip?.fingerprint ?: "unknown"} " +
                            "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                    }
                    if (modalTrip != null && modalTrip.fingerprint != pendingTripClick?.fingerprint) {
                        logFlowWaiting("modal_trip_fingerprint_mismatch modal=${modalTrip.fingerprint}")
                    }
                    startRouteLabelMonitor("modal_tree_confirmed")
                }

                if (
                    detailFlowStage == DetailFlowStage.MODAL_RENDERED &&
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    isTripDetailModalVisible(rootNode)
                ) {
                    listWithoutModalEventCount = 0
                    detailFlowStage = DetailFlowStage.DETAIL_CONTENT_CHANGED
                    logFlowDebug {
                        "DETAIL_CONTENT_CHANGED ${flowContextLog()} " +
                            "eventClass=${event.className} modalVisible=true"
                    }
                    startRouteLabelMonitor("detail_content_changed")
                }

                if (
                    (detailFlowStage == DetailFlowStage.MODAL_RENDERED || detailFlowStage == DetailFlowStage.DETAIL_CONTENT_CHANGED) &&
                    isGoogleMapEvent(event) &&
                    isTripDetailModalVisible(rootNode)
                ) {
                    listWithoutModalEventCount = 0
                    detailFlowStage = DetailFlowStage.MAP_CHANGED
                    logFlowDebug {
                        "MAP_CHANGED ${flowContextLog()} " +
                            "eventClass=${event.className} desc=${event.contentDescription}"
                    }
                    requestRouteLabelScreenshotOnMapEvent("map_event")
                }
            }
        }
    }

    private fun isTripDetailWindowEvent(event: AccessibilityEvent): Boolean {
        return event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString() == "ya6"
    }

    private fun isTripDetailFlowActive(): Boolean {
        return detailFlowStage != DetailFlowStage.IDLE
    }

    private fun isTripDetailFlowComplete(): Boolean {
        return detailFlowStage == DetailFlowStage.MAP_CHANGED ||
            detailFlowStage == DetailFlowStage.OCR_COMPLETED
    }

    private fun shouldResetDetailFlowFromTripListOnly(): Boolean {
        return when (detailFlowStage) {
            DetailFlowStage.IDLE -> false
            DetailFlowStage.CARD_CLICKED -> {
                if (routeLabelMonitorActive) {
                    activeFlowElapsedMs() >= maxRouteLabelAnalysisMs
                } else {
                    listWithoutModalEventCount >= 3
                }
            }
            DetailFlowStage.OCR_COMPLETED -> true
            DetailFlowStage.MODAL_RENDERED,
            DetailFlowStage.DETAIL_CONTENT_CHANGED,
            DetailFlowStage.MAP_CHANGED -> false
            DetailFlowStage.OCR_REQUESTED -> {
                activeFlowElapsedMs() >= maxRouteLabelAnalysisMs ||
                    (routeLabelOcrRetryCount > 0 && isTripListVisibleWithoutDetailModal())
            }
        }
    }

    private fun activeFlowElapsedMs(): Long {
        val attemptId = activeFlowContext?.attemptId ?: return 0L
        return timingFor(attemptId).cardClickedAt
            .takeIf { it > 0L }
            ?.let { nowMs() - it }
            ?: 0L
    }

    private fun isTripDetailModalCurrentlyVisible(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return isTripDetailModalVisible(rootNode)
    }

    private fun isTripListVisibleWithoutDetailModal(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return isTripListVisible(rootNode) && !isTripDetailModalVisible(rootNode)
    }

    private fun startRouteLabelMonitor(reason: String) {
        listOverlayRenderingBlocked = true
        routeLabelMonitorActive = true
        routeLabelAwaitingMapEventRescan = false
        val context = activeFlowContext
        val startedAt = nowMs()
        context?.let { timingFor(it.attemptId).routeMonitorStartedAt = startedAt }
        logFlowDebug {
            "ROUTE_MONITOR_START ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "modalToMonitor=${context?.let { deltaMs(timingFor(it.attemptId).modalRenderedAt, startedAt) } ?: "na"}ms " +
                "activeOverlays=${activeOverlays.size} overlayGeneration=$overlayClearGeneration"
        }
        requestRouteLabelScreenshotWhenOverlaysGone(reason, overlayClearGeneration)
    }

    private fun scheduleModalVisibilityFallback(attemptId: Long) {
        mainHandler.postDelayed({
            val context = activeFlowContext ?: return@postDelayed
            if (context.attemptId != attemptId || detailFlowStage != DetailFlowStage.CARD_CLICKED) {
                return@postDelayed
            }

            val elapsedMs = timingFor(attemptId).cardClickedAt
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

            if (isTripDetailModalVisible(rootNode)) {
                listWithoutModalEventCount = 0
                val modalTrip = extractModalTrip(rootNode)
                enrichActiveFlowWithModalTrip(modalTrip)
                lastModalSummary = "visible=true modal=${modalTrip?.fingerprint ?: "unknown"} " +
                    "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                detailFlowStage = DetailFlowStage.MODAL_RENDERED
                timingFor(attemptId).modalRenderedAt = nowMs()
                logFlowDebug {
                    "MODAL_RENDERED_BY_FALLBACK ${flowContextLog(context)} elapsedMs=$elapsedMs " +
                        "modal=${modalTrip?.fingerprint ?: "unknown"} " +
                        "matches=${modalTrip?.fingerprint == pendingTripClick?.fingerprint}"
                }
                if (!isRuntimeVerboseEnabled()) {
                    Log.d(TAG_FLOW, "MODAL_RENDERED_BY_FALLBACK attempt=$attemptId elapsedMs=$elapsedMs")
                }
                startRouteLabelMonitor("modal_visibility_fallback")
                return@postDelayed
            }

            if (isTripListVisible(rootNode)) {
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
        context?.let { timingFor(it.attemptId).overlayGatePostEnteredAt = postEnteredAt }
        logFlowDebug {
            "ROUTE_MONITOR_POST_ENTERED ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "monitorToPost=${context?.let { deltaMs(timingFor(it.attemptId).routeMonitorStartedAt, postEnteredAt) } ?: "na"}ms " +
                "modalToPost=${context?.let { deltaMs(timingFor(it.attemptId).modalRenderedAt, postEnteredAt) } ?: "na"}ms " +
                "activeOverlays=${activeOverlays.size} overlayGeneration=$overlayClearGeneration required=$requiredOverlayGeneration"
        }
        if (activeOverlays.isNotEmpty() || overlayClearGeneration < requiredOverlayGeneration) {
            logFlowWaiting(
                "waiting_overlay_clear reason=$reason activeOverlays=${activeOverlays.size} " +
                    "overlayGeneration=$overlayClearGeneration required=$requiredOverlayGeneration"
            )
            mainHandler.postDelayed({
                requestRouteLabelScreenshotWhenOverlaysGone(reason, requiredOverlayGeneration)
            }, 80L)
            return
        }

        context?.let { timingFor(it.attemptId).overlayGatePassedAt = nowMs() }
        logFlowDebug {
            "ROUTE_OVERLAY_GATE_PASSED ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "postToGate=${context?.let { deltaMs(timingFor(it.attemptId).overlayGatePostEnteredAt, timingFor(it.attemptId).overlayGatePassedAt) } ?: "na"}ms " +
                "modalToGate=${context?.let { deltaMs(timingFor(it.attemptId).modalRenderedAt, timingFor(it.attemptId).overlayGatePassedAt) } ?: "na"}ms"
        }
        requestRouteLabelScreenshotAfterGate(reason)
    }

    private fun requestRouteLabelScreenshotAfterGate(reason: String) {
        if (!routeLabelMonitorActive) return
        val context = activeFlowContext
        val screenshotPostEnteredAt = nowMs()
        context?.let { timingFor(it.attemptId).screenshotPostEnteredAt = screenshotPostEnteredAt }
        logFlowDebug {
            "ROUTE_SCREENSHOT_POST_ENTERED ${flowContextLog(context)} reason=$reason stage=$detailFlowStage " +
                "gateToScreenshotPost=${context?.let { deltaMs(timingFor(it.attemptId).overlayGatePassedAt, screenshotPostEnteredAt) } ?: "na"}ms " +
                "modalToScreenshotPost=${context?.let { deltaMs(timingFor(it.attemptId).modalRenderedAt, screenshotPostEnteredAt) } ?: "na"}ms"
        }
        requestRouteLabelScreenshot(reason)
    }

    private fun requestRouteLabelScreenshot(reason: String) {
        if (screenshotInFlight) {
            logFlowWaiting("screenshot_already_in_flight reason=$reason")
            scheduleRouteLabelRescan("screenshot_in_flight")
            return
        }
        routeLabelAwaitingMapEventRescan = false
        screenshotInFlight = true
        routeLabelScanCount += 1
        lastScreenshotReason = reason
        val context = activeFlowContext ?: pendingTripClick?.let { TripFlowContext(detailFlowAttempt, it) }
        if (reason.startsWith("visual_rescan_after_ocr_parse_incomplete")) {
            removeDetailProfitabilityOverlay()
        }
        context?.let { timingFor(it.attemptId).screenshotRequestedAt = nowMs() }
        logFlowDebug { "SCREENSHOT_REQUEST ${flowContextLog(context)} scan=$routeLabelScanCount reason=$reason stage=$detailFlowStage" }

        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            context?.let { timingFor(it.attemptId).screenshotCallbackAt = nowMs() }
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()

                            if (bitmap == null) {
                                logFlowDebug { "SCREENSHOT_EMPTY ${flowContextLog(context)}" }
                                logFlowIncomplete("screenshot_empty", context)
                                return
                            }

                            context?.let { timingFor(it.attemptId).scanStartedAt = nowMs() }
                            val result = RouteLabelDetector.detect(bitmap)
                            context?.let { timingFor(it.attemptId).scanCompletedAt = nowMs() }
                            lastRouteScanSummary = "visible=${result.routeLabelsVisible} " +
                                "mode=${result.mode} " +
                                "bluePixels=${result.bluePixelCount} greenPixels=${result.greenPixelCount} " +
                                "blueCandidates=${result.pickupCandidates.size} greenCandidates=${result.destinationCandidates.size} " +
                                "blueBox=${result.pickupLabel?.bounds ?: "none"} " +
                                "greenBox=${result.destinationLabel?.bounds ?: "none"}"
                            logFlowDebug {
                                "ROUTE_LABEL_SCAN ${flowContextLog(context)} scan=$routeLabelScanCount " +
                                    "afterFlowReset=${isContextStale(context)} $lastRouteScanSummary"
                            }

                            var bitmapOwnedByOcr = false
                            if (result.routeLabelsVisible) {
                                context?.let { timingFor(it.attemptId).labelsVisibleAt = nowMs() }
                                if (!isContextStale(context)) {
                                    routeLabelMonitorActive = false
                                    detailFlowStage = DetailFlowStage.MAP_CHANGED
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
                            screenshotInFlight = false
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotInFlight = false
                        logFlowDebug { "SCREENSHOT_FAILED ${flowContextLog(context)} code=$errorCode" }
                        logFlowIncomplete("screenshot_failed code=$errorCode", context)
                    }
                }
            )
        } catch (e: SecurityException) {
            screenshotInFlight = false
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
            detailFlowStage = DetailFlowStage.OCR_REQUESTED
        }
        logFlowDebug {
            "ROUTE_LABEL_OCR_REQUEST ${flowContextLog(context)} afterFlowReset=${isContextStale(context)} " +
                "blueBox=${detection.pickupLabel?.bounds ?: "none"} greenBox=${detection.destinationLabel?.bounds ?: "none"}"
        }
        context?.let { timingFor(it.attemptId).ocrRequestedAt = nowMs() }

        RouteLabelOcr.recognize(
            source = bitmap,
            detection = detection,
            onSuccess = { result ->
                bitmap.recycle()
                context?.let { timingFor(it.attemptId).ocrCompletedAt = nowMs() }
                lastOcrSummary = "complete=${result.complete} " +
                    "pickupCandidate=${result.pickupCandidateIndex} destinationCandidate=${result.destinationCandidateIndex} " +
                    "pickupRaw=${result.pickup.rawText} pickupMin=${result.pickup.minutes} pickupKm=${result.pickup.distanceKm} " +
                    "destinationRaw=${result.destination.rawText} destinationMin=${result.destination.minutes} destinationKm=${result.destination.distanceKm}"
                logFlowDebug {
                    "ROUTE_LABEL_OCR_RESULT ${flowContextLog(context)} afterFlowReset=${isContextStale(context)} $lastOcrSummary"
                }

                if (result.complete) {
                    val profitability = calculateRealProfitability(context, result)
                    if (profitability != null) {
                        storeRealTripEvaluation(context, result, profitability)
                    }
                    if (!isContextStale(context)) {
                        detailFlowStage = DetailFlowStage.OCR_COMPLETED
                        if (profitability != null) {
                            if (!isTripDetailModalCurrentlyVisible()) {
                                logFlowWaiting("modal_tree_unavailable_at_ocr_complete_showing_overlay_from_active_context", context)
                            }
                            showDetailProfitabilityOverlay(
                                profitability,
                                result,
                                findOfferRecommendation(context, result, profitability)
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
            onFailure = { exception ->
                bitmap.recycle()
                lastOcrSummary = "failed=${exception.message}"
                logFlowIncomplete("ocr_failed message=${exception.message}", context)
            }
        )
    }

    private fun retryRouteLabelOcrAfterIncompleteResult(
        context: TripFlowContext?,
        result: RouteLabelOcr.OcrResult
    ) {
        val analysisElapsedMs = context
            ?.let { timingFor(it.attemptId).cardClickedAt }
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
        detailFlowStage = DetailFlowStage.MAP_CHANGED
        logFlowDebug {
            "ROUTE_LABEL_OCR_RETRY ${flowContextLog(context)} retry=$routeLabelOcrRetryCount " +
                "pickupComplete=${result.pickup.complete} destinationComplete=${result.destination.complete} " +
                "lastOcr=[$lastOcrSummary]"
        }
        scheduleRouteLabelRescan("ocr_parse_incomplete_retry_$routeLabelOcrRetryCount")
    }

    private fun storeRealTripEvaluation(
        context: TripFlowContext?,
        ocrResult: RouteLabelOcr.OcrResult,
        profitability: ProfitabilityResult
    ) {
        val trip = context?.trip ?: return
        val price = parsePriceText(trip.priceText) ?: return
        val pickupDistanceKm = ocrResult.pickup.distanceKm ?: return
        val tripDistanceKm = ocrResult.destination.distanceKm ?: return
        tripEvaluationCache.store(
            TripEvaluationSnapshot(
                identity = trip.identity,
                kind = TripEvaluationKind.REAL,
                profitability = profitability,
                price = price,
                pickupDistanceKm = pickupDistanceKm,
                tripDistanceKm = tripDistanceKm,
                pickupMinutes = ocrResult.pickup.minutes,
                tripMinutes = ocrResult.destination.minutes,
                updatedAtMs = nowMs()
            )
        )
        Log.d(TAG_FLOW, "REAL_EVALUATION_STORED attempt=${context.attemptId} status=${profitability.status} usdKm=${profitability.expectedUsdPerKm}")
    }

    private fun calculateRealProfitability(
        context: TripFlowContext?,
        ocrResult: RouteLabelOcr.OcrResult
    ): ProfitabilityResult? {
        val tripPrice = context?.trip?.priceText?.let(::parsePriceText) ?: return null
        val pickupDistanceKm = ocrResult.pickup.distanceKm ?: return null
        val tripDistanceKm = ocrResult.destination.distanceKm ?: return null

        return ProfitabilityEngine.calculate(
            tripPrice = tripPrice,
            pickupDistanceKm = pickupDistanceKm,
            tripDistanceKm = tripDistanceKm,
            maxPickupDistanceKm = settingsManager.maxPickupDistance,
            minUsdPerKm = settingsManager.minUsdPerKm,
            commissionPercent = settingsManager.commissionPercent,
            isPreview = false
        )
    }

    private fun findOfferRecommendation(
        context: TripFlowContext?,
        ocrResult: RouteLabelOcr.OcrResult,
        currentProfitability: ProfitabilityResult
    ): OfferRecommendation? {
        if (currentProfitability.status == TripStatus.RENTABLE) return null

        val trip = context?.trip ?: return null
        val basePrice = parsePriceText(trip.priceText) ?: return null
        val pickupDistanceKm = ocrResult.pickup.distanceKm ?: return null
        val tripDistanceKm = ocrResult.destination.distanceKm ?: return null
        val visibleOfferPriceTexts = rootInActiveWindow
            ?.takeIf { isTripDetailModalVisible(it) }
            ?.let { extractOfferPriceTexts(it, trip.priceText) }
            .orEmpty()

        return (trip.offerPriceTexts + visibleOfferPriceTexts)
            .mapNotNull(::parsePriceText)
            .filter { it > basePrice + 0.001 }
            .distinct()
            .sorted()
            .firstNotNullOfOrNull { offerPrice ->
                val offerProfitability = ProfitabilityEngine.calculate(
                    tripPrice = offerPrice,
                    pickupDistanceKm = pickupDistanceKm,
                    tripDistanceKm = tripDistanceKm,
                    maxPickupDistanceKm = settingsManager.maxPickupDistance,
                    minUsdPerKm = settingsManager.minUsdPerKm,
                    commissionPercent = settingsManager.commissionPercent,
                    isPreview = false
                )
                if (offerProfitability.status == TripStatus.RENTABLE) {
                    OfferRecommendation(offerPrice, offerProfitability)
                } else {
                    null
                }
            }
    }

    private fun parsePriceText(priceText: String): Double? {
        return Regex("""\d+(?:[,.]\d+)?""")
            .find(priceText)
            ?.value
            ?.replace(",", ".")
            ?.toDoubleOrNull()
    }

    private fun scheduleRouteLabelRescan(reason: String) {
        if (!routeLabelMonitorActive) return
        routeLabelAwaitingMapEventRescan = true
        logFlowDebug { "ROUTE_LABEL_RESCAN_SCHEDULED ${flowContextLog()} reason=$reason" }
        mainHandler.postDelayed({
            if (!routeLabelMonitorActive) return@postDelayed
            if (!routeLabelAwaitingMapEventRescan) return@postDelayed
            routeLabelAwaitingMapEventRescan = false
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                logFlowWaiting("route_label_rescan_root_unavailable reason=$reason")
                scheduleRouteLabelRescan("root_unavailable")
                return@postDelayed
            }

            val elapsedMs = activeFlowElapsedMs()
            if (
                elapsedMs >= maxRouteLabelAnalysisMs &&
                isTripListVisible(rootNode) &&
                !isTripDetailModalVisible(rootNode)
            ) {
                resetTripDetailFlow("trip_list_returned_before_route_labels_visible reason=$reason")
                return@postDelayed
            }

            requestRouteLabelScreenshotWhenOverlaysGone(
                "visual_rescan_after_$reason",
                overlayClearGeneration
            )
        }, 250L)
    }

    private fun requestRouteLabelScreenshotOnMapEvent(reason: String) {
        if (!routeLabelMonitorActive || !routeLabelAwaitingMapEventRescan || screenshotInFlight) return
        routeLabelAwaitingMapEventRescan = false
        logFlowDebug { "ROUTE_LABEL_RESCAN_BY_MAP_EVENT ${flowContextLog()} reason=$reason" }
        requestRouteLabelScreenshotWhenOverlaysGone(
            "visual_rescan_after_$reason",
            overlayClearGeneration
        )
    }

    private fun extractClickedTrip(event: AccessibilityEvent): PendingTripClick? {
        if (isTripClickFromIgnoredSurface(event.source)) {
            logFlowDebug { "CARD_CLICK_IGNORED ignoredSurface event=${describeFlowEvent(event)}" }
            return null
        }

        val texts = event.text.map { it.toString().trim() }.filter { it.isNotEmpty() }
        val joined = texts.joinToString("|")
        if (!joined.contains("$")) return null
        if (!joined.contains("Seleccionar en el mapa", ignoreCase = true)) return null

        val passengerName = texts.firstOrNull() ?: return null
        val pickupDistanceText = texts.firstOrNull { it.contains("km", ignoreCase = true) || it.contains("metro", ignoreCase = true) } ?: return null
        val priceText = texts.firstOrNull { it.contains("$") } ?: return null
        val priceIndex = texts.indexOf(priceText)
        val pickupAddress = texts.drop(priceIndex + 1).firstOrNull {
            !it.equals("Precio justo", ignoreCase = true) &&
                !it.equals("Yappy", ignoreCase = true) &&
                !it.equals("Quejarse", ignoreCase = true) &&
                !it.equals("Ocultar", ignoreCase = true) &&
                !it.equals("Seleccionar en el mapa", ignoreCase = true)
        } ?: return null
        val pickupIndex = texts.indexOf(pickupAddress)
        val destinationAddress = texts.drop(pickupIndex + 1).firstOrNull {
            !it.equals("Yappy", ignoreCase = true) &&
                !it.equals("Quejarse", ignoreCase = true) &&
                !it.equals("Ocultar", ignoreCase = true) &&
                !it.equals("Seleccionar en el mapa", ignoreCase = true)
        } ?: return null

        return PendingTripClick(
            passengerName = passengerName,
            pickupDistanceText = pickupDistanceText,
            priceText = priceText,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress
        )
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

    private fun isTripClickFromIgnoredSurface(source: AccessibilityNodeInfo?): Boolean {
        var current = source
        var depth = 0
        while (current != null && depth < 8) {
            val viewId = current.viewIdResourceName.orEmpty()
            if (
                viewId.endsWith("driver_common_imageview_avatar") ||
                viewId.endsWith("item_order_userinfoview") ||
                viewId.endsWith("item_order_imageview_dots") ||
                viewId.endsWith("item_order_options_container_hide")
            ) {
                return true
            }
            if (
                viewId.endsWith("item_order_container_info") ||
                viewId.endsWith("info_textview_stage_price_view") ||
                viewId.endsWith("order_info_textview_from_address") ||
                viewId.endsWith("order_info_textview_to_address") ||
                viewId.endsWith("order_info_textview_to_addresses")
            ) {
                return false
            }
            current = current.parent
            depth += 1
        }
        return false
    }

    private fun extractModalTrip(rootNode: AccessibilityNodeInfo): PendingTripClick? {
        val passengerName = findTextById(rootNode, "sinet.startup.inDriver:id/user_info_text_name") ?: return null
        val pickupDistanceText = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_distance") ?: return null
        val priceText = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_price") ?: return null
        val pickupAddress = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_address_text_pickup") ?: return null
        val destinationAddress = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_address_text_destination") ?: return null
        val offerPriceTexts = extractOfferPriceTexts(rootNode, priceText)

        return PendingTripClick(
            passengerName = passengerName,
            pickupDistanceText = pickupDistanceText,
            priceText = priceText,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress,
            offerPriceTexts = offerPriceTexts
        )
    }

    private fun extractOfferPriceTexts(rootNode: AccessibilityNodeInfo, basePriceText: String): List<String> {
        val basePrice = parsePriceText(basePriceText) ?: return emptyList()
        return collectNodeTexts(rootNode)
            .filter { it.contains("$") }
            .mapNotNull { text ->
                val price = parsePriceText(text) ?: return@mapNotNull null
                if (price > basePrice + 0.001) text else null
            }
            .distinctBy { parsePriceText(it) }
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val texts = mutableListOf<String>()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        for (index in 0 until node.childCount) {
            texts.addAll(collectNodeTexts(node.getChild(index)))
        }
        return texts
    }

    private fun isTripDetailModalVisible(rootNode: AccessibilityNodeInfo): Boolean {
        val hasDetailHeader = hasNodeByText(rootNode, "Solicitud de viaje") ||
            hasNodeById(rootNode, "sinet.startup.inDriver:id/button_offer")
        val hasDetailTripData = hasNodeById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_price") ||
            hasNodeById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_distance") ||
            hasNodeById(rootNode, "sinet.startup.inDriver:id/order_info_address_text_pickup") ||
            hasNodeById(rootNode, "sinet.startup.inDriver:id/order_info_address_text_destination")

        return hasDetailHeader && hasDetailTripData
    }

    private fun isTripListVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container").isNotEmpty()
    }

    private fun isTripSearchEmptyStateVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return hasNodeByText(rootNode, "Buscando en un área más amplia") ||
            hasNodeByText(rootNode, "Buscando en un area mas amplia") ||
            collectNodeTexts(rootNode).any { text ->
                text.contains("Buscando en un", ignoreCase = true) &&
                    (
                        text.contains("área", ignoreCase = true) ||
                            text.contains("area", ignoreCase = true)
                    )
            }
    }

    private fun isGoogleMapEvent(event: AccessibilityEvent): Boolean {
        return event.className?.toString() == "android.view.TextureView" &&
            event.contentDescription?.toString()?.contains("Mapa de Google", ignoreCase = true) == true
    }

    private fun findTextById(rootNode: AccessibilityNodeInfo, viewId: String): String? {
        return rootNode.findAccessibilityNodeInfosByViewId(viewId)
            .firstOrNull()
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun hasNodeById(rootNode: AccessibilityNodeInfo, viewId: String): Boolean {
        return rootNode.findAccessibilityNodeInfosByViewId(viewId).isNotEmpty()
    }

    private fun hasNodeByText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        return rootNode.findAccessibilityNodeInfosByText(text).isNotEmpty()
    }

    private fun resetTripDetailFlow(reason: String) {
        if (detailFlowStage == DetailFlowStage.IDLE && pendingTripClick == null) return
        if (!isTripDetailFlowComplete()) {
            logFlowIncomplete("reset_before_route_labels_visible reason=$reason")
        }
        routeLabelMonitorActive = false
        routeLabelAwaitingMapEventRescan = false
        removeDetailProfitabilityOverlay()
        if (isRuntimeVerboseEnabled()) {
            Log.d(TAG_FLOW, "FLOW_RESET attempt=$detailFlowAttempt reason=$reason previousStage=$detailFlowStage pending=${pendingTripClick?.fingerprint ?: "none"}")
        } else {
            Log.d(TAG_FLOW, "FLOW_RESET attempt=$detailFlowAttempt reason=$reason previousStage=$detailFlowStage")
        }
        detailFlowStage = DetailFlowStage.IDLE
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
            if (detailFlowStage != DetailFlowStage.IDLE || listOverlayRenderingBlocked) {
                logOverlayTrace {
                    "POST_DETAIL_REFRESH_SKIPPED remaining=$remainingAttempts stage=$detailFlowStage " +
                        "blocked=$listOverlayRenderingBlocked active=${activeOverlays.size}"
                }
                return@postDelayed
            }
            processMainFlow("post_detail_refresh remaining=$remainingAttempts")
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
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            logOverlayTrace { "LIST_SCAN_SKIPPED reason=$reason cause=root_null active=${activeOverlays.size}" }
            return
        }

        if (isTripDetailFlowActive() || listOverlayRenderingBlocked || isTripDetailModalVisible(rootNode)) {
            clearAllOverlays("process_blocked_by_detail_or_modal")
            logFlowDebug {
                "LIST_OVERLAY_RENDER_BLOCKED reason=$reason stage=$detailFlowStage blocked=$listOverlayRenderingBlocked " +
                    "modalVisible=${isTripDetailModalVisible(rootNode)} activeOverlays=${activeOverlays.size}"
            }
            return
        }
        
        val scanId = ++listScanSequence
        val scanStartedAt = nowMs()
        val nodes = rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container")
        val foundKeysInThisScan = mutableSetOf<String>()
        val foundBoundsInThisScan = mutableMapOf<String, Rect>()
        logOverlayTrace {
            "LIST_SCAN_START reason=$reason nodes=${nodes.size} active=${activeOverlays.size} activeKeys=${compactKeys(activeOverlays.keys)} " +
                "stage=$detailFlowStage blocked=$listOverlayRenderingBlocked"
        }

        val maxP = settingsManager.maxPickupDistance
        val minU = settingsManager.minUsdPerKm
        val comm = settingsManager.commissionPercent
        val previewTripDistance = settingsManager.previewTripDistanceKm

        for (node in nodes) {
            val trip = extractTripData(node) ?: continue
            val tripKey = trip.fingerprint
            foundKeysInThisScan.add(tripKey)
            overlayMissingScanCounts.remove(tripKey)
            val currentBounds = Rect()
            node.getBoundsInScreen(currentBounds)
            foundBoundsInThisScan[tripKey] = Rect(currentBounds)
            markTripSeen(tripKey, scanId, currentBounds, trip.price, trip.pickupDistance)

            val realSnapshot = tripEvaluationCache.findReal(trip.identity, nowMs())
            val result = realSnapshot?.let { snapshot ->
                ProfitabilityEngine.calculate(
                    tripPrice = trip.price,
                    pickupDistanceKm = snapshot.pickupDistanceKm,
                    tripDistanceKm = snapshot.tripDistanceKm,
                    maxPickupDistanceKm = maxP,
                    minUsdPerKm = minU,
                    commissionPercent = comm,
                    isPreview = false
                )
            } ?: ProfitabilityEngine.calculate(
                tripPrice = trip.price,
                pickupDistanceKm = trip.pickupDistance,
                tripDistanceKm = previewTripDistance,
                maxPickupDistanceKm = maxP,
                minUsdPerKm = minU,
                commissionPercent = comm,
                isPreview = true
            )

            if (realSnapshot != null) {
                syncOverlay(tripKey, currentBounds, result)
                logFlowDebug {
                    "LIST_REAL_OVERLAY_USED key=$tripKey status=${result.status} " +
                        "storedPrice=${realSnapshot.price} currentPrice=${trip.price} " +
                        "pickupKm=${realSnapshot.pickupDistanceKm} tripKm=${realSnapshot.tripDistanceKm}"
                }
            } else if (result.status != TripStatus.RENTABLE) {
                logOverlayTrace {
                    "LIST_OVERLAY_SKIP_HIDE_PREVIEW_NON_RENTABLE key=$tripKey status=${result.status} " +
                        "price=${trip.price} pickup=${trip.pickupDistance} foundKeys=${compactKeys(foundKeysInThisScan)}"
                }
                if (trip.pickupDistance > 0.05) {
                    logLifecycle(
                        "PREVIEW_HIDE_REQUEST key=$tripKey scan=$scanId seenToHide=${tripLifecycleAgeMs(tripKey)}ms " +
                            "price=${trip.price} pickup=${trip.pickupDistance} status=${result.status}"
                    )
                    executeDirectHide(node, "preview_not_rentable key=$tripKey")
                }
            } else {
                syncOverlay(tripKey, currentBounds, result)
            }
        }

        val keysToRemove = activeOverlays.keys.filter { !foundKeysInThisScan.contains(it) }
        logOverlayTrace {
            "LIST_SCAN_END nodes=${nodes.size} found=${foundKeysInThisScan.size} " +
                "foundKeys=${compactKeys(foundKeysInThisScan)} staleKeys=${compactKeys(keysToRemove)} activeBeforeRemove=${compactKeys(activeOverlays.keys)}"
        }
        if (isRuntimeVerboseEnabled()) {
            logLifecycle(
                "LIST_SCAN_END scan=$scanId nodes=${nodes.size} found=${foundKeysInThisScan.size} " +
                    "active=${activeOverlays.size} durationMs=${nowMs() - scanStartedAt}"
            )
        }
        if (foundKeysInThisScan.isEmpty() && activeOverlays.isNotEmpty()) {
            if (isTripSearchEmptyStateVisible(rootNode)) {
                val activeBeforeClear = activeOverlays.size
                clearAllOverlays("trip_list_empty_search_state")
                logOverlayTrace {
                    "LIST_SCAN_EMPTY_CONFIRMED_SEARCH_STATE nodes=${nodes.size} activeBefore=$activeBeforeClear"
                }
                return
            }
            logOverlayTrace {
                "LIST_SCAN_EMPTY_IGNORED_FOR_STALE nodes=${nodes.size} active=${activeOverlays.size} " +
                    "activeKeys=${compactKeys(activeOverlays.keys)}"
            }
            return
        }
        keysToRemove.forEach { tripKey ->
            val staleBounds = activeOverlays[tripKey]?.lastBounds
            val overlappingBounds = staleBounds?.let { bounds ->
                foundBoundsInThisScan.entries.firstOrNull { hasMeaningfulVerticalOverlap(bounds, it.value) }
            }
            if (overlappingBounds != null) {
                removeOverlay(
                    tripKey,
                    "stale_bounds_overlap_live_row foundKey=${overlappingBounds.key} " +
                        "staleBounds=$staleBounds liveBounds=${overlappingBounds.value}"
                )
                return@forEach
            }
            val missingCount = (overlayMissingScanCounts[tripKey] ?: 0) + 1
            overlayMissingScanCounts[tripKey] = missingCount
            if (missingCount >= staleOverlayRemovalConfirmations) {
                removeOverlay(
                    tripKey,
                    "not_found_in_current_list_scan confirmed=$missingCount found=${foundKeysInThisScan.size}"
                )
                overlayMissingScanCounts.remove(tripKey)
            } else {
                logOverlayTrace {
                    "LIST_OVERLAY_STALE_PENDING key=$tripKey missingCount=$missingCount " +
                        "required=$staleOverlayRemovalConfirmations found=${foundKeysInThisScan.size}"
                }
            }
        }
    }

    private fun hasMeaningfulVerticalOverlap(first: Rect, second: Rect): Boolean {
        val overlap = min(first.bottom, second.bottom) - max(first.top, second.top)
        if (overlap <= 0) return false

        val minHeight = min(first.height(), second.height()).coerceAtLeast(1)
        val requiredOverlap = max(32, (minHeight * 0.35f).toInt())
        return overlap >= requiredOverlap
    }

    private fun markTripSeen(
        tripKey: String,
        scanId: Long,
        bounds: Rect,
        price: Double,
        pickupDistanceKm: Double
    ) {
        val now = nowMs()
        val existing = listTripLifecycles[tripKey]
        if (existing == null) {
            listTripLifecycles[tripKey] = ListTripLifecycle(
                key = tripKey,
                firstSeenAt = now,
                firstScanId = scanId,
                lastSeenAt = now,
                lastScanId = scanId,
                lastBounds = Rect(bounds)
            )
            logLifecycle(
                "LIST_TRIP_FIRST_SEEN key=$tripKey scan=$scanId bounds=$bounds " +
                    "price=$price pickupKm=$pickupDistanceKm activeOverlays=${activeOverlays.size}"
            )
            pruneTripLifecycles(now)
        } else {
            existing.lastSeenAt = now
            existing.lastScanId = scanId
            existing.lastBounds = Rect(bounds)
        }
    }

    private fun markOverlayAdded(tripKey: String, result: ProfitabilityResult, bounds: Rect) {
        val now = nowMs()
        val lifecycle = listTripLifecycles[tripKey]
        if (lifecycle != null) {
            lifecycle.overlayFirstAddedAt = lifecycle.overlayFirstAddedAt.takeIf { it > 0L } ?: now
            lifecycle.overlayLastUpdatedAt = now
            lifecycle.lastStatus = result.status
            lifecycle.lastPreview = result.isPreview
            logLifecycle(
                "LIST_OVERLAY_FIRST_ADDED key=$tripKey scan=${lifecycle.lastScanId} " +
                    "seenToOverlayMs=${now - lifecycle.firstSeenAt} lastSeenToOverlayMs=${now - lifecycle.lastSeenAt} " +
                    "status=${result.status} preview=${result.isPreview} bounds=$bounds active=${activeOverlays.size}"
            )
        } else {
            logLifecycle(
                "LIST_OVERLAY_ADDED_WITHOUT_SEEN key=$tripKey status=${result.status} " +
                    "preview=${result.isPreview} bounds=$bounds active=${activeOverlays.size}"
            )
        }
    }

    private fun markOverlayUpdated(tripKey: String, result: ProfitabilityResult, reason: String) {
        val now = nowMs()
        val lifecycle = listTripLifecycles[tripKey] ?: return
        lifecycle.overlayLastUpdatedAt = now
        lifecycle.lastStatus = result.status
        lifecycle.lastPreview = result.isPreview
        if (isRuntimeVerboseEnabled()) {
            logLifecycle(
                "LIST_OVERLAY_UPDATED key=$tripKey reason=$reason scan=${lifecycle.lastScanId} " +
                    "seenToUpdateMs=${now - lifecycle.firstSeenAt} status=${result.status} preview=${result.isPreview}"
            )
        }
    }

    private fun markTripClicked(tripKey: String) {
        val now = nowMs()
        val lifecycle = listTripLifecycles[tripKey]
        if (lifecycle == null) {
            logLifecycle(
                "LIST_TRIP_CLICKED_WITHOUT_LIFECYCLE key=$tripKey attempt=${detailFlowAttempt + 1} " +
                    "activeOverlays=${activeOverlays.size} trackedViews=${activeListOverlayViews.size}"
            )
            return
        }
        lifecycle.clickAt = now
        logLifecycle(
            "LIST_TRIP_CLICKED key=$tripKey attempt=${detailFlowAttempt + 1} " +
                "seenToClickMs=${now - lifecycle.firstSeenAt} overlayAgeMs=${deltaMs(lifecycle.overlayFirstAddedAt, now) ?: "none"} " +
                "lastSeenAgeMs=${now - lifecycle.lastSeenAt} status=${lifecycle.lastStatus ?: "unknown"} " +
                "preview=${lifecycle.lastPreview} activeOverlays=${activeOverlays.size}"
        )
    }

    private fun markOverlayRemoved(tripKey: String, reason: String) {
        val now = nowMs()
        val lifecycle = listTripLifecycles[tripKey]
        if (lifecycle == null) {
            logLifecycle("LIST_OVERLAY_REMOVED_WITHOUT_LIFECYCLE key=$tripKey reason=$reason")
            return
        }
        lifecycle.overlayLastRemovedAt = now
        logLifecycle(
            "LIST_OVERLAY_REMOVED key=$tripKey reason=$reason " +
                "seenToRemoveMs=${now - lifecycle.firstSeenAt} overlayAgeMs=${deltaMs(lifecycle.overlayFirstAddedAt, now) ?: "none"} " +
                "clickAgeMs=${deltaMs(lifecycle.clickAt, now) ?: "none"} lastSeenAgeMs=${now - lifecycle.lastSeenAt} " +
                "status=${lifecycle.lastStatus ?: "unknown"} preview=${lifecycle.lastPreview}"
        )
    }

    private fun tripLifecycleAgeMs(tripKey: String): Long? {
        return listTripLifecycles[tripKey]?.let { nowMs() - it.firstSeenAt }
    }

    private fun pruneTripLifecycles(now: Long = nowMs()) {
        val maxAgeMs = 10 * 60 * 1000L
        if (listTripLifecycles.size <= 80) return
        val staleKeys = listTripLifecycles
            .filter { (_, lifecycle) -> now - lifecycle.lastSeenAt > maxAgeMs && !activeOverlays.containsKey(lifecycle.key) }
            .keys
            .toList()
        staleKeys.forEach { listTripLifecycles.remove(it) }
    }

    private fun logLifecycle(message: String) {
        Log.d(TAG_LIFECYCLE, message)
    }

    private fun syncOverlay(tripKey: String, bounds: Rect, result: ProfitabilityResult) {
        if (isTripDetailFlowActive() || listOverlayRenderingBlocked) {
            logOverlayTrace {
                "LIST_OVERLAY_SYNC_SKIPPED key=$tripKey reason=detail_flow_active stage=$detailFlowStage blocked=$listOverlayRenderingBlocked"
            }
            return
        }

        val overlayWidth = (bounds.width() * 0.4).toInt()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.right - overlayWidth
            y = bounds.top
        }

        try {
            if (activeOverlays.containsKey(tripKey)) {
                val record = activeOverlays[tripKey]!!
                updateHUDText(record.textContainer, result)
                applyListOverlayBackground(record.view, result)
                if (record.lastBounds != bounds) {
                    record.lastBounds = Rect(bounds)
                    windowManager.updateViewLayout(record.view, params)
                    markOverlayUpdated(tripKey, result, "layout_changed")
                    logOverlayTrace {
                        "LIST_OVERLAY_UPDATED_LAYOUT key=$tripKey status=${result.status} preview=${result.isPreview} bounds=$bounds"
                    }
                } else {
                    markOverlayUpdated(tripKey, result, "text_changed")
                    logOverlayTrace {
                        "LIST_OVERLAY_UPDATED_TEXT key=$tripKey status=${result.status} preview=${result.isPreview} bounds=$bounds"
                    }
                }
            } else {
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                applyListOverlayBackground(container, result)
                updateHUDText(container, result)
                windowManager.addView(container, params)
                activeOverlays[tripKey] = OverlayRecord(container, container, Rect(bounds))
                activeListOverlayViews.add(container)
                overlayMissingScanCounts.remove(tripKey)
                markOverlayAdded(tripKey, result, bounds)
                logOverlayTrace {
                    "LIST_OVERLAY_ADDED key=$tripKey status=${result.status} preview=${result.isPreview} " +
                        "bounds=$bounds active=${activeOverlays.size}"
                }
            }
        } catch (e: Exception) {
            logOverlayTrace { "LIST_OVERLAY_SYNC_FAILED key=$tripKey message=${e.message}" }
        }
    }

    private fun applyListOverlayBackground(view: View, result: ProfitabilityResult) {
        val color = when {
            result.status == TripStatus.RENTABLE -> "#E6004D00"
            !result.isPreview -> "#E69A1B1B"
            else -> "#E6333333"
        }
        view.setBackgroundColor(Color.parseColor(color))
    }

    private fun updateHUDText(container: LinearLayout, result: ProfitabilityResult) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = when {
                result.isPreview && result.status == TripStatus.RENTABLE -> "PREVIEW"
                result.status == TripStatus.RENTABLE -> "REAL"
                result.status == TripStatus.NOT_RENTABLE_PICKUP -> "REAL LEJOS"
                else -> "REAL NO RENT."
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = String.format("%.2f", result.expectedUsdPerKm) + " $/km"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = formatProfitLine(result)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        val footerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        footerContainer.addView(TextView(this).apply {
            text = String.format("%.1f", result.pickupDistanceKm) + " km | "
            setTextColor(Color.WHITE)
            textSize = 10f
        })
        val orangeCircle = View(this).apply {
            val size = (10 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (4 * resources.displayMetrics.density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FFA500"))
            }
        }
        footerContainer.addView(orangeCircle)
        footerContainer.addView(TextView(this).apply {
            text = "Total: " + String.format("%.1f", result.totalDistanceKm) + "? km"
            setTextColor(Color.WHITE)
            textSize = 10f
        })
        container.addView(footerContainer)
    }

    private fun showDetailProfitabilityOverlay(
        result: ProfitabilityResult,
        ocrResult: RouteLabelOcr.OcrResult,
        offerRecommendation: OfferRecommendation?
    ) {
        removeDetailProfitabilityOverlay()

        val displayMetrics = resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.60).toInt()
        val overlayHeight = ((if (offerRecommendation != null) 136 else 112) * displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - overlayWidth) / 2
            y = max((178 * displayMetrics.density).toInt(), 0)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (14 * displayMetrics.density).toInt(),
                (8 * displayMetrics.density).toInt(),
                (14 * displayMetrics.density).toInt(),
                (8 * displayMetrics.density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 8 * displayMetrics.density
                setColor(
                    if (result.status == TripStatus.RENTABLE) {
                        Color.parseColor("#E6004D00")
                    } else {
                        Color.parseColor("#E69A1B1B")
                    }
                )
            }
        }

        updateDetailProfitabilityText(container, result, ocrResult, offerRecommendation)

        try {
            windowManager.addView(container, params)
            detailProfitabilityOverlay = container
            activeFlowContext?.let { context ->
                val timing = timingFor(context.attemptId)
                timing.overlayShownAt = nowMs()
                logFlowDebug { "FLOW_TIMING ${flowContextLog(context)} ${formatFlowTiming(timing)}" }
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
        } catch (e: Exception) {
            Log.e(TAG_FLOW, "DETAIL_PROFITABILITY_OVERLAY_FAILED ${e.message}", e)
        }
    }

    private fun showDetailProgressOverlay() {
        removeDetailProfitabilityOverlay()

        val displayMetrics = resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.60).toInt()
        val overlayHeight = (72 * displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - overlayWidth) / 2
            y = max((178 * displayMetrics.density).toInt(), 0)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 8 * displayMetrics.density
                setColor(Color.parseColor("#E6333333"))
            }
        }
        container.addView(TextView(this).apply {
            text = "CALCULANDO..."
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = "Leyendo distancia y tiempo"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        try {
            windowManager.addView(container, params)
            detailProfitabilityOverlay = container
            logFlowDebug { "DETAIL_PROGRESS_OVERLAY_SHOWN ${flowContextLog()}" }
        } catch (e: Exception) {
            Log.e(TAG_FLOW, "DETAIL_PROGRESS_OVERLAY_FAILED ${e.message}", e)
        }
    }

    private fun updateDetailProfitabilityText(
        container: LinearLayout,
        result: ProfitabilityResult,
        ocrResult: RouteLabelOcr.OcrResult,
        offerRecommendation: OfferRecommendation?
    ) {
        val title = when (result.status) {
            TripStatus.RENTABLE -> "RENTABLE REAL"
            TripStatus.NOT_RENTABLE_PICKUP -> "RECOGIDA LEJOS"
            TripStatus.NOT_RENTABLE -> "NO RENTABLE"
        }
        container.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = String.format("%.2f $/km  |  %s", result.expectedUsdPerKm, formatProfitLine(result))
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = "Tiempo: ${ocrResult.pickup.minutes ?: "-"} + ${ocrResult.destination.minutes ?: "-"} min  |  Total: " +
                String.format("%.1f km", result.totalDistanceKm)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
        })
        if (offerRecommendation != null) {
            container.addView(TextView(this).apply {
                text = "Ofrecer $" + String.format("%.2f", offerRecommendation.price) +
                    " para rentable"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    private fun removeDetailProfitabilityOverlay() {
        detailProfitabilityOverlay?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: Exception) {}
        }
        detailProfitabilityOverlay = null
    }

    private fun formatProfitLine(result: ProfitabilityResult): String {
        val label = if (result.trueProfit < 0.0) "Pierde" else "Gana"
        return "$label: $" + String.format("%.2f", kotlin.math.abs(result.trueProfit))
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun deltaMs(from: Long, to: Long): Long? {
        return if (from > 0L && to > 0L) to - from else null
    }

    private fun timingFor(attemptId: Long): FlowTiming {
        return flowTimings.getOrPut(attemptId) { FlowTiming() }
    }

    private fun pruneFlowTimings() {
        val oldestToKeep = detailFlowAttempt - 20
        flowTimings.keys.filter { it < oldestToKeep }.forEach { flowTimings.remove(it) }
    }

    private fun formatFlowTiming(timing: FlowTiming): String {
        return "clickToModal=${deltaMs(timing.cardClickedAt, timing.modalRenderedAt) ?: "na"}ms " +
            "modalToMonitor=${deltaMs(timing.modalRenderedAt, timing.routeMonitorStartedAt) ?: "na"}ms " +
            "monitorToGatePost=${deltaMs(timing.routeMonitorStartedAt, timing.overlayGatePostEnteredAt) ?: "na"}ms " +
            "gatePostToPassed=${deltaMs(timing.overlayGatePostEnteredAt, timing.overlayGatePassedAt) ?: "na"}ms " +
            "gateToScreenshotPost=${deltaMs(timing.overlayGatePassedAt, timing.screenshotPostEnteredAt) ?: "na"}ms " +
            "screenshotPostToRequest=${deltaMs(timing.screenshotPostEnteredAt, timing.screenshotRequestedAt) ?: "na"}ms " +
            "modalToScreenshotRequest=${deltaMs(timing.modalRenderedAt, timing.screenshotRequestedAt) ?: "na"}ms " +
            "screenshotRequestToCallback=${deltaMs(timing.screenshotRequestedAt, timing.screenshotCallbackAt) ?: "na"}ms " +
            "scanDuration=${deltaMs(timing.scanStartedAt, timing.scanCompletedAt) ?: "na"}ms " +
            "screenshotCallbackToLabels=${deltaMs(timing.screenshotCallbackAt, timing.labelsVisibleAt) ?: "na"}ms " +
            "labelsToOcrResult=${deltaMs(timing.labelsVisibleAt, timing.ocrCompletedAt) ?: "na"}ms " +
            "ocrResultToOverlay=${deltaMs(timing.ocrCompletedAt, timing.overlayShownAt) ?: "na"}ms " +
            "clickToOverlay=${deltaMs(timing.cardClickedAt, timing.overlayShownAt) ?: "na"}ms"
    }

    private fun removeOverlay(tripKey: String, reason: String) {
        activeOverlays[tripKey]?.let {
            markOverlayRemoved(tripKey, reason)
            removeListOverlayView(it.view, "single_remove key=$tripKey reason=$reason")
            logOverlayTrace { "LIST_OVERLAY_REMOVED key=$tripKey reason=$reason bounds=${it.lastBounds}" }
        }
        activeOverlays.remove(tripKey)
        overlayMissingScanCounts.remove(tripKey)
    }

    private fun clearAllOverlays(reason: String) {
        val startedAt = nowMs()
        val viewsToRemove = (activeListOverlayViews + activeOverlays.values.map { it.view }).distinct()
        val removedCount = viewsToRemove.size
        val removedKeys = activeOverlays.keys.toList()
        val shouldLogClear = removedCount > 0 || isRuntimeVerboseEnabled()
        overlayMissingScanCounts.clear()
        if (shouldLogClear) {
            Log.d(
                TAG_RUNTIME_TRACE,
                "LIST_OVERLAY_CLEAR_START reason=$reason trackedKeys=${activeOverlays.size} trackedViews=$removedCount generation=$overlayClearGeneration " +
                    "stage=$detailFlowStage blocked=$listOverlayRenderingBlocked " +
                    "sinceCardClickMs=${deltaMs(lastCardClickForCleanupAt, startedAt) ?: "na"}"
            )
        }
        viewsToRemove.forEach { view ->
            removeListOverlayView(view, "clear_all reason=$reason")
        }
        removedKeys.forEach { key ->
            markOverlayRemoved(key, reason)
        }
        activeOverlays.clear()
        activeListOverlayViews.clear()
        if (removedCount > 0) {
            overlayClearGeneration += 1
            Log.d(
                TAG_RUNTIME_TRACE,
                "LIST_OVERLAYS_CLEARED attempt=$detailFlowAttempt reason=$reason removedViews=$removedCount " +
                    "generation=$overlayClearGeneration keys=${compactKeys(removedKeys)}"
            )
        }
        if (shouldLogClear) {
            Log.d(
                TAG_RUNTIME_TRACE,
                "LIST_OVERLAY_CLEAR_END reason=$reason removedViews=$removedCount trackedKeysAfter=${activeOverlays.size} trackedViewsAfter=${activeListOverlayViews.size} " +
                    "durationMs=${nowMs() - startedAt} generation=$overlayClearGeneration"
            )
        }
    }

    private fun removeListOverlayView(view: View, reason: String) {
        try {
            windowManager.removeViewImmediate(view)
            logOverlayTrace { "LIST_OVERLAY_VIEW_REMOVED reason=$reason mode=immediate" }
        } catch (immediateError: Exception) {
            try {
                windowManager.removeView(view)
                logOverlayTrace { "LIST_OVERLAY_VIEW_REMOVED reason=$reason mode=normal afterImmediate=${immediateError.message}" }
            } catch (removeError: Exception) {
                Log.w(
                    TAG_RUNTIME_TRACE,
                    "LIST_OVERLAY_VIEW_REMOVE_FAILED reason=$reason immediate=${immediateError.message} normal=${removeError.message}"
                )
            }
        } finally {
            activeListOverlayViews.remove(view)
        }
    }

    private fun executeDirectHide(node: AccessibilityNodeInfo, reason: String) {
        val hideBtn = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_options_container_hide").firstOrNull()
        if (hideBtn != null) {
            logOverlayTrace { "LIST_TRIP_HIDE_DIRECT reason=$reason action=hide_button" }
            hideBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            logOverlayTrace { "LIST_TRIP_HIDE_DIRECT reason=$reason action=dots_menu" }
            node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_imageview_dots")
                .firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun extractTripData(node: AccessibilityNodeInfo): Trip? {
        return try {
            val name = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/driver_common_textview_name").firstOrNull()?.text?.toString() ?: return null
            val priceT = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/info_textview_stage_price_view").firstOrNull()?.text?.toString() ?: ""
            val distT = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/order_info_stage_textview_distance").firstOrNull()?.text?.toString() ?: ""
            val from = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/order_info_textview_from_address").firstOrNull()?.text?.toString() ?: ""
            val to = findFirstTextByIds(
                node,
                "sinet.startup.inDriver:id/order_info_textview_to_address",
                "sinet.startup.inDriver:id/order_info_textview_destination_address",
                "sinet.startup.inDriver:id/order_info_stage_textview_to_address"
            ) ?: ""
            Trip(name.trim(), parseDoubleSafe(priceT), parseDoubleSafe(distT), from.trim(), to.trim())
        } catch (e: Exception) { null }
    }

    private fun findFirstTextByIds(node: AccessibilityNodeInfo, vararg viewIds: String): String? {
        return viewIds.firstNotNullOfOrNull { viewId ->
            node.findAccessibilityNodeInfosByViewId(viewId)
                .firstOrNull()
                ?.text
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseDoubleSafe(text: String): Double {
        val isMetro = text.contains("metro", ignoreCase = true)
        val cleaned = text.replace(",", ".").replace(Regex("[^0-9.]"), "")
        val value = cleaned.toDoubleOrNull() ?: 0.0
        return if (isMetro) value / 1000.0 else value
    }

    override fun onDestroy() {
        clearAllOverlays("service_destroy")
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}
}
