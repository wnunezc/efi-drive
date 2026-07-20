package com.efidriver.icarosnet.engine

import com.efidriver.icarosnet.models.ProfitabilityResult
import com.efidriver.icarosnet.models.TripIdentity

enum class TripEvaluationKind {
    PREVIEW,
    REAL
}

data class TripEvaluationSnapshot(
    val identity: TripIdentity,
    val kind: TripEvaluationKind,
    val profitability: ProfitabilityResult,
    val price: Double,
    val pickupDistanceKm: Double,
    val tripDistanceKm: Double,
    val pickupMinutes: Int?,
    val tripMinutes: Int?,
    val updatedAtMs: Long
)

class TripEvaluationCache(
    private val ttlMs: Long = 5 * 60 * 1000L
) {
    private val realByStrongKey = linkedMapOf<String, TripEvaluationSnapshot>()
    private val realByWeakKey = linkedMapOf<String, TripEvaluationSnapshot>()
    private val previewByWeakKey = linkedMapOf<String, TripEvaluationSnapshot>()

    fun store(snapshot: TripEvaluationSnapshot) {
        prune(snapshot.updatedAtMs)
        when (snapshot.kind) {
            TripEvaluationKind.REAL -> {
                snapshot.identity.strongKey?.let { realByStrongKey[it] = snapshot }
                realByWeakKey[snapshot.identity.weakKey] = snapshot
            }
            TripEvaluationKind.PREVIEW -> {
                previewByWeakKey[snapshot.identity.weakKey] = snapshot
            }
        }
    }

    fun findReal(identity: TripIdentity, nowMs: Long): TripEvaluationSnapshot? {
        prune(nowMs)
        identity.strongKey?.let { strongKey ->
            realByStrongKey[strongKey]?.let { return it }
        }
        return realByWeakKey[identity.weakKey]
    }

    fun prune(nowMs: Long): Int {
        var removed = 0
        removed += realByStrongKey.removeExpired(nowMs)
        removed += realByWeakKey.removeExpired(nowMs)
        removed += previewByWeakKey.removeExpired(nowMs)
        return removed
    }

    private fun MutableMap<String, TripEvaluationSnapshot>.removeExpired(nowMs: Long): Int {
        val expiredKeys = entries
            .filter { nowMs - it.value.updatedAtMs > ttlMs }
            .map { it.key }
        expiredKeys.forEach(::remove)
        return expiredKeys.size
    }
}
