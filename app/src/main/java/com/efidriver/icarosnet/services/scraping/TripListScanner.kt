package com.efidriver.icarosnet.services.scraping

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.efidriver.icarosnet.models.Trip

class TripListScanner {
    data class TripRow(
        val node: AccessibilityNodeInfo,
        val trip: Trip,
        val bounds: Rect
    )

    fun scan(rootNode: AccessibilityNodeInfo): List<TripRow> {
        return rootNode.findAccessibilityNodeInfosByViewId(ID_ITEM_ORDER_CONTAINER)
            .mapNotNull { node ->
                val trip = extractTripData(node) ?: return@mapNotNull null
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                TripRow(node, trip, bounds)
            }
    }

    fun isTripListVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return rootNode.findAccessibilityNodeInfosByViewId(ID_ITEM_ORDER_CONTAINER).isNotEmpty()
    }

    fun isTripSearchEmptyStateVisible(rootNode: AccessibilityNodeInfo): Boolean {
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

    private fun extractTripData(node: AccessibilityNodeInfo): Trip? {
        return try {
            val name = node.findAccessibilityNodeInfosByViewId(ID_NAME).firstOrNull()?.text?.toString() ?: return null
            val priceText = node.findAccessibilityNodeInfosByViewId(ID_PRICE).firstOrNull()?.text?.toString() ?: ""
            val distanceText = node.findAccessibilityNodeInfosByViewId(ID_DISTANCE).firstOrNull()?.text?.toString() ?: ""
            val from = node.findAccessibilityNodeInfosByViewId(ID_FROM).firstOrNull()?.text?.toString() ?: ""
            val to = findFirstTextByIds(node, ID_TO, ID_DESTINATION, ID_STAGE_TO) ?: ""
            Trip(name.trim(), parseDoubleSafe(priceText), parseDoubleSafe(distanceText), from.trim(), to.trim())
        } catch (e: Exception) {
            null
        }
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

    private fun collectNodeTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val texts = mutableListOf<String>()
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        for (index in 0 until node.childCount) {
            texts.addAll(collectNodeTexts(node.getChild(index)))
        }
        return texts
    }

    private fun hasNodeByText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        return rootNode.findAccessibilityNodeInfosByText(text).isNotEmpty()
    }

    private fun parseDoubleSafe(text: String): Double {
        val isMetro = text.contains("metro", ignoreCase = true)
        val cleaned = text.replace(",", ".").replace(Regex("[^0-9.]"), "")
        val value = cleaned.toDoubleOrNull() ?: 0.0
        return if (isMetro) value / 1000.0 else value
    }

    private companion object {
        private const val ID_ITEM_ORDER_CONTAINER = "sinet.startup.inDriver:id/item_order_container"
        private const val ID_NAME = "sinet.startup.inDriver:id/driver_common_textview_name"
        private const val ID_PRICE = "sinet.startup.inDriver:id/info_textview_stage_price_view"
        private const val ID_DISTANCE = "sinet.startup.inDriver:id/order_info_stage_textview_distance"
        private const val ID_FROM = "sinet.startup.inDriver:id/order_info_textview_from_address"
        private const val ID_TO = "sinet.startup.inDriver:id/order_info_textview_to_address"
        private const val ID_DESTINATION = "sinet.startup.inDriver:id/order_info_textview_destination_address"
        private const val ID_STAGE_TO = "sinet.startup.inDriver:id/order_info_stage_textview_to_address"
    }
}
