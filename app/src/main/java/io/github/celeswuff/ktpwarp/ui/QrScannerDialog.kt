package io.github.celeswuff.ktpwarp.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun QrScannerDialog(
    onDismissRequest: () -> Unit,
    onQrCodeScanned: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var minZoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(1f) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var permissionRequested by remember { mutableStateOf(false) }

    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analyzerExecutor.shutdown()
        }
    }

    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(FORMAT_QR_CODE)
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val permanentlyDenied = permissionRequested && !hasCameraPermission &&
        activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.CAMERA
    )

    val handledResult = remember { AtomicBoolean(false) }

    LaunchedEffect(hasCameraPermission, previewView) {
        val targetView = previewView ?: return@LaunchedEffect
        if (!hasCameraPermission) return@LaunchedEffect

        val cameraProvider = context.getCameraProvider()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(targetView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            if (handledResult.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val mainExecutor = ContextCompat.getMainExecutor(context)
            scanner.process(inputImage)
                .addOnSuccessListener(mainExecutor) { barcodes ->
                    val rawValue = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                    if (!rawValue.isNullOrBlank() && handledResult.compareAndSet(false, true)) {
                        onQrCodeScanned(rawValue)
                    }
                }
                .addOnFailureListener(mainExecutor) { exception ->
                    if (handledResult.compareAndSet(false, true)) {
                        onError(exception)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        runCatching {
            cameraProvider.unbindAll()
            val lifecycleOwner = activity as? androidx.lifecycle.LifecycleOwner ?: return@runCatching
            val boundCamera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            val zoomState = boundCamera.cameraInfo.zoomState.value
            minZoom = zoomState?.minZoomRatio ?: 1f
            maxZoom = zoomState?.maxZoomRatio ?: 1f
            zoomRatio = zoomState?.zoomRatio ?: 1f
            camera = boundCamera
        }.onFailure {
            onError(it)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val provider = ProcessCameraProvider.getInstance(context).get()
                provider.unbindAll()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(camera, minZoom, maxZoom, zoomRatio) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                val currentCamera = camera ?: return@detectTransformGestures
                                val nextZoom = (zoomRatio * zoomChange).coerceIn(minZoom, maxZoom)
                                zoomRatio = nextZoom
                                currentCamera.cameraControl.setZoomRatio(nextZoom)
                            }
                        }
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView = it }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(onClick = onDismissRequest) {
                                Text("关闭")
                            }
                        }
                        Text("将二维码放入取景框，可双指缩放")
                    }

                    if (maxZoom > minZoom) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("缩放：${"%.1f".format(zoomRatio)}x")
                            Slider(
                                value = zoomRatio,
                                onValueChange = { newZoom ->
                                    zoomRatio = newZoom
                                    camera?.cameraControl?.setZoomRatio(newZoom)
                                },
                                valueRange = minZoom..maxZoom,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("需要摄像头权限才能扫码。")
                    if (permanentlyDenied) {
                        Text("您已拒绝并不再提示，请在系统设置中开启摄像头权限。")
                        Button(onClick = { context.openAppSettings() }) {
                            Text("打开设置")
                        }
                    } else {
                        Button(onClick = {
                            permissionRequested = true
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }) {
                            Text("授予权限")
                        }
                    }
                    Button(onClick = onDismissRequest) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                continuation.resume(future.get())
            },
            ContextCompat.getMainExecutor(this)
        )
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
