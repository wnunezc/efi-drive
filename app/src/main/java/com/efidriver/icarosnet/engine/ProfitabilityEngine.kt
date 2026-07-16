package com.efidriver.icarosnet.engine

import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.models.TripStatus
import kotlin.math.round

object ProfitabilityEngine {

    /**
     * Calcula la rentabilidad de un viaje basado en los parámetros de entrada.
     * Implementación exacta del algoritmo de rentabilidad solicitado.
     */
    fun calculate(
        tripPrice: Double,
        pickupDistanceKm: Double,
        tripDistanceKm: Double = 1.0, // Valor por defecto sugerido para cálculo previo
        maxPickupDistanceKm: Double,
        minUsdPerKm: Double,
        commissionPercent: Double
    ): ProfitabilityResult {
        
        // Paso 1: Calcular ingreso real
        val expectedIncome = tripPrice * (1.0 - (commissionPercent / 100.0))
        
        // Paso 3: Calcular distancia total (Lo movemos arriba para tenerlo siempre)
        val totalDistanceKm = pickupDistanceKm + tripDistanceKm
        
        // Paso 4: Calcular ingreso por kilómetro (Evitar división por cero)
        val rawUsdPerKm = if (totalDistanceKm > 0) {
            expectedIncome / totalDistanceKm
        } else {
            0.0
        }
        
        // Redondear a 2 decimales para evitar decimales infinitos y comparaciones erróneas
        val expectedUsdPerKm = round(rawUsdPerKm * 100.0) / 100.0
        
        // Paso 2: Evaluar recogida (Regla de Oro)
        val pickupAccepted = pickupDistanceKm <= maxPickupDistanceKm
        
        // Paso 5: Determinar rentabilidad final por precio
        val tripAcceptedByPrice = expectedUsdPerKm >= minUsdPerKm
        
        // Determinación del estado final
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
            pickupAccepted = pickupAccepted,
            tripAccepted = (status == TripStatus.RENTABLE)
        )
    }
}
