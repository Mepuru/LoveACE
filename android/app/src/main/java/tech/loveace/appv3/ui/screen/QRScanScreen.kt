package tech.loveace.appv3.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.Handler
import android.os.Looper
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRScanScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫码签到") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (hasCameraPermission) {
                CameraPreviewWithScanner(onScanned = onScanned)
                ScanOverlay()
                Box(
                    Modifier.fillMaxSize().padding(bottom = 100.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "请将二维码放入框内",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("需要摄像头权限", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("扫码签到需要使用摄像头", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("授予权限")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val executor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val reader = MultiFormatReader().apply {
                    val hints = mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(
                            BarcodeFormat.QR_CODE,
                            BarcodeFormat.DATA_MATRIX,
                            BarcodeFormat.CODE_128,
                            BarcodeFormat.EAN_13,
                            BarcodeFormat.EAN_8,
                        ),
                        DecodeHintType.TRY_HARDER to true,
                    )
                    setHints(hints)
                }

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    if (!hasScanned) {
                        val result = decodeImageProxy(reader, imageProxy)
                        if (result != null) {
                            hasScanned = true
                            Handler(Looper.getMainLooper()).post { onScanned(result) }
                        }
                    }
                    imageProxy.close()
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QRScan", "Camera bind failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun decodeImageProxy(reader: MultiFormatReader, imageProxy: ImageProxy): String? {
    val plane = imageProxy.planes[0]
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val width = imageProxy.width
    val height = imageProxy.height
    val rowStride = plane.rowStride

    val source = PlanarYUVLuminanceSource(
        bytes, rowStride, height, 0, 0, width, height, false
    )
    val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

    return try {
        reader.decodeWithState(binaryBitmap).text
    } catch (_: NotFoundException) {
        null
    } catch (e: Exception) {
        Log.e("QRScan", "Decode error", e)
        null
    } finally {
        reader.reset()
    }
}

@Composable
private fun ScanOverlay() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val scanSize = size.width * 0.7f
        val left = (size.width - scanSize) / 2
        val top = (size.height - scanSize) / 2

        drawRect(Color.Black.copy(alpha = 0.5f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(scanSize, scanSize),
            cornerRadius = CornerRadius(16f, 16f),
            blendMode = BlendMode.Clear,
        )
        drawRoundRect(
            color = primary,
            topLeft = Offset(left, top),
            size = Size(scanSize, scanSize),
            cornerRadius = CornerRadius(16f, 16f),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}
