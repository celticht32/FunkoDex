package com.funkodex.ui.screens.prescan

import android.Manifest
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.funkodex.data.model.FunkoItem
import com.funkodex.ui.screens.scanner.BarcodeAnalyzer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import android.util.Size

/**
 * Pre-Purchase Scanner — a streamlined "do I already own this?" check.
 *
 * Distinct from the main Scanner screen:
 *  - No lookup to Funko.com — only checks your local Couchbase collection
 *  - Big, obvious YES/NO result overlay so you can see it quickly in a store
 *  - Auto-resets after 3 seconds so you can scan the next item immediately
 *  - No "add to collection" flow — this screen is read-only
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PreScanScreen(
    viewModel: PreScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(cameraPermission.status) {
        if (cameraPermission.status.isGranted) viewModel.startScanning()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Camera always running in the background
        if (cameraPermission.status.isGranted) {
            PreScanCameraPreview(onBarcodeDetected = viewModel::onBarcodeDetected)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CameraAlt, null, Modifier.size(64.dp), tint = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text("Camera permission needed", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                        Text("Grant permission")
                    }
                }
            }
        }

        // Scan frame
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(width = 260.dp, height = 110.dp)
                    .background(Color.Transparent)
            ) {
                // Corner brackets
                CornerBracket(Modifier.align(Alignment.TopStart), topLeft = true)
                CornerBracket(Modifier.align(Alignment.TopEnd), topRight = true)
                CornerBracket(Modifier.align(Alignment.BottomStart), bottomLeft = true)
                CornerBracket(Modifier.align(Alignment.BottomEnd), bottomRight = true)
            }
        }

        // Top label
        Box(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    "Pre-Purchase Check",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color    = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                )
            }
        }

        // Result overlay
        AnimatedVisibility(
            visible = state !is PreScanState.Scanning && state !is PreScanState.Idle,
            enter   = fadeIn() + scaleIn(),
            exit    = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            when (val s = state) {
                is PreScanState.AlreadyOwned -> OwnedResult(item = s.item)
                is PreScanState.NotOwned     -> NotOwnedResult(item = s.item)
                is PreScanState.LookingUp    -> LookingUpIndicator()
                is PreScanState.NotFound     -> NotFoundResult()
                else -> {}
            }
        }

        // Bottom hint
        if (state is PreScanState.Scanning) {
            Box(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Scan the barcode on a Funko box",
                    color    = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun OwnedResult(item: FunkoItem) {
    Column(
        modifier = Modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1B5E20).copy(alpha = 0.95f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Big checkmark
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF4CAF50)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(44.dp), tint = Color.White)
        }
        Text("YOU HAVE THIS ONE",
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)

        if (item.imageUrl.isNotEmpty()) {
            AsyncImage(
                model = item.imageUrl, contentDescription = null,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Text(item.name, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, fontSize = 15.sp)
        if (item.franchise.isNotEmpty())
            Text(item.franchise, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        if (item.pricePaid > 0)
            Text("You paid: $${"%.2f".format(item.pricePaid)}",
                color = Color(0xFFA5D6A7), fontSize = 13.sp)

        Text("auto-resetting…", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

@Composable
private fun NotOwnedResult(item: FunkoItem) {
    Column(
        modifier = Modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0D47A1).copy(alpha = 0.95f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF2196F3)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.AddShoppingCart, null, Modifier.size(40.dp), tint = Color.White)
        }
        Text("NOT IN YOUR COLLECTION",
            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            textAlign = TextAlign.Center)

        if (item.imageUrl.isNotEmpty()) {
            AsyncImage(
                model = item.imageUrl, contentDescription = null,
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Text(item.name, color = Color.White, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, fontSize = 15.sp)
        if (item.franchise.isNotEmpty())
            Text(item.franchise, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        if (item.retailPrice > 0)
            Text("Retail: $${"%.2f".format(item.retailPrice)}",
                color = Color(0xFF90CAF9), fontSize = 13.sp)
        val isWanted = item.isOwned.not() && item.id.isNotEmpty()
        if (isWanted)
            Surface(color = Color(0xFFF57C00), shape = RoundedCornerShape(6.dp)) {
                Text("★ On your want list",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }

        Text("auto-resetting…", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
    }
}

@Composable
private fun LookingUpIndicator() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.8f)
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            Text("Checking your collection…", color = Color.White)
        }
    }
}

@Composable
private fun NotFoundResult() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF4A4A4A).copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.HelpOutline, null, Modifier.size(40.dp), tint = Color.White)
            Text("Unknown Funko", color = Color.White, fontWeight = FontWeight.Medium)
            Text("Not in local database", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun CornerBracket(
    modifier: Modifier,
    topLeft: Boolean = false, topRight: Boolean = false,
    bottomLeft: Boolean = false, bottomRight: Boolean = false,
) {
    val size = 20.dp
    val thickness = 3.dp
    Box(modifier = modifier.size(size)) {
        if (topLeft || bottomLeft) {
            Box(modifier = Modifier.width(thickness).fillMaxHeight().background(Color.White)
                .align(Alignment.CenterStart))
        }
        if (topRight || bottomRight) {
            Box(modifier = Modifier.width(thickness).fillMaxHeight().background(Color.White)
                .align(Alignment.CenterEnd))
        }
        if (topLeft || topRight) {
            Box(modifier = Modifier.height(thickness).fillMaxWidth().background(Color.White)
                .align(Alignment.TopCenter))
        }
        if (bottomLeft || bottomRight) {
            Box(modifier = Modifier.height(thickness).fillMaxWidth().background(Color.White)
                .align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun PreScanCameraPreview(onBarcodeDetected: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(
                            java.util.concurrent.Executors.newSingleThreadExecutor(),
                            BarcodeAnalyzer(onBarcodeDetected)
                        )
                    }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}
