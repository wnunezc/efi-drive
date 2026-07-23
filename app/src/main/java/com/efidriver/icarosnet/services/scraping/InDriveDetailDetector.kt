package com.efidriver.icarosnet.services.scraping

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InDriveDetailDetector {
    fun isTripDetailWindowEvent(event: AccessibilityEvent): Boolean {
        return event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString() == DETAIL_WINDOW_CLASS
    }

    fun isGoogleMapEvent(event: AccessibilityEvent): Boolean {
        return event.className?.toString() == "android.view.TextureView" &&
            event.contentDescription?.toString()?.contains("Mapa de Google", ignoreCase = true) == true
    }

    fun isGoogleMapVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return hasNodeMatching(rootNode) { node ->
            node.className?.toString() == "android.view.TextureView" &&
                node.contentDescription?.toString()?.contains("Mapa de Google", ignoreCase = true) == true
        }
    }

    fun isTripDetailShellVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return hasNodeByText(rootNode, "Solicitud de viaje") ||
            hasNodeById(rootNode, ID_BUTTON_OFFER) ||
            isGoogleMapVisible(rootNode)
    }

    fun isTripDetailModalVisible(rootNode: AccessibilityNodeInfo): Boolean {
        val hasDetailHeader = hasNodeByText(rootNode, "Solicitud de viaje") ||
            hasNodeById(rootNode, ID_BUTTON_OFFER)
        val hasDetailTripData = hasNodeById(rootNode, ID_HEADER_PRICE) ||
            hasNodeById(rootNode, ID_HEADER_DISTANCE) ||
            hasNodeById(rootNode, ID_PICKUP_ADDRESS) ||
            hasNodeById(rootNode, ID_DESTINATION_ADDRESS)

        return hasDetailHeader && hasDetailTripData
    }

    fun findTextById(rootNode: AccessibilityNodeInfo, viewId: String): String? {
        return rootNode.findAccessibilityNodeInfosByViewId(viewId)
            .firstOrNull()
            ?.text
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun collectNodeTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val texts = mutableListOf<String>()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        for (index in 0 until node.childCount) {
            texts.addAll(collectNodeTexts(node.getChild(index)))
        }
        return texts
    }

    private fun hasNodeById(rootNode: AccessibilityNodeInfo, viewId: String): Boolean {
        return rootNode.findAccessibilityNodeInfosByViewId(viewId).isNotEmpty()
    }

    private fun hasNodeByText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        return rootNode.findAccessibilityNodeInfosByText(text).isNotEmpty()
    }

    private fun hasNodeMatching(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (node == null) return false
        if (predicate(node)) return true
        for (index in 0 until node.childCount) {
            if (hasNodeMatching(node.getChild(index), predicate)) return true
        }
        return false
    }

    companion object {
        const val ID_HEADER_DISTANCE = "sinet.startup.inDriver:id/order_info_header_text_distance"
        const val ID_HEADER_PRICE = "sinet.startup.inDriver:id/order_info_header_text_price"
        const val ID_PICKUP_ADDRESS = "sinet.startup.inDriver:id/order_info_address_text_pickup"
        const val ID_DESTINATION_ADDRESS = "sinet.startup.inDriver:id/order_info_address_text_destination"
        const val ID_USER_NAME = "sinet.startup.inDriver:id/user_info_text_name"
        private const val ID_BUTTON_OFFER = "sinet.startup.inDriver:id/button_offer"
        private const val DETAIL_WINDOW_CLASS = "ya6"
    }
}
