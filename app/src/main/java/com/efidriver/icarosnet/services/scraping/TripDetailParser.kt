package com.efidriver.icarosnet.services.scraping

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TripDetailParser(
    private val detailDetector: InDriveDetailDetector
) {
    fun extractClickedTrip(event: AccessibilityEvent): PendingTripClick? {
        if (isTripClickFromIgnoredSurface(event.source)) return null

        val texts = event.text.map { it.toString().trim() }.filter { it.isNotEmpty() }
        val joined = texts.joinToString("|")
        if (!joined.contains("$")) return null
        if (!joined.contains("Seleccionar en el mapa", ignoreCase = true)) return null

        val passengerName = texts.firstOrNull() ?: return null
        val pickupDistanceText = texts.firstOrNull {
            it.contains("km", ignoreCase = true) || it.contains("metro", ignoreCase = true)
        } ?: return null
        val priceText = texts.firstOrNull { it.contains("$") } ?: return null
        val priceIndex = texts.indexOf(priceText)
        val pickupAddress = texts.drop(priceIndex + 1).firstOrNull {
            !isIgnoredListText(it)
        } ?: return null
        val pickupIndex = texts.indexOf(pickupAddress)
        val destinationAddress = texts.drop(pickupIndex + 1).firstOrNull {
            !isIgnoredListText(it)
        } ?: return null

        return PendingTripClick(
            passengerName = passengerName,
            pickupDistanceText = pickupDistanceText,
            priceText = priceText,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress
        )
    }

    fun isTripClickFromIgnoredSurface(source: AccessibilityNodeInfo?): Boolean {
        var current = source
        var depth = 0
        while (current != null && depth < 8) {
            val viewId = current.viewIdResourceName.orEmpty()
            if (
                viewId.endsWith("driver_common_imageview_avatar") ||
                viewId.endsWith("item_order_userinfoview") ||
                viewId.endsWith("item_order_imageview_dots") ||
                viewId.endsWith("item_order_options_container_hide")
            ) {
                return true
            }
            if (
                viewId.endsWith("item_order_container_info") ||
                viewId.endsWith("info_textview_stage_price_view") ||
                viewId.endsWith("order_info_textview_from_address") ||
                viewId.endsWith("order_info_textview_to_address") ||
                viewId.endsWith("order_info_textview_to_addresses")
            ) {
                return false
            }
            current = current.parent
            depth += 1
        }
        return false
    }

    fun extractModalTrip(rootNode: AccessibilityNodeInfo): PendingTripClick? {
        val passengerName = detailDetector.findTextById(rootNode, InDriveDetailDetector.ID_USER_NAME) ?: return null
        val pickupDistanceText = detailDetector.findTextById(rootNode, InDriveDetailDetector.ID_HEADER_DISTANCE) ?: return null
        val priceText = detailDetector.findTextById(rootNode, InDriveDetailDetector.ID_HEADER_PRICE) ?: return null
        val pickupAddress = detailDetector.findTextById(rootNode, InDriveDetailDetector.ID_PICKUP_ADDRESS) ?: return null
        val destinationAddress = detailDetector.findTextById(rootNode, InDriveDetailDetector.ID_DESTINATION_ADDRESS) ?: return null
        val offerPriceTexts = extractOfferPriceTexts(rootNode, priceText)

        return PendingTripClick(
            passengerName = passengerName,
            pickupDistanceText = pickupDistanceText,
            priceText = priceText,
            pickupAddress = pickupAddress,
            destinationAddress = destinationAddress,
            offerPriceTexts = offerPriceTexts
        )
    }

    fun extractOfferPriceTexts(rootNode: AccessibilityNodeInfo, basePriceText: String): List<String> {
        val basePrice = parsePriceText(basePriceText) ?: return emptyList()
        return detailDetector.collectNodeTexts(rootNode)
            .filter { it.contains("$") }
            .mapNotNull { text ->
                val price = parsePriceText(text) ?: return@mapNotNull null
                if (price > basePrice + 0.001) text else null
            }
            .distinctBy { parsePriceText(it) }
    }

    fun parsePriceText(priceText: String): Double? {
        return Regex("""\d+(?:[,.]\d+)?""")
            .find(priceText)
            ?.value
            ?.replace(",", ".")
            ?.toDoubleOrNull()
    }

    private fun isIgnoredListText(text: String): Boolean {
        return text.equals("Precio justo", ignoreCase = true) ||
            text.equals("Yappy", ignoreCase = true) ||
            text.equals("Quejarse", ignoreCase = true) ||
            text.equals("Ocultar", ignoreCase = true) ||
            text.equals("Seleccionar en el mapa", ignoreCase = true)
    }
}
