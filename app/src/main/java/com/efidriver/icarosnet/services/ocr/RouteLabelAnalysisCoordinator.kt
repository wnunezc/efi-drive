package com.efidriver.icarosnet.services.ocr

import android.graphics.Bitmap
import com.efidriver.icarosnet.vision.RouteLabelDetector
import com.efidriver.icarosnet.vision.RouteLabelOcr

class RouteLabelAnalysisCoordinator {
    data class DetectionSummary(
        val text: String,
        val detection: RouteLabelDetector.DetectionResult
    )

    fun detect(bitmap: Bitmap): DetectionSummary {
        val result = RouteLabelDetector.detect(bitmap)
        return DetectionSummary(
            text = "visible=${result.routeLabelsVisible} " +
                "mode=${result.mode} " +
                "bluePixels=${result.bluePixelCount} greenPixels=${result.greenPixelCount} " +
                "blueCandidates=${result.pickupCandidates.size} greenCandidates=${result.destinationCandidates.size} " +
                "blueBox=${result.pickupLabel?.bounds ?: "none"} " +
                "greenBox=${result.destinationLabel?.bounds ?: "none"}",
            detection = result
        )
    }

    fun recognize(
        bitmap: Bitmap,
        detection: RouteLabelDetector.DetectionResult,
        onSuccess: (RouteLabelOcr.OcrResult) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        RouteLabelOcr.recognize(
            source = bitmap,
            detection = detection,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun summarizeOcr(result: RouteLabelOcr.OcrResult): String {
        return "complete=${result.complete} " +
            "pickupCandidate=${result.pickupCandidateIndex} destinationCandidate=${result.destinationCandidateIndex} " +
            "pickupRaw=${result.pickup.rawText} pickupMin=${result.pickup.minutes} pickupKm=${result.pickup.distanceKm} " +
            "destinationRaw=${result.destination.rawText} destinationMin=${result.destination.minutes} destinationKm=${result.destination.distanceKm}"
    }
}
