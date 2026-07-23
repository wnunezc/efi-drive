package com.efidriver.icarosnet.services.monitoring

data class OverlayRemovalTriggerEvidence(
    val wallTimeMs: Long,
    val elapsedMs: Long,
    val type: OverlayRemovalType,
    val reason: String,
    val trigger: String,
    val fallback: String?,
    val eventType: Int?,
    val eventClass: String?,
    val packageName: String?,
    val detailFlowStage: String,
    val listOverlayRenderingBlocked: Boolean,
    val tripDetailFlowActive: Boolean,
    val tripListVisible: Boolean?,
    val detailModalVisible: Boolean?,
    val detailShellVisible: Boolean?,
    val rowsFound: Int?,
    val activeBefore: Int,
    val trackedViewsBefore: Int,
    val keysBefore: List<String>,
    val keysFound: List<String>,
    val targetKey: String?
)
