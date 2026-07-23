package com.efidriver.icarosnet.services.scraping

import com.efidriver.icarosnet.models.TripIdentity

data class PendingTripClick(
    val passengerName: String,
    val pickupDistanceText: String,
    val priceText: String,
    val pickupAddress: String,
    val destinationAddress: String,
    val offerPriceTexts: List<String> = emptyList()
) {
    val identity: TripIdentity = TripIdentity.from(passengerName, pickupAddress, destinationAddress)
    val fingerprint: String = identity.bestKey
}
