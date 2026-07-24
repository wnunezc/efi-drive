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
        // Fase 1: Búsqueda robusta por ID en todo el árbol (vuelve a ser estable)
        val rowNodes = rootNode.findAccessibilityNodeInfosByViewId(ID_ITEM_ORDER_CONTAINER)
        if (rowNodes.isEmpty()) return emptyList()

        return rowNodes.mapNotNull { node ->
            // Filtro de coordenadas básico: solo procesar si el nodo tiene dimensiones válidas
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return@mapNotNull null

            val trip = extractTripDataRobust(node) ?: return@mapNotNull null
            TripRow(node, trip, bounds)
        }
    }

    fun isTripListVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return rootNode.findAccessibilityNodeInfosByViewId(ID_ITEM_ORDER_CONTAINER).isNotEmpty()
    }

    fun isTripSearchEmptyStateVisible(rootNode: AccessibilityNodeInfo): Boolean {
        return rootNode.findAccessibilityNodeInfosByText("Buscando en un").any { 
            val txt = it.text?.toString() ?: ""
            txt.contains("área", ignoreCase = true) || txt.contains("area", ignoreCase = true)
        }
    }

    /**
     * Extracción robusta de datos: combina búsqueda directa para el nombre (ancla) 
     * con un recorrido ligero para el resto de datos.
     */
    private fun extractTripDataRobust(rowNode: AccessibilityNodeInfo): Trip? {
        // Primero intentamos por ID exacto, si no, buscamos el primer texto disponible (Fallback)
        val nameNode = rowNode.findAccessibilityNodeInfosByViewId(ID_NAME).firstOrNull()
        val name = nameNode?.text?.toString() ?: findFirstText(rowNode) ?: return null

        var priceText: String? = null
        var distanceText: String? = null
        var from: String? = null
        var to: String? = null

        fun findRemaining(node: AccessibilityNodeInfo) {
            val viewId = node.viewIdResourceName ?: ""
            val txt = node.text?.toString() ?: ""
            
            when {
                viewId.endsWith("info_textview_stage_price_view") || txt.contains("$") -> {
                    if (priceText == null) priceText = txt
                }
                viewId.endsWith("order_info_stage_textview_distance") || txt.contains("km") || txt.contains("metro") -> {
                    if (distanceText == null) distanceText = txt
                }
                viewId.endsWith("order_info_textview_from_address") -> from = txt
                viewId.contains("to_address") || viewId.contains("destination_address") -> to = txt
            }
            
            if (priceText != null && distanceText != null && from != null && to != null) return
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { findRemaining(it) }
            }
        }

        findRemaining(rowNode)

        return Trip(
            passengerName = name.trim(),
            price = parseDoubleSafe(priceText ?: ""),
            pickupDistance = parseDoubleSafe(distanceText ?: ""),
            fromAddress = from?.trim() ?: "",
            toAddress = to?.trim() ?: ""
        )
    }

    private fun findFirstText(node: AccessibilityNodeInfo): String? {
        if (!node.text.isNullOrBlank()) return node.text.toString()
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findFirstText(it) }
            if (found != null) return found
        }
        return null
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
    }
}
