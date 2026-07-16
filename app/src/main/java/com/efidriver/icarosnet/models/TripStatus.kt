package com.efidriver.icarosnet.models

enum class TripStatus {
    RENTABLE,
    NOT_RENTABLE,
    NOT_RENTABLE_PICKUP
}

data class ProfitabilityResult(
    val status: TripStatus,
    val expectedIncome: Double,
    val expectedUsdPerKm: Double,
    val totalDistanceKm: Double,
    val pickupAccepted: Boolean,
    val tripAccepted: Boolean
)
