package com.efidriver.icarosnet.license

data class LicenseSnapshot(
    val licenseKey: String?,
    val licenseId: String?,
    val product: String?,
    val machineId: String,
    val deviceCode: String,
    val valid: Boolean,
    val expiresAtEpochMs: Long?,
    val lastValidatedAtEpochMs: Long?
) {
    fun isUsable(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        if (!valid) return false
        val expiresAt = expiresAtEpochMs ?: return false
        if (expiresAt <= nowEpochMs) return false
        val lastValidated = lastValidatedAtEpochMs ?: return false
        return nowEpochMs - lastValidated <= LicenseConstants.OFFLINE_GRACE_MS
    }
}

data class LicenseOperationResult(
    val success: Boolean,
    val message: String,
    val snapshot: LicenseSnapshot? = null
)
