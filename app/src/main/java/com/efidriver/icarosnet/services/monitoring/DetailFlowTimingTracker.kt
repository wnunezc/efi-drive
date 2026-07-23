package com.efidriver.icarosnet.services.monitoring

class DetailFlowTimingTracker {
    data class FlowTiming(
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
        var profitabilityStartedAt: Long = 0L,
        var profitabilityCompletedAt: Long = 0L,
        var offerStartedAt: Long = 0L,
        var offerCompletedAt: Long = 0L,
        var overlayShowStartedAt: Long = 0L,
        var overlayShownAt: Long = 0L
    )

    private val timings = mutableMapOf<Long, FlowTiming>()

    fun timingFor(attemptId: Long): FlowTiming {
        return timings.getOrPut(attemptId) { FlowTiming() }
    }

    fun prune(currentAttempt: Long) {
        val oldestToKeep = currentAttempt - 20
        timings.keys.filter { it < oldestToKeep }.forEach { timings.remove(it) }
    }

    fun format(timing: FlowTiming): String {
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
            "ocrResultToProfit=${deltaMs(timing.ocrCompletedAt, timing.profitabilityCompletedAt) ?: "na"}ms " +
            "profitDuration=${deltaMs(timing.profitabilityStartedAt, timing.profitabilityCompletedAt) ?: "na"}ms " +
            "offerDuration=${deltaMs(timing.offerStartedAt, timing.offerCompletedAt) ?: "na"}ms " +
            "ocrResultToOverlay=${deltaMs(timing.ocrCompletedAt, timing.overlayShownAt) ?: "na"}ms " +
            "overlayPaintDuration=${deltaMs(timing.overlayShowStartedAt, timing.overlayShownAt) ?: "na"}ms " +
            "clickToOverlay=${deltaMs(timing.cardClickedAt, timing.overlayShownAt) ?: "na"}ms"
    }

    private fun deltaMs(from: Long, to: Long): Long? {
        return if (from > 0L && to > 0L) to - from else null
    }
}
