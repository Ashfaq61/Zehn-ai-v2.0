package com.zehn.assistant.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zehn.assistant.ui.theme.ZehnColors
import java.io.File

@SuppressLint("MissingPermission")
@Composable
fun CameraVisionDialog(
    initialUseFrontCamera: Boolean = false,
    initialVideoMode: Boolean = false,
    onDismiss: () -> Unit,
    onCapturedPhoto: (Uri) -> Unit,
    onScannedOcrOrQr: (String) -> Unit,
    onSavedMediaNotice: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    var useFrontCamera by remember { mutableStateOf(initialUseFrontCamera) }
    var torchOn by remember { mutableStateOf(false) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var statusText by remember {
        mutableStateOf(
            if (initialVideoMode) "Video capture ready (visible preview)"
            else "Visible Camera • OCR, QR, Photo & Video"
        )
    }
    var detectedOcr by remember { mutableStateOf("") }
    var detectedQr by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = {
            runCatching { activeRecording?.stop() }
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ZehnColors.Background)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vision, Camera & QR", style = MaterialTheme.typography.titleLarge, color = ZehnColors.FrostWhite)
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = ZehnColors.Cyan)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasPermission && activeRecording == null) {
                        IconButton(
                            onClick = {
                                useFrontCamera = !useFrontCamera
                                if (useFrontCamera) torchOn = false
                            },
                            modifier = Modifier.testTag("camera_switch_lens_button")
                        ) {
                            Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch front or back camera", tint = ZehnColors.Cyan)
                        }
                        if (!useFrontCamera) {
                            IconButton(
                                onClick = { torchOn = !torchOn },
                                modifier = Modifier.testTag("camera_torch_button")
                            ) {
                                Icon(
                                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                    contentDescription = "Toggle camera flash",
                                    tint = if (torchOn) ZehnColors.Amber else ZehnColors.FrostWhite
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        runCatching { activeRecording?.stop() }
                        onDismiss()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = ZehnColors.FrostWhite)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (!hasPermission) {
                ClayGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Camera permission is required for live OCR, QR scanning, photo saving, and video capture.", color = ZehnColors.FrostWhite)
                    Spacer(Modifier.height(12.dp))
                    ClayActionButton(text = "Grant Camera", onClick = { permLauncher.launch(Manifest.permission.CAMERA) })
                }
            } else {
                val cameraController = remember {
                    LifecycleCameraController(context).apply {
                        setEnabledUseCases(
                            CameraController.IMAGE_CAPTURE or
                                CameraController.IMAGE_ANALYSIS or
                                CameraController.VIDEO_CAPTURE
                        )
                    }
                }

                LaunchedEffect(useFrontCamera) {
                    cameraController.cameraSelector = if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                }

                LaunchedEffect(torchOn, useFrontCamera) {
                    runCatching { cameraController.enableTorch(!useFrontCamera && torchOn) }
                }

                val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
                val barcodeScanner = remember {
                    BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
                    )
                }

                DisposableEffect(lifecycleOwner) {
                    cameraController.bindToLifecycle(lifecycleOwner)
                    cameraController.setImageAnalysisAnalyzer(
                        ContextCompat.getMainExecutor(context),
                        MlKitAnalyzer(
                            listOf(textRecognizer, barcodeScanner),
                            CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                            ContextCompat.getMainExecutor(context)
                        ) { result ->
                            val ocr = result?.getValue(textRecognizer)?.text?.trim().orEmpty()
                            if (ocr.isNotEmpty()) detectedOcr = ocr.take(280)
                            val qr = result?.getValue(barcodeScanner)?.firstOrNull()?.rawValue.orEmpty()
                            if (qr.isNotEmpty()) detectedQr = qr
                        }
                    )
                    onDispose {
                        runCatching { activeRecording?.stop() }
                        cameraController.clearImageAnalysisAnalyzer()
                        textRecognizer.close()
                        barcodeScanner.close()
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply { controller = cameraController }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        color = ZehnColors.Background.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = if (activeRecording != null) "REC • VISIBLE CAMERA"
                            else if (useFrontCamera) "FRONT LENS ACTIVE"
                            else "BACK LENS ACTIVE",
                            color = if (activeRecording != null) ZehnColors.RoseStop else ZehnColors.Cyan,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    if (detectedQr.isNotBlank() || detectedOcr.isNotBlank()) {
                        ClayGlassCard(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            if (detectedQr.isNotBlank()) {
                                Text("QR / Barcode: $detectedQr", color = ZehnColors.Cyan, style = MaterialTheme.typography.labelMedium)
                            }
                            if (detectedOcr.isNotBlank()) {
                                Text("OCR Preview: ${detectedOcr.take(140)}", color = ZehnColors.FrostWhite, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClayActionButton(
                        text = "Analyze",
                        icon = Icons.Filled.CameraAlt,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val file = File(context.cacheDir, "zehn_cam_${System.currentTimeMillis()}.jpg")
                            val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                            cameraController.takePicture(
                                opts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        onCapturedPhoto(Uri.fromFile(file))
                                        onDismiss()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        statusText = "Photo capture error: ${exception.message}"
                                    }
                                }
                            )
                        }
                    )
                    ClayActionButton(
                        text = "Save Photo",
                        icon = Icons.Filled.PhotoLibrary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val name = "MahiZehn_${System.currentTimeMillis()}.jpg"
                            val cv = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MahiZehnAI")
                            }
                            val opts = ImageCapture.OutputFileOptions.Builder(
                                context.contentResolver,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                cv
                            ).build()
                            cameraController.takePicture(
                                opts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        val msg = "Saved photo to Pictures/MahiZehnAI/$name"
                                        statusText = msg
                                        onSavedMediaNotice(msg)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        statusText = "Save photo failed: ${exception.message}"
                                    }
                                }
                            )
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ClayActionButton(
                        text = if (activeRecording == null) "Record Video" else "Stop Video",
                        icon = if (activeRecording == null) Icons.Filled.Videocam else Icons.Filled.Stop,
                        isDanger = activeRecording != null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val current = activeRecording
                            if (current != null) {
                                current.stop()
                                activeRecording = null
                                statusText = "Finishing video save..."
                            } else {
                                val name = "MahiZehn_${System.currentTimeMillis()}.mp4"
                                val cv = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MahiZehnAI")
                                }
                                val mediaOpts = MediaStoreOutputOptions.Builder(
                                    context.contentResolver,
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                ).setContentValues(cv).build()

                                val audioGranted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                val audioConfig = AudioConfig.create(audioGranted)

                                activeRecording = cameraController.startRecording(
                                    mediaOpts,
                                    audioConfig,
                                    ContextCompat.getMainExecutor(context)
                                ) { event ->
                                    when (event) {
                                        is VideoRecordEvent.Start -> statusText = "Recording video to Movies/MahiZehnAI..."
                                        is VideoRecordEvent.Finalize -> {
                                            activeRecording = null
                                            val msg = if (event.hasError()) {
                                                "Video error (${event.error})"
                                            } else {
                                                "Saved video to Movies/MahiZehnAI/$name"
                                            }
                                            statusText = msg
                                            onSavedMediaNotice(msg)
                                        }
                                    }
                                }
                            }
                        }
                    )
                    ClayActionButton(
                        text = "Use OCR / QR",
                        icon = Icons.Filled.QrCodeScanner,
                        modifier = Modifier.weight(1f),
                        enabled = detectedQr.isNotBlank() || detectedOcr.isNotBlank(),
                        onClick = {
                            val combined = buildString {
                                if (detectedQr.isNotBlank()) appendLine("QR/Barcode: $detectedQr")
                                if (detectedOcr.isNotBlank()) append("OCR Text:\n$detectedOcr")
                            }
                            onScannedOcrOrQr(combined)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
