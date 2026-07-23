package com.efidriver.icarosnet.engine

import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.models.TripStatus
import kotlin.math.round

object ProfitabilityEngine {

    fun calculate(
        tripPrice: Double,
        pickupDistanceKm: Double,
        tripDistanceKm: Double = 1.0, 
        maxPickupDistanceKm: Double,
        minUsdPerKm: Double,
        commissionPercent: Double,
        isPreview: Boolean = true
    ): ProfitabilityResult {
        
        // 1. Neto tras comisión
        val expectedIncome = tripPrice * (1.0 - (commissionPercent / 100.0))
        
        // 2. Distancia Total
        val totalDistanceKm = pickupDistanceKm + tripDistanceKm
        
        // 3. Rentabilidad $/km
        val rawUsdPerKm = if (totalDistanceKm > 0) expectedIncome / totalDistanceKm else 0.0
        val expectedUsdPerKm = round(rawUsdPerKm * 100.0) / 100.0
        
        // 4. GANANCIA REAL (Neto - (Distancia * Umbral))
        val operatingCost = totalDistanceKm * minUsdPerKm
        val rawTrueProfit = expectedIncome - operatingCost
        val trueProfit = round(rawTrueProfit * 100.0) / 100.0
        
        // 5. Verificación de Filtros
        val pickupAccepted = pickupDistanceKm <= maxPickupDistanceKm
        val tripAcceptedByPrice = trueProfit >= 0.0
        
        val status = when {
            !pickupAccepted -> TripStatus.NOT_RENTABLE_PICKUP
            !tripAcceptedByPrice -> TripStatus.NOT_RENTABLE
            else -> TripStatus.RENTABLE
        }
        
        return ProfitabilityResult(
            status = status,
            expectedIncome = expectedIncome,
            expectedUsdPerKm = expectedUsdPerKm,
            totalDistanceKm = totalDistanceKm,
            trueProfit = trueProfit,
            pickupDistanceKm = pickupDistanceKm,
            isPreview = isPreview,
            pickupAccepted = pickupAccepted,
            tripAccepted = (status == TripStatus.RENTABLE)
        )
    }
}
