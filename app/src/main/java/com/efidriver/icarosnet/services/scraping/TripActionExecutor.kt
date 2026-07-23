package com.efidriver.icarosnet.services.scraping

import android.view.accessibility.AccessibilityNodeInfo

class TripActionExecutor(
    private val trace: (String) -> Unit
) {
    fun hideTrip(node: AccessibilityNodeInfo, reason: String) {
        val hideButton = node.findAccessibilityNodeInfosByViewId(ID_HIDE).firstOrNull()
        if (hideButton != null) {
            trace("LIST_TRIP_HIDE_DIRECT reason=$reason action=hide_button")
            hideButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            trace("LIST_TRIP_HIDE_DIRECT reason=$reason action=dots_menu")
            node.findAccessibilityNodeInfosByViewId(ID_DOTS)
                .firstOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private companion object {
        private const val ID_HIDE = "sinet.startup.inDriver:id/item_order_options_container_hide"
        private const val ID_DOTS = "sinet.startup.inDriver:id/item_order_imageview_dots"
    }
}
