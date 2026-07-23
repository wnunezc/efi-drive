package com.efidriver.icarosnet.services.monitoring

import android.util.Log

class OverlayRemovalLogger(
    private val tag: String = TAG
) {
    fun logTrigger(evidence: OverlayRemovalTriggerEvidence) {
        Log.d(tag, formatTrigger(evidence))
    }

    fun log(evidence: OverlayRemovalEvidence) {
        Log.d(tag, format(evidence))
    }

    private fun formatTrigger(evidence: OverlayRemovalTriggerEvidence): String {
        return buildString {
            append("OVERLAY_REMOVAL_TRIGGER")
            append(" wallTimeMs=").append(evidence.wallTimeMs)
            append(" elapsedMs=").append(evidence.elapsedMs)
            append(" type=").append(evidence.type)
            append(" reason=").append(evidence.reason)
            append(" trigger=").append(evidence.trigger)
            append(" fallback=").append(evidence.fallback ?: "none")
            append(" eventType=").append(evidence.eventType ?: "none")
            append(" eventClass=").append(evidence.eventClass ?: "none")
            append(" package=").append(evidence.packageName ?: "none")
            append(" stage=").append(evidence.detailFlowStage)
            append(" blocked=").append(evidence.listOverlayRenderingBlocked)
            append(" detailFlowActive=").append(evidence.tripDetailFlowActive)
            append(" tripListVisible=").append(evidence.tripListVisible ?: "unknown")
            append(" detailModalVisible=").append(evidence.detailModalVisible ?: "unknown")
            append(" detailShellVisible=").append(evidence.detailShellVisible ?: "unknown")
            append(" rowsFound=").append(evidence.rowsFound ?: "unknown")
            append(" activeBefore=").append(evidence.activeBefore)
            append(" trackedBefore=").append(evidence.trackedViewsBefore)
            append(" targetKey=").append(evidence.targetKey ?: "none")
            append(" keysBefore=").append(compact(evidence.keysBefore))
            append(" keysFound=").append(compact(evidence.keysFound))
        }
    }

    private fun format(evidence: OverlayRemovalEvidence): String {
        return buildString {
            append("OVERLAY_REMOVAL_EVIDENCE")
            append(" wallTimeMs=").append(evidence.wallTimeMs)
            append(" elapsedMs=").append(evidence.elapsedMs)
            append(" type=").append(evidence.type)
            append(" reason=").append(evidence.reason)
            append(" trigger=").append(evidence.trigger)
            append(" fallback=").append(evidence.fallback ?: "none")
            append(" eventType=").append(evidence.eventType ?: "none")
            append(" eventClass=").append(evidence.eventClass ?: "none")
            append(" package=").append(evidence.packageName ?: "none")
            append(" stage=").append(evidence.detailFlowStage)
            append(" blocked=").append(evidence.listOverlayRenderingBlocked)
            append(" detailFlowActive=").append(evidence.tripDetailFlowActive)
            append(" tripListVisible=").append(evidence.tripListVisible ?: "unknown")
            append(" detailModalVisible=").append(evidence.detailModalVisible ?: "unknown")
            append(" detailShellVisible=").append(evidence.detailShellVisible ?: "unknown")
            append(" rowsFound=").append(evidence.rowsFound ?: "unknown")
            append(" activeBefore=").append(evidence.activeBefore)
            append(" trackedBefore=").append(evidence.trackedViewsBefore)
            append(" activeAfter=").append(evidence.activeAfter)
            append(" trackedAfter=").append(evidence.trackedViewsAfter)
            append(" targetKey=").append(evidence.targetKey ?: "none")
            append(" keysBefore=").append(compact(evidence.keysBefore))
            append(" keysFound=").append(compact(evidence.keysFound))
            append(" removedKeys=").append(compact(evidence.removedKeys))
            append(" durationMs=").append(evidence.durationMs ?: "unknown")
        }
    }

    private fun compact(keys: List<String>): String {
        if (keys.isEmpty()) return "[]"
        val visibleKeys = keys.take(MAX_KEYS).joinToString(",")
        return if (keys.size > MAX_KEYS) "[$visibleKeys,+${keys.size - MAX_KEYS}]" else "[$visibleKeys]"
    }

    private companion object {
        private const val TAG = "EfiRuntimeTrace"
        private const val MAX_KEYS = 5
    }
}
