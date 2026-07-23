package com.efidriver.icarosnet.services.profitability

import com.efidriver.icarosnet.engine.ProfitabilityEngine
import com.efidriver.icarosnet.engine.SettingsManager
import com.efidriver.icarosnet.engine.TripEvaluationCache
import com.efidriver.icarosnet.engine.TripEvaluationKind
import com.efidriver.icarosnet.engine.TripEvaluationSnapshot
import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.models.TripStatus
import com.efidriver.icarosnet.services.scraping.PendingTripClick
import com.efidriver.icarosnet.services.scraping.TripDetailParser
import com.efidriver.icarosnet.vision.RouteLabelOcr

class DetailProfitabilityCoordinator(
    private val settingsManager: SettingsManager,
    private val tripEvaluationCache: TripEvaluationCache,
    private val tripDetailParser: TripDetailParser,
    private val nowMs: () -> Long
) {
    data class OfferRecommendation(
        val price: Double,
        val profitability: ProfitabilityResult
    )

    private data class SettingsSnapshot(
        val maxPickupDistanceKm: Double,
        val minUsdPerKm: Double,
        val commissionPercent: Double
    )

    fun calculateRealProfitability(
        trip: PendingTripClick?,
        ocrResult: RouteLabelOcr.OcrResult
    ): ProfitabilityResult? {
        val tripPrice = trip?.priceText?.let(tripDetailParser::parsePriceText) ?: return null
        val pickupDistanceKm = ocrResult.pickup.distanceKm ?: return null
        val tripDistanceKm = ocrResult.destination.distanceKm ?: return null
        val settings = currentSettings()

        return ProfitabilityEngine.calculate(
            tripPrice = tripPrice,
            pickupDistanceKm = pickupDistanceKm,
            tripDistanceKm = tripDistanceKm,
            maxPickupDistanceKm = settings.maxPickupDistanceKm,
            minUsdPerKm = settings.minUsdPerKm,
            commissionPercent = settings.commissionPercent,
            isPreview = false
        )
    }

    fun storeRealTripEvaluation(
        trip: PendingTripClick?,
        ocrResult: RouteLabelOcr.OcrResult,
        profitability: ProfitabilityResult
    ): Boolean {
        trip ?: return false
        val price = tripDetailParser.parsePriceText(trip.priceText) ?: return false
        val pickupDistanceKm = ocrResult.pickup.distanceKm ?: return false
        val tripDistanceKm = ocrResult.destination.distanceKm ?: return false
        tripEvaluationCache.store(
            TripEvaluationSnapshot(
                identity = trip.identity,
                kind = TripEvaluationKind.REAL,
                profitability = profitability,
                price = price,
                pickupDistanceKm = pickupDistanceKm,
                tripDistanceKm = tripDistanceKm,
                pickupMinutes = ocrResult.pickup.minutes,
                tripMinutes = ocrResult.destination.minutes,
                updatedAtMs = nowMs()
            )
        )
        return true
    }

    fun findOfferRecommendation(
        trip: PendingTripClick?,
        ocrResult: RouteLabelOcr.OcrResult,
        currentProfitability: ProfitabilityResult,
        visibleOfferPriceTexts: List<String>
    ): OfferRecommendation? {
        if (currentProfitability.status == TripStatus.RENTABLE) return null

        trip ?: return null
        val basePrice = tripDetailParser.parsePriceText(trip.priceText) ?: return null
        val pickupDistanceKm = ocrResult.pickup.distanceKm ?: return null
        val tripDistanceKm = ocrResult.destination.distanceKm ?: return null
        val settings = currentSettings()

        return (trip.offerPriceTexts + visibleOfferPriceTexts)
            .mapNotNull(tripDetailParser::parsePriceText)
            .filter { it > basePrice + 0.001 }
            .distinct()
            .sorted()
            .firstNotNullOfOrNull { offerPrice ->
                val offerProfitability = ProfitabilityEngine.calculate(
                    tripPrice = offerPrice,
                    pickupDistanceKm = pickupDistanceKm,
                    tripDistanceKm = tripDistanceKm,
                    maxPickupDistanceKm = settings.maxPickupDistanceKm,
                    minUsdPerKm = settings.minUsdPerKm,
                    commissionPercent = settings.commissionPercent,
                    isPreview = false
                )
                if (offerProfitability.status == TripStatus.RENTABLE) {
                    OfferRecommendation(offerPrice, offerProfitability)
                } else {
                    null
                }
            }
    }

    private fun currentSettings(): SettingsSnapshot {
        return SettingsSnapshot(
            maxPickupDistanceKm = settingsManager.maxPickupDistance,
            minUsdPerKm = settingsManager.minUsdPerKm,
            commissionPercent = settingsManager.commissionPercent
        )
    }
}
