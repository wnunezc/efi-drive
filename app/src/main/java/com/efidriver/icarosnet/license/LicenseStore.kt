package com.efidriver.icarosnet.license

import android.content.Context

class LicenseStore(context: Context) {
    private val identityManager = DeviceIdentityManager(context)
    private val securePrefs = SecurePrefs(context, "efi_license_store")

    fun getSnapshot(): LicenseSnapshot {
        return LicenseSnapshot(
            licenseKey = securePrefs.getString(KEY_LICENSE_KEY),
            licenseId = securePrefs.getString(KEY_LICENSE_ID),
            product = securePrefs.getString(KEY_PRODUCT),
            machineId = identityManager.getMachineId(),
            deviceCode = identityManager.getDeviceCode(),
            valid = securePrefs.getBoolean(KEY_VALID, false),
            expiresAtEpochMs = securePrefs.getLong(KEY_EXPIRES_AT),
            lastValidatedAtEpochMs = securePrefs.getLong(KEY_LAST_VALIDATED_AT)
        )
    }

    fun saveValidLicense(
        licenseKey: String,
        licenseId: String?,
        product: String?,
        expiresAtEpochMs: Long?,
        validatedAtEpochMs: Long
    ) {
        securePrefs.putString(KEY_LICENSE_KEY, licenseKey)
        securePrefs.putString(KEY_LICENSE_ID, licenseId)
        securePrefs.putString(KEY_PRODUCT, product)
        securePrefs.putBoolean(KEY_VALID, true)
        securePrefs.putLong(KEY_EXPIRES_AT, expiresAtEpochMs)
        securePrefs.putLong(KEY_LAST_VALIDATED_AT, validatedAtEpochMs)
    }

    fun markInvalid() {
        securePrefs.putBoolean(KEY_VALID, false)
        securePrefs.putLong(KEY_LAST_VALIDATED_AT, System.currentTimeMillis())
    }

    fun clear() {
        securePrefs.clear()
    }

    private companion object {
        const val KEY_LICENSE_KEY = "license_key"
        const val KEY_LICENSE_ID = "license_id"
        const val KEY_PRODUCT = "product"
        const val KEY_VALID = "valid"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_LAST_VALIDATED_AT = "last_validated_at"
    }
}
