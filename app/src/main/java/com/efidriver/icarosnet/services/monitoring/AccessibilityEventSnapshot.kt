package com.efidriver.icarosnet.services.monitoring

import android.view.accessibility.AccessibilityEvent

data class AccessibilityEventSnapshot(
    val eventType: Int?,
    val eventClass: String?,
    val packageName: String?
) {
    companion object {
        fun from(event: AccessibilityEvent?): AccessibilityEventSnapshot {
            return AccessibilityEventSnapshot(
                eventType = event?.eventType,
                eventClass = event?.className?.toString(),
                packageName = event?.packageName?.toString()
            )
        }
    }
}
