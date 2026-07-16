package com.efidriver.icarosnet.models

enum class TripStatus {
    RENTABLE,
    NOT_RENTABLE,
    NOT_RENTABLE_PICKUP
}

data class ProfitabilityResult(
    val status: TripStatus,
    val expectedIncome: Double,     // Neto tras comisión
    val expectedUsdPerKm: Double,   // Rentabilidad $/km
    val totalDistanceKm: Double,    // Distancia estimada
    val trueProfit: Double,         // GANANCIA REAL (Neto - Costo base)
    val pickupDistanceKm: Double,   // Distancia de recogida real
    val isPreview: Boolean,         // FLAG: Indica si es cálculo basado en lista (estimado)
    val pickupAccepted: Boolean,
    val tripAccepted: Boolean
)
