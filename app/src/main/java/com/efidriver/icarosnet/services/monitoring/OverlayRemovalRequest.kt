package com.efidriver.icarosnet.services.monitoring

data class OverlayRemovalRequest(
    val type: OverlayRemovalType,
    val reason: String,
    val trigger: String,
    val fallback: String? = null,
    val rowsFound: Int? = null,
    val keysFound: List<String> = emptyList(),
    val targetKey: String? = null
)
