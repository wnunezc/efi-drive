package com.efidriver.icarosnet.services.detail

import com.efidriver.icarosnet.services.scraping.PendingTripClick

data class TripFlowContext(
    val attemptId: Long,
    val trip: PendingTripClick
) {
    val fingerprint: String
        get() = trip.fingerprint
}
