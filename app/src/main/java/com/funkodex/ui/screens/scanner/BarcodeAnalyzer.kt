package com.funkodex.ui.screens.scanner

import androidx.annotation.OptIn
import androidx.camera.core.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

/**
 * ML Kit barcode analyzer — shared between ScannerScreen and PreScanScreen.
 * Detects UPC_A and UPC_E barcodes and calls [onDetected] with the raw value.
 *
 * To suppress single-frame misreads (a blurry frame can yield a partial or
 * wrong value), a candidate value must be seen on [requiredConsecutiveReads]
 * consecutive frames before it is committed via [onDetected]. Once committed,
 * the same value is not re-emitted until a different value (or a gap with no
 * barcode) resets the run.
 *
 * Holds a native ML Kit [BarcodeScanning] client, so it is [Closeable]: the
 * owner MUST call [close] when the analyzer is discarded (e.g. onDispose of the
 * camera preview). A new BarcodeAnalyzer allocates a new native detector; without
 * close() they accumulate for the app's lifetime — the leak that made long
 * scanning sessions slower and never freed memory until the app was killed.
 */
@OptIn(ExperimentalGetImage::class)
class BarcodeAnalyzer(
    private val onDetected: (String) -> Unit,
    private val requiredConsecutiveReads: Int = 3,
) : ImageAnalysis.Analyzer, Closeable {
    private val scanner = BarcodeScanning.getClient()

    // Set true in close(); analyze() becomes a no-op that just drains frames so a
    // late in-flight ImageProxy after teardown can't touch a closed scanner.
    @Volatile private var closed = false

    // Frame-confirmation state (single-threaded executor → no synchronization needed)
    private var candidate: String? = null
    private var candidateCount: Int = 0
    private var lastEmitted: String? = null

    override fun analyze(imageProxy: ImageProxy) {
        if (closed) { imageProxy.close(); return }
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

    /**
     * Release the native ML Kit detector. Idempotent. After this, [analyze] is a
     * no-op that only drains frames. Call from the camera preview's onDispose.
     */
    override fun close() {
        closed = true
        scanner.close()
    }
}
