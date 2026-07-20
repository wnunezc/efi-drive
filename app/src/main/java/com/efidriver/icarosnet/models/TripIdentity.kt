package com.efidriver.icarosnet.models

data class TripIdentity(
    val passengerName: String,
    val pickupAddress: String,
    val destinationAddress: String?
) {
    val strongKey: String? = destinationAddress
        ?.takeIf { it.isNotBlank() }
        ?.let { normalize(passengerName, pickupAddress, it) }

    val weakKey: String = normalize(passengerName, pickupAddress)

    val bestKey: String = strongKey ?: weakKey

    companion object {
        fun from(
            passengerName: String,
            pickupAddress: String,
            destinationAddress: String? = null
        ): TripIdentity {
            return TripIdentity(
                passengerName = passengerName.trim(),
                pickupAddress = pickupAddress.trim(),
                destinationAddress = destinationAddress?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        fun normalize(vararg parts: String): String {
            return parts
                .map { it.lowercase().replace("\\s".toRegex(), "") }
                .joinToString("|")
        }
    }
}
