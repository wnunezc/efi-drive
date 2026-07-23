package com.efidriver.icarosnet.engine

import com.efidriver.icarosnet.models.TripStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfitabilityEngineTest {

    @Test
    fun calculate_doesNotMarkRoundedNegativeProfitAsRentable() {
        val result = ProfitabilityEngine.calculate(
            tripPrice = 3.752941,
            pickupDistanceKm = 2.0,
            tripDistanceKm = 2.0,
            maxPickupDistanceKm = 2.5,
            minUsdPerKm = 0.8,
            commissionPercent = 15.0,
            isPreview = true
        )

        assertEquals(0.80, result.expectedUsdPerKm, 0.0)
        assertEquals(-0.01, result.trueProfit, 0.0)
        assertEquals(TripStatus.NOT_RENTABLE, result.status)
    }

    @Test
    fun calculate_marksZeroOrPositiveProfitAsRentableWhenPickupIsAccepted() {
        val result = ProfitabilityEngine.calculate(
            tripPrice = 2.56,
            pickupDistanceKm = 1.2,
            tripDistanceKm = 1.5,
            maxPickupDistanceKm = 2.5,
            minUsdPerKm = 0.8,
            commissionPercent = 15.0,
            isPreview = true
        )

        assertEquals(0.81, result.expectedUsdPerKm, 0.0)
        assertEquals(0.02, result.trueProfit, 0.0)
        assertEquals(TripStatus.RENTABLE, result.status)
    }
}
