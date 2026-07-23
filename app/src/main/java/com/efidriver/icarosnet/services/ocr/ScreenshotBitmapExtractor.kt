package com.efidriver.icarosnet.services.ocr

import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap

object ScreenshotBitmapExtractor {
    fun extract(screenshot: ScreenshotResult): Bitmap? {
        val hardwareBuffer = screenshot.hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            hardwareBuffer.close()
        }
    }
}
