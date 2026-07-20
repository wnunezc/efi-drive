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
import com.efidriver.icarosnet.vision.RouteLabelDetector
import com.efidriver.icarosnet.vision.RouteLabelOcr
import com.efidriver.icarosnet.models.Trip
import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.engine.SettingsManager
import java.util.concurrent.Executors
import kotlin.math.max

@SuppressLint("AccessibilityPolicy")
class ScraperAccessibilityService : AccessibilityService() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var windowManager: WindowManager
    private val TAG_HUD = "EfiHUD"
    private val TAG_SONDA = "EfiSonda"
    private val TAG_FLOW = "EfiTripFlow"
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val activeOverlays = mutableMapOf<String, OverlayRecord>()
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
        val destinationAddress: String
    ) {
        val fingerprint: String = listOf(passengerName, priceText, pickupAddress, destinationAddress)
            .joinToString("|")
            .replace("\\s".toRegex(), "")
    }

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
    private var listWithoutModalEventCount = 0
    private val flowTimings = mutableMapOf<Long, FlowTiming>()

    private data class OverlayRecord(
        val view: View,
        val textContainer: LinearLayout,
        var lastBounds: Rect
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        Log.d(TAG_HUD, "--- SERVICIO ESTABLE v7.2 - HUD PROTEGIDO ---")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventStartedAt = nowMs()
        val packageName = event?.packageName?.toString() ?: ""
        
        if (packageName == "sinet.startup.inDriver") {
            observeTripDetailFlow(event)
            val afterObserveAt = nowMs()

            // 1. HUD Y FILTRO (Producci??n)
            if (isTripDetailFlowActive() || listOverlayRenderingBlocked) {
                clearAllOverlays()
            } else {
                processMainFlow()
            }
            val afterHudAt = nowMs()
            
            // 2. DIAGN??STICO ESTRUCTURAL (Aislado)
            var sondaDurationMs = 0L
            val shouldRunStructuralProbe =
                settingsManager.structuralProbeDebugEnabled &&
                    !isTripDetailFlowActive() &&
                    !listOverlayRenderingBlocked &&
                    (
                        event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    )
            if (shouldRunStructuralProbe) {
                try {
                    val sondaStartedAt = nowMs()
                    ejecutarSondaEstructural()
                    sondaDurationMs = nowMs() - sondaStartedAt
                } catch (e: Exception) {}
            }
            val eventDurationMs = nowMs() - eventStartedAt
            if (isDebugDiagnosticsEnabled() && (detailFlowStage != DetailFlowStage.IDLE || listOverlayRenderingBlocked || eventDurationMs > 50L || sondaDurationMs > 20L)) {
                logFlowDebug {
                    "ACCESS_EVENT_TIMING attempt=$detailFlowAttempt type=${event?.eventType ?: -1} " +
                        "class=${event?.className ?: "none"} stage=$detailFlowStage " +
                        "observe=${afterObserveAt - eventStartedAt}ms hud=${afterHudAt - afterObserveAt}ms " +
                        "sonda=${sondaDurationMs}ms total=${eventDurationMs}ms"
                }
            }
        } else {
            if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (packageName != "com.efidriver.icarosnet") {
                    resetTripDetailFlow("external_package=$packageName")
                    clearAllOverlays()
                    removeDetailProfitabilityOverlay()
                }
            }
        }
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
                detailFlowStage = DetailFlowStage.CARD_CLICKED
                listOverlayRenderingBlocked = true
                listWithoutModalEventCount = 0
                clearAllOverlays()
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
                if (!isDebugDiagnosticsEnabled()) {
                    Log.d(TAG_FLOW, "CARD_CLICKED attempt=$detailFlowAttempt price=${tripClick.priceText} pickup=${tripClick.pickupDistanceText}")
                }
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
                    clearAllOverlays()
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
            DetailFlowStage.CARD_CLICKED -> listWithoutModalEventCount >= 3
            DetailFlowStage.OCR_COMPLETED -> true
            DetailFlowStage.MODAL_RENDERED,
            DetailFlowStage.DETAIL_CONTENT_CHANGED,
            DetailFlowStage.MAP_CHANGED -> false
            DetailFlowStage.OCR_REQUESTED -> routeLabelOcrRetryCount > 0 || listWithoutModalEventCount >= 3
        }
    }

    private fun isTripDetailModalCurrentlyVisible(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        return isTripDetailModalVisible(rootNode)
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
                                if (!isContextStale(context)) {
                                    mainHandler.post {
                                        if (!isContextStale(context)) {
                                            showDetailProgressOverlay()
                                        }
                                    }
                                }
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
                    if (!isContextStale(context)) {
                        detailFlowStage = DetailFlowStage.OCR_COMPLETED
                        if (profitability != null) {
                            if (!isTripDetailModalCurrentlyVisible()) {
                                logFlowWaiting("modal_tree_unavailable_at_ocr_complete_showing_overlay_from_active_context", context)
                            }
                            showDetailProfitabilityOverlay(profitability, result)
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
        if (isContextStale(context) || routeLabelOcrRetryCount >= 2) {
            logFlowIncomplete("ocr_parse_incomplete", context)
            resetTripDetailFlow("ocr_parse_incomplete_after_retries retries=$routeLabelOcrRetryCount")
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

    private fun parsePriceText(priceText: String): Double? {
        return priceText
            .replace("$", "")
            .replace(",", ".")
            .trim()
            .toDoubleOrNull()
    }

    private fun scheduleRouteLabelRescan(reason: String) {
        if (!routeLabelMonitorActive) return
        routeLabelAwaitingMapEventRescan = true
        logFlowDebug { "ROUTE_LABEL_RESCAN_WAITING_FOR_MAP_EVENT ${flowContextLog()} reason=$reason" }
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

            if (isTripListVisible(rootNode) && !hasNodeByText(rootNode, "Solicitud de viaje")) {
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

    private fun extractModalTrip(rootNode: AccessibilityNodeInfo): PendingTripClick? {
        val passengerName = findTextById(rootNode, "sinet.startup.inDriver:id/user_info_text_name") ?: return null
        val pickupDistanceText = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_distance") ?: return null
        val priceText = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_price") ?: return null
        val pickupAddress = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_address_text_pickup") ?: return null
        val destinationAddress = findTextById(rootNode, "sinet.startup.inDriver:id/order_info_address_text_destination") ?: return null

        return PendingTripClick(
            passengerName = passengerName,
            pickupDistanceText = pickupDistanceText,
            priceText = priceText,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress
        )
    }

    private fun isTripDetailModalVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return hasNodeById(rootNode, "sinet.startup.inDriver:id/design_bottom_sheet") &&
            hasNodeById(rootNode, "sinet.startup.inDriver:id/button_offer") &&
            hasNodeById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_price") &&
            (
                hasNodeById(rootNode, "sinet.startup.inDriver:id/order_info_header_text_distance") ||
                    hasNodeByText(rootNode, "Solicitud de viaje")
                )
    }

    private fun isTripListVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container").isNotEmpty()
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
        removeDetailProfitabilityOverlay()
        if (isDebugDiagnosticsEnabled()) {
            Log.d(TAG_FLOW, "FLOW_RESET attempt=$detailFlowAttempt reason=$reason previousStage=$detailFlowStage pending=${pendingTripClick?.fingerprint ?: "none"}")
        } else {
            Log.d(TAG_FLOW, "FLOW_RESET attempt=$detailFlowAttempt reason=$reason previousStage=$detailFlowStage")
        }
        detailFlowStage = DetailFlowStage.IDLE
        pendingTripClick = null
        activeFlowContext = null
        listOverlayRenderingBlocked = false
        listWithoutModalEventCount = 0
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
        if (isDebugDiagnosticsEnabled()) {
            Log.w(
                TAG_FLOW,
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

    private fun isDebugDiagnosticsEnabled(): Boolean {
        return ::settingsManager.isInitialized && settingsManager.structuralProbeDebugEnabled
    }

    private inline fun logFlowDebug(message: () -> String) {
        if (isDebugDiagnosticsEnabled()) {
            Log.d(TAG_FLOW, message())
        }
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

    private fun processMainFlow() {
        if (!::settingsManager.isInitialized) return
        val rootNode = rootInActiveWindow ?: return

        if (isTripDetailFlowActive() || listOverlayRenderingBlocked || isTripDetailModalVisible(rootNode)) {
            clearAllOverlays()
            logFlowDebug {
                "LIST_OVERLAY_RENDER_BLOCKED stage=$detailFlowStage blocked=$listOverlayRenderingBlocked " +
                    "modalVisible=${isTripDetailModalVisible(rootNode)} activeOverlays=${activeOverlays.size}"
            }
            return
        }
        
        val nodes = rootNode.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_container")
        val foundKeysInThisScan = mutableSetOf<String>()

        val maxP = settingsManager.maxPickupDistance
        val minU = settingsManager.minUsdPerKm
        val comm = settingsManager.commissionPercent
        val previewTripDistance = settingsManager.previewTripDistanceKm

        for (node in nodes) {
            val trip = extractTripData(node) ?: continue
            val tripKey = trip.fingerprint
            foundKeysInThisScan.add(tripKey)

            val result = ProfitabilityEngine.calculate(
                tripPrice = trip.price,
                pickupDistanceKm = trip.pickupDistance,
                tripDistanceKm = previewTripDistance,
                maxPickupDistanceKm = maxP,
                minUsdPerKm = minU,
                commissionPercent = comm,
                isPreview = true
            )

            if (result.status != TripStatus.RENTABLE) {
                if (trip.pickupDistance > 0.05) executeDirectHide(node)
            } else {
                val currentBounds = Rect()
                node.getBoundsInScreen(currentBounds)
                syncOverlay(tripKey, currentBounds, result)
            }
        }

        val keysToRemove = activeOverlays.keys.filter { !foundKeysInThisScan.contains(it) }
        keysToRemove.forEach { removeOverlay(it) }
    }

    // SONDA AGNOSTICA: No busca valores, busca ESTRUCTURA
    private fun ejecutarSondaEstructural() {
        val root = rootInActiveWindow ?: return
        
        // Buscamos cualquier nodo cuyo ID termine en _PointB (el ancla de destino)
        recorrerNodosPorEstructura(root)
    }

    private fun recorrerNodosPorEstructura(node: AccessibilityNodeInfo?) {
        if (node == null) return
        
        val viewId = node.viewIdResourceName ?: ""
        
        // Si encontramos el ancla estructural del destino
        if (viewId.endsWith("_PointB")) {
            Log.e(TAG_SONDA, "ANCLA DETECTADA: $viewId")
            
            // Inspeccionamos al PADRE para ver a sus HERMANOS (donde suele estar el globo de texto)
            val parent = node.parent
            if (parent != null) {
                Log.e(TAG_SONDA, "Inspeccionando entorno del Punto B (Hermanos: \${parent.childCount})")
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i)
                    if (sibling != null) {
                        val sText = sibling.text?.toString() ?: "NoText"
                        val sDesc = sibling.contentDescription?.toString() ?: "NoDesc"
                        Log.e(TAG_SONDA, "  DATO ENTORNO -> Txt: '\$sText' | Desc: '\$sDesc' | Class: \${sibling.className}")
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            recorrerNodosPorEstructura(child)
        }
    }

    private fun syncOverlay(tripKey: String, bounds: Rect, result: ProfitabilityResult) {
        if (isTripDetailFlowActive() || listOverlayRenderingBlocked) return

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
                if (record.lastBounds != bounds) {
                    record.lastBounds = Rect(bounds)
                    windowManager.updateViewLayout(record.view, params)
                }
            } else {
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#E6004D00"))
                }
                updateHUDText(container, result)
                windowManager.addView(container, params)
                activeOverlays[tripKey] = OverlayRecord(container, container, Rect(bounds))
            }
        } catch (e: Exception) {}
    }

    private fun updateHUDText(container: LinearLayout, result: ProfitabilityResult) {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = "RENTABLE"
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
            text = "Gana: $" + String.format("%.2f", result.trueProfit)
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
        ocrResult: RouteLabelOcr.OcrResult
    ) {
        removeDetailProfitabilityOverlay()

        val displayMetrics = resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * 0.60).toInt()
        val overlayHeight = (112 * displayMetrics.density).toInt()
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

        updateDetailProfitabilityText(container, result, ocrResult)

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
                if (isDebugDiagnosticsEnabled()) {
                    "DETAIL_PROFITABILITY_OVERLAY_SHOWN ${flowContextLog()} " +
                        "status=${result.status} usdKm=${result.expectedUsdPerKm} profit=${result.trueProfit} " +
                        "pickupKm=${result.pickupDistanceKm} totalKm=${result.totalDistanceKm}"
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
        ocrResult: RouteLabelOcr.OcrResult
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
            text = String.format("%.2f $/km  |  Gana: $%.2f", result.expectedUsdPerKm, result.trueProfit)
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
    }

    private fun removeDetailProfitabilityOverlay() {
        detailProfitabilityOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
        }
        detailProfitabilityOverlay = null
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

    private fun removeOverlay(tripKey: String) {
        activeOverlays[tripKey]?.let {
            try { windowManager.removeView(it.view) } catch (e: Exception) {}
        }
        activeOverlays.remove(tripKey)
    }

    private fun clearAllOverlays() {
        val removedCount = activeOverlays.size
        val iterator = activeOverlays.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            try {
                windowManager.removeView(entry.value.view)
            } catch (e: Exception) {}
            iterator.remove()
        }
        if (removedCount > 0) {
            overlayClearGeneration += 1
            logFlowDebug { "OVERLAYS_CLEARED attempt=$detailFlowAttempt removed=$removedCount generation=$overlayClearGeneration" }
        }
    }

    private fun executeDirectHide(node: AccessibilityNodeInfo) {
        val hideBtn = node.findAccessibilityNodeInfosByViewId("sinet.startup.inDriver:id/item_order_options_container_hide").firstOrNull()
        if (hideBtn != null) {
            hideBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
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
            Trip(name.trim(), parseDoubleSafe(priceT), parseDoubleSafe(distT), from.trim(), "")
        } catch (e: Exception) { null }
    }

    private fun parseDoubleSafe(text: String): Double {
        val isMetro = text.contains("metro", ignoreCase = true)
        val cleaned = text.replace(",", ".").replace(Regex("[^0-9.]"), "")
        val value = cleaned.toDoubleOrNull() ?: 0.0
        return if (isMetro) value / 1000.0 else value
    }

    override fun onDestroy() {
        clearAllOverlays()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {}
}
