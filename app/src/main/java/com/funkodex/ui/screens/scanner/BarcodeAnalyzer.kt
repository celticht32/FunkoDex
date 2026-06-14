package com.funkodex.ui.screens.scanner

import androidx.annotation.OptIn
import androidx.camera.core.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * ML Kit barcode analyzer — shared between ScannerScreen and PreScanScreen.
 * Detects UPC_A and UPC_E barcodes and calls [onDetected] with the raw value.
 *
 * To suppress single-frame misreads (a blurry frame can yield a partial or
 * wrong value), a candidate value must be seen on [requiredConsecutiveReads]
 * consecutive frames before it is committed via [onDetected]. Once committed,
 * the same value is not re-emitted until a different value (or a gap with no
 * barcode) resets the run.
 */
@OptIn(ExperimentalGetImage::class)
class BarcodeAnalyzer(
    private val onDetected: (String) -> Unit,
    private val requiredConsecutiveReads: Int = 3,
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()

    // Frame-confirmation state (single-threaded executor → no synchronization needed)
    private var candidate: String? = null
    private var candidateCount: Int = 0
    private var lastEmitted: String? = null

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes
                    .firstOrNull {
                        it.format == Barcode.FORMAT_UPC_A ||
                        it.format == Barcode.FORMAT_UPC_E ||
                        it.format == Barcode.FORMAT_EAN_13
                    }
                    ?.rawValue

                if (value == null) {
                    // No barcode this frame — break the run so a brief glimpse
                    // of a wrong value can't accumulate across non-contiguous frames.
                    candidate = null
                    candidateCount = 0
                    return@addOnSuccessListener
                }

                if (value == candidate) {
                    candidateCount++
                } else {
                    candidate = value
                    candidateCount = 1
                }

                if (candidateCount >= requiredConsecutiveReads && value != lastEmitted) {
                    lastEmitted = value
                    onDetected(value)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /**
     * Allow the same value to be detected again — call when the consumer has
     * finished handling a previous detection and wants to accept a fresh scan
     * (e.g. when returning to the live camera after a miss).
     */
    fun resetEmission() {
        candidate = null
        candidateCount = 0
        lastEmitted = null
    }
}
