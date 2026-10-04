/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.msm.movin.auralock.model.ParsedQrData
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun QrScannerView(
    onQrCodeDetected: (ParsedQrData) -> Unit,
    onManualInputClicked: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            try {
                launcher.launch(Manifest.permission.CAMERA)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraPreviewWithScanner(
                onQrCodeDetected = onQrCodeDetected,
                onManualInputClicked = onManualInputClicked,
                onClose = onClose
            )
        } else {
            // Permission Denied / Prompt View
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Permission Required",
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera Permission Needed",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "AuraLock requires camera permission to scan your PC's pairing QR code from the terminal or desktop app.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        try {
                            launcher.launch(Manifest.permission.CAMERA)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Throwable) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Grant Camera Permission", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (e: Throwable) {
                            android.widget.Toast.makeText(context, "Open Settings -> Apps -> AuraLock -> Permissions", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text("Open App Settings", color = Color(0xFF94A3B8), fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onManualInputClicked) {
                    Text("Enter IP & PIN Manually Instead", color = Color(0xFF38BDF8))
                }
                TextButton(onClick = onClose) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(
    onQrCodeDetected: (ParsedQrData) -> Unit,
    onManualInputClicked: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Safely initialize MLKit Barcode Scanner
    val barcodeScanner = remember {
        try {
            val barcodeOptions = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            BarcodeScanning.getClient(barcodeOptions)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    var cameraExecutor by remember {
        mutableStateOf<ExecutorService?>(null)
    }

    LaunchedEffect(Unit) {
        if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
    }

    var isTorchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProviderInstance by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var hasScanned by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    // Animated vertical laser sweep
    val infiniteTransition = rememberInfiniteTransition(label = "LaserSweep")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LaserOffset"
    )

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProviderInstance?.unbindAll()
            } catch (_: Throwable) {}
            try {
                cameraExecutor?.shutdown()
            } catch (_: Throwable) {}
            try {
                barcodeScanner?.close()
            } catch (_: Throwable) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraError == null) {
            key(reloadTrigger) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }

                        try {
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                try {
                                    val currentExecutor = cameraExecutor ?: Executors.newSingleThreadExecutor().also { cameraExecutor = it }
                                    if (currentExecutor.isShutdown) return@addListener

                                    val cameraProvider = cameraProviderFuture.get()
                                    cameraProviderInstance = cameraProvider

                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    imageAnalysis.setAnalyzer(currentExecutor) { imageProxy: ImageProxy ->
                                        if (hasScanned || currentExecutor.isShutdown) {
                                            try { imageProxy.close() } catch (_: Throwable) {}
                                            return@setAnalyzer
                                        }
                                        if (barcodeScanner != null) {
                                            processImageProxy(
                                                imageProxy = imageProxy,
                                                barcodeScanner = barcodeScanner,
                                                onResult = { rawText ->
                                                    if (!hasScanned) {
                                                        val parsed = ParsedQrData.parse(rawText)
                                                        if (parsed != null && parsed.pin.isNotBlank()) {
                                                            hasScanned = true
                                                            try {
                                                                ContextCompat.getMainExecutor(context).execute {
                                                                    onQrCodeDetected(parsed)
                                                                }
                                                            } catch (_: Throwable) {}
                                                        }
                                                    }
                                                }
                                            )
                                        } else {
                                            try { imageProxy.close() } catch (_: Throwable) {}
                                        }
                                    }

                                    cameraProvider.unbindAll()

                                    val cameraSelector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    } else if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                                        CameraSelector.DEFAULT_FRONT_CAMERA
                                    } else {
                                        CameraSelector.DEFAULT_BACK_CAMERA
                                    }

                                    camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        imageAnalysis
                                    )
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                    try {
                                        ContextCompat.getMainExecutor(context).execute {
                                            cameraError = "Could not initialize camera: ${e.localizedMessage ?: "Hardware busy or unavailable"}"
                                        }
                                    } catch (_: Throwable) {}
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        } catch (e: Throwable) {
                            e.printStackTrace()
                            cameraError = "Camera provider initialization failed: ${e.localizedMessage ?: "Unknown error"}"
                        }

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Viewfinder Overlay: 4-Rectangles Safe Cutout Mask & Cyberpunk Laser HUD
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val boxSize = (canvasWidth * 0.72f).coerceAtMost(320.dp.toPx())
                val left = (canvasWidth - boxSize) / 2f
                val top = (canvasHeight - boxSize) / 2f - 40.dp.toPx()
                val right = left + boxSize
                val bottom = top + boxSize
                val maskColor = Color.Black.copy(alpha = 0.65f)

                // Top mask
                drawRect(
                    color = maskColor,
                    topLeft = Offset(0f, 0f),
                    size = Size(canvasWidth, top)
                )

                // Bottom mask
                drawRect(
                    color = maskColor,
                    topLeft = Offset(0f, bottom),
                    size = Size(canvasWidth, canvasHeight - bottom)
                )

                // Left mask
                drawRect(
                    color = maskColor,
                    topLeft = Offset(0f, top),
                    size = Size(left, boxSize)
                )

                // Right mask
                drawRect(
                    color = maskColor,
                    topLeft = Offset(right, top),
                    size = Size(canvasWidth - right, boxSize)
                )

                // Viewfinder Outer Border with Neon Cyan Stroke
                drawRoundRect(
                    color = Color(0xFF06B6D4).copy(alpha = 0.8f),
                    topLeft = Offset(left, top),
                    size = Size(boxSize, boxSize),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Corner L-brackets
                val cornerLength = 28.dp.toPx()
                val strokeW = 4.dp.toPx()
                val cyan = Color(0xFF38BDF8)

                // Top-Left
                drawLine(cyan, Offset(left, top), Offset(left + cornerLength, top), strokeW)
                drawLine(cyan, Offset(left, top), Offset(left, top + cornerLength), strokeW)

                // Top-Right
                drawLine(cyan, Offset(right, top), Offset(right - cornerLength, top), strokeW)
                drawLine(cyan, Offset(right, top), Offset(right, top + cornerLength), strokeW)

                // Bottom-Left
                drawLine(cyan, Offset(left, bottom), Offset(left + cornerLength, bottom), strokeW)
                drawLine(cyan, Offset(left, bottom), Offset(left, bottom - cornerLength), strokeW)

                // Bottom-Right
                drawLine(cyan, Offset(right, bottom), Offset(right - cornerLength, bottom), strokeW)
                drawLine(cyan, Offset(right, bottom), Offset(right, bottom - cornerLength), strokeW)

                // Sweeping Laser Beam
                val laserY = top + (boxSize * laserProgress)
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF06B6D4).copy(alpha = 0.3f),
                            Color(0xFF38BDF8),
                            Color(0xFF06B6D4).copy(alpha = 0.3f),
                            Color.Transparent
                        ),
                        startX = left,
                        endX = right
                    ),
                    start = Offset(left + 8.dp.toPx(), laserY),
                    end = Offset(right - 8.dp.toPx(), laserY),
                    strokeWidth = 3.dp.toPx()
                )
            }
        } else {
            // Camera Error Fallback UI
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera Unavailable",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = cameraError ?: "Please enter your PC details manually.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            cameraError = null
                            reloadTrigger++
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Camera")
                    }
                    Button(
                        onClick = onManualInputClicked,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Enter Manually", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Top Controls: Close & Flash Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B).copy(alpha = 0.85f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color.White
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0F172A).copy(alpha = 0.85f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "AuraLock Scanner",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }

            IconButton(
                onClick = {
                    try {
                        isTorchOn = !isTorchOn
                        camera?.cameraControl?.enableTorch(isTorchOn)
                    } catch (_: Exception) {}
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B).copy(alpha = 0.85f))
            ) {
                Icon(
                    imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash",
                    tint = if (isTorchOn) Color(0xFFFACC15) else Color.White
                )
            }
        }

        // Bottom Instruction Card & Manual Switch
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF0F172A).copy(alpha = 0.92f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scan PC Terminal QR Code",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Run 'auralock pair' in your PC terminal or open AuraLock Desktop to align the QR inside the viewfinder.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onManualInputClicked,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF38BDF8)
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF0284C7))
                    ) {
                        Text("Enter IP & 6-Digit PIN Manually", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (String) -> Unit
) {
    try {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            onResult(rawValue)
                            break
                        }
                    }
                }
                .addOnFailureListener {
                    // Gracefully ignore frame parse error
                }
                .addOnCompleteListener {
                    try {
                        imageProxy.close()
                    } catch (_: Throwable) {}
                }
        } else {
            try {
                imageProxy.close()
            } catch (_: Throwable) {}
        }
    } catch (e: Throwable) {
        try {
            imageProxy.close()
        } catch (_: Throwable) {}
    }
}
