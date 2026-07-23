package com.efidriver.icarosnet.license

import android.content.Context
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class AppLicenseManager(context: Context) {
    private val store = LicenseStore(context.applicationContext)
    private val apiClient = LicenseApiClient()

    fun getSnapshot(): LicenseSnapshot = store.getSnapshot()

    fun hasUsableLicense(): Boolean = getSnapshot().isUsable()

    fun activate(licenseKey: String): LicenseOperationResult {
        val cleanKey = licenseKey.trim()
        if (cleanKey.isBlank()) {
            return LicenseOperationResult(false, "Ingresa una llave de licencia.")
        }

        return runCatching {
            val current = store.getSnapshot()
            val response = apiClient.activate(cleanKey, current.machineId)
            handleServerResponse(cleanKey, response)
        }.getOrElse { error ->
            val snapshot = store.getSnapshot()
            if (snapshot.isUsable()) {
                LicenseOperationResult(
                    success = true,
                    message = "Sin conexión con el servidor. Se mantiene la licencia local vigente.",
                    snapshot = snapshot
                )
            } else {
                LicenseOperationResult(false, "No se pudo activar: ${error.safeMessage()}", snapshot)
            }
        }
    }

    fun validateStored(): LicenseOperationResult {
        val snapshot = store.getSnapshot()
        val licenseKey = snapshot.licenseKey
            ?: return LicenseOperationResult(false, "No hay licencia guardada.", snapshot)

        return runCatching {
            val response = apiClient.validate(licenseKey, snapshot.machineId)
            handleServerResponse(licenseKey, response)
        }.getOrElse { error ->
            val fallback = store.getSnapshot()
            if (fallback.isUsable()) {
                LicenseOperationResult(
                    success = true,
                    message = "No se pudo contactar el servidor. Licencia local todavía vigente.",
                    snapshot = fallback
                )
            } else {
                LicenseOperationResult(false, "Validación fallida: ${error.safeMessage()}", fallback)
            }
        }
    }

    fun clearLicense(): LicenseSnapshot {
        store.clear()
        return store.getSnapshot()
    }

    private fun handleServerResponse(
        licenseKey: String,
        response: LicenseApiResponse
    ): LicenseOperationResult {
        if (!response.valid || response.revokedAt != null) {
            store.markInvalid()
            return LicenseOperationResult(false, response.message, store.getSnapshot())
        }

        val expiresAtEpochMs = response.expiresAt?.toEpochMsOrNull()
        if (expiresAtEpochMs == null || expiresAtEpochMs <= System.currentTimeMillis()) {
            store.markInvalid()
            return LicenseOperationResult(false, "La licencia no tiene una fecha de vencimiento válida.", store.getSnapshot())
        }

        store.saveValidLicense(
            licenseKey = licenseKey,
            licenseId = response.licenseId,
            product = response.product,
            expiresAtEpochMs = expiresAtEpochMs,
            validatedAtEpochMs = System.currentTimeMillis()
        )
        return LicenseOperationResult(true, response.message, store.getSnapshot())
    }

    private fun String.toEpochMsOrNull(): Long? {
        return try {
            OffsetDateTime.parse(this).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun Throwable.safeMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }
}
