package com.efidriver.icarosnet.models

import java.util.UUID

/**
 * Representa un registro de viaje extraído de la UI de InDrive.
 */
data class Trip(
    val passengerName: String,
    val price: Double,
    val pickupDistance: Double,
    val fromAddress: String,
    val toAddress: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Genera una Huella Digital única para identificar el viaje aunque se mueva en la lista.
     * Esta es la 'Llave Maestra' que mencionamos.
     */
    val fingerprint: String by lazy {
        "${passengerName}_${fromAddress}"
            .replace("\\s".toRegex(), "") // Quitar espacios para normalizar
    }
}
