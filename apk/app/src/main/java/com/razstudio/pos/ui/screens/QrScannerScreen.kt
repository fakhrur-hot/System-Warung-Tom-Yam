package com.razstudio.pos.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * In-app QR scanner for ordering-device onboarding. Renders a CameraX preview at the correct
 * display aspect ratio (FILL_CENTER, driven by the display's own dimensions so it never
 * stretches/squashes), analyzes frames with ZXing, and calls [onQrDecoded] once with the first
 * decoded QR text. [onCancel] backs out to manual entry.
 *
 * Camera permission must already be granted by the caller (see the permission gate below);
 * if it isn't, this shows a rationale + a button that triggers the caller's [onRequestPermission].
 */
@Composable
fun QrScannerScreen(
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onQrDecoded: (String) -> Unit,
    onCancel: () -> Unit,
    promptText: String = "Point the camera at the invite QR",
    cancelText: String = "Enter code instead",
    grantText: String = "Grant camera access",
    cameraErrorText: String = "Camera isn't producing a picture. This is usually the device's " +
        "camera driver, not the app — try again, or use the fallback below.",
    retryText: String = "Try again"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Single-fire guard so one QR fires onQrDecoded exactly once.
    var decoded by remember { mutableStateOf(false) }

    // CameraX can report a use case as successfully bound and "ACTIVE" while the vendor camera
    // HAL never actually delivers a single frame — a MIPI-level sensor fault does exactly this on
    // some devices (silent black preview, bindToLifecycle throws nothing). A frame-received flag
    // plus a timeout is the only way to tell "still starting up" apart from "never going to work."
    // retryTrigger re-keys the AndroidView below to force a full rebind from scratch on retry,
    // since the factory lambda only runs once per composition otherwise.
    val frameReceived = remember { AtomicBoolean(false) }
    var cameraError by remember { mutableStateOf(false) }
    var retryTrigger by remember { mutableStateOf(0) }

    if (!hasCameraPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = promptText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.padding(top = 16.dp)
            ) { Text(grantText) }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(cancelText) }
        }
        return
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // Gives the camera a real chance to warm up (cold start on some devices takes a couple of
    // seconds) before deciding it's dead. Re-runs on every retry via the retryTrigger key.
    LaunchedEffect(retryTrigger) {
        frameReceived.set(false)
        cameraError = false
        delay(6_000)
        if (!frameReceived.get() && !decoded) {
            cameraError = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(retryTrigger) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        // FILL_CENTER keeps the preview at the sensor's aspect ratio, cropping to
                        // fill the view instead of stretching — correct on any screen ratio.
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(
                                    analysisExecutor,
                                    QrAnalyzer(
                                        onFrame = { frameReceived.set(true) },
                                        onDecoded = { text ->
                                            if (!decoded) {
                                                decoded = true
                                                onQrDecoded(text)
                                            }
                                        },
                                    ),
                                )
                            }

                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        } catch (_: Exception) {
                            // A bind failure is a real, visible error — not something to leave the
                            // user staring at a dead black screen guessing about.
                            cameraError = true
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )
        }

        if (cameraError) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = cameraErrorText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = { retryTrigger++ },
                    modifier = Modifier.padding(top = 16.dp)
                ) { Text(retryText) }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(cancelText) }
            }
        } else {
            // Framing guide + prompt + cancel over the preview.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = promptText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text(cancelText) }
            }
        }
    }
}

/** ZXing frame analyzer: decodes QR from CameraX YUV frames. [onFrame] fires for every frame that
 *  actually reaches here, decoded or not — the only signal that the camera is really streaming. */
private class QrAnalyzer(
    private val onFrame: () -> Unit,
    private val onDecoded: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true))
    }

    override fun analyze(image: ImageProxy) {
        onFrame()
        try {
            val plane = image.planes.firstOrNull() ?: run { image.close(); return }
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                image.height,
                0, 0,
                image.width,
                image.height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = try {
                reader.decodeWithState(bitmap)
            } catch (_: Exception) {
                null
            }
            if (result != null && result.text.isNotBlank()) {
                onDecoded(result.text)
            }
        } catch (_: Exception) {
            // Ignore this frame.
        } finally {
            image.close()
        }
    }
}
