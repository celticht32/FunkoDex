package com.funkodex.ui.screens.scanner

import androidx.annotation.OptIn
import androidx.camera.core.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * ML Kit barcode analyzer — shared between ScannerScreen and PreScanScreen.
 * Detects UPC_A and UPC_E barcodes and calls [onDetected] with the raw value.
 */
@OptIn(ExperimentalGetImage::class)
class BarcodeAnalyzer(
    private val onDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient()

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes
                    .firstOrNull {
                        it.format == Barcode.FORMAT_UPC_A ||
                        it.format == Barcode.FORMAT_UPC_E ||
                        it.format == Barcode.FORMAT_EAN_13
                    }
                    ?.rawValue
                    ?.let(onDetected)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}
