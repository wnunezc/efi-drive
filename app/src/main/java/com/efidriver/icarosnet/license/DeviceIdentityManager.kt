package com.efidriver.icarosnet.license

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class DeviceIdentityManager(private val context: Context) {
    private val securePrefs = SecurePrefs(context, "efi_device_identity")

    fun getMachineId(): String {
        val stableDeviceSeed = getAndroidId() ?: getOrCreateFallbackSecret()
        return sha256Hex("${LicenseConstants.PRODUCT}:${context.packageName}:$stableDeviceSeed")
    }

    fun getDeviceCode(): String {
        val hash = getMachineId().uppercase()
        return "EFI-${hash.substring(0, 4)}-${hash.substring(4, 8)}-${hash.substring(8, 12)}"
    }

    private fun getAndroidId(): String? {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
    }

    private fun getOrCreateFallbackSecret(): String {
        securePrefs.getString(KEY_SECRET)?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val secret = Base64.encodeToString(bytes, Base64.NO_WRAP)
        securePrefs.putString(KEY_SECRET, secret)
        return secret
    }

    private fun sha256Hex(value: String): String {
        return sha256Hex(value.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_SECRET = "device_secret"
    }
}
