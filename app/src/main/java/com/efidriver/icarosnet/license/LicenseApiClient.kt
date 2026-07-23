package com.efidriver.icarosnet.license

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LicenseApiClient(
    private val baseUrl: String = LicenseConstants.BASE_URL
) {
    fun activate(licenseKey: String, machineId: String): LicenseApiResponse {
        return postLicenseRequest("/api/licenses/activate", licenseKey, machineId)
    }

    fun validate(licenseKey: String, machineId: String): LicenseApiResponse {
        return postLicenseRequest("/api/licenses/validate", licenseKey, machineId)
    }

    private fun postLicenseRequest(
        path: String,
        licenseKey: String,
        machineId: String
    ): LicenseApiResponse {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "EfiDriver/1.0 Android")
        }

        return try {
            val payload = JSONObject()
                .put("license_key", licenseKey)
                .put("product", LicenseConstants.PRODUCT)
                .put("machine_id", machineId)
                .toString()

            connection.outputStream.use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val body = readBody(connection)
            parseResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        return stream.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        }
    }

    private fun parseResponse(statusCode: Int, body: String): LicenseApiResponse {
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json == null) {
            return LicenseApiResponse(
                valid = false,
                message = "Respuesta inválida del servidor ($statusCode).",
                httpStatusCode = statusCode
            )
        }

        return LicenseApiResponse(
            valid = json.optBoolean("valid", false),
            message = json.optString("message", if (statusCode in 200..299) "OK" else "Error $statusCode"),
            httpStatusCode = statusCode,
            licenseId = json.optNullableString("license_id"),
            product = json.optNullableString("product"),
            expiresAt = json.optNullableString("expires_at"),
            revokedAt = json.optNullableString("revoked_at")
        )
    }
}

data class LicenseApiResponse(
    val valid: Boolean,
    val message: String,
    val httpStatusCode: Int,
    val licenseId: String? = null,
    val product: String? = null,
    val expiresAt: String? = null,
    val revokedAt: String? = null
)

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name).takeIf { it.isNotBlank() }
}
