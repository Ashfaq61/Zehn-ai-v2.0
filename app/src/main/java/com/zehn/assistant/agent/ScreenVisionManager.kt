package com.zehn.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zehn.assistant.ai.AiEngineRouter
import com.zehn.assistant.ai.LlmMessage
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class OcrTextElement(
    val text: String,
    val bounds: Rect
)

data class VisualScreenAnalysis(
    val method: String, // "MEDIA_PROJECTION" or "ACCESSIBILITY_SCREENSHOT" or "UNAVAILABLE"
    val ocrText: String = "",
    val ocrElements: List<OcrTextElement> = emptyList(),
    val visionModelSummary: String = "",
    val error: String? = null
)

@Singleton
class ScreenVisionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safetyPolicy: SafetyPolicyManager,
    private val aiRouter: AiEngineRouter
) {
    /**
     * Observation Method A: Structural Accessibility tree reading (fast, privacy-guarded).
     */
    fun observeViaAccessibility(): ScreenObservation {
        val svc = ZehnAccessibilityService.instance.value
            ?: return ScreenObservation(error = "Accessibility Service is not connected.")
        return svc.observeScreenStructured()
    }

    /**
     * Observation Method B: Pixel capture (MediaProjection first when consented, else
     * AccessibilityService.takeScreenshot on API 30+) + ML Kit OCR + optional Vision model interpretation.
     */
    suspend fun captureAndAnalyzeScreen(
        visionPrompt: String? = null,
        useVisionModel: Boolean = false
    ): VisualScreenAnalysis = withContext(Dispatchers.IO) {
        val currentPkg = ZehnAccessibilityService.foregroundPackage.value
        if (!safetyPolicy.canReadCurrentScreen(currentPkg)) {
            return@withContext VisualScreenAnalysis(
                method = "BLOCKED",
                error = "Screen capture blocked by Safety/Gaming Guard for ${currentPkg ?: "current app"}."
            )
        }

        var method = "UNAVAILABLE"
        var bitmap: Bitmap? = null

        if (ScreenCaptureService.projectionActive.value) {
            bitmap = ScreenCaptureService.captureSingleFrame(context)
            if (bitmap != null) {
                method = "MEDIA_PROJECTION"
            }
        }

        if (bitmap == null) {
            val svc = ZehnAccessibilityService.instance.value
            if (svc != null) {
                bitmap = svc.captureScreenshotBitmap()
                if (bitmap != null) {
                    method = "ACCESSIBILITY_SCREENSHOT"
                }
            }
        }

        if (bitmap == null) {
            val status = ScreenCaptureService.getPermissionStatus(context)
            return@withContext VisualScreenAnalysis(
                method = "UNAVAILABLE",
                error = "No screenshot frame captured ($status). Grant MediaProjection consent or enable Accessibility on Android 11+."
            )
        }

        return@withContext try {
            val ocrElements = runMlKitOcr(bitmap)
            val combinedText = ocrElements.joinToString("\n") { it.text }
            val visionSummary = if (useVisionModel && !visionPrompt.isNullOrBlank()) {
                runVisionModelOnBitmap(bitmap, visionPrompt, combinedText)
            } else ""

            VisualScreenAnalysis(
                method = method,
                ocrText = combinedText,
                ocrElements = ocrElements,
                visionModelSummary = visionSummary
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VisualScreenAnalysis(
                method = method,
                error = "Screen vision processing error: ${e.message ?: "unknown"}"
            )
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun findOcrElementMatching(query: String): OcrTextElement? {
        val analysis = captureAndAnalyzeScreen(useVisionModel = false)
        if (analysis.ocrElements.isEmpty()) return null
        val clean = query.trim()
        return analysis.ocrElements.firstOrNull { it.text.equals(clean, ignoreCase = true) }
            ?: analysis.ocrElements.firstOrNull { it.text.contains(clean, ignoreCase = true) }
    }

    private suspend fun runMlKitOcr(bitmap: Bitmap): List<OcrTextElement> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        return withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine { cont ->
                recognizer.process(inputImage)
                    .addOnSuccessListener { result ->
                        val items = mutableListOf<OcrTextElement>()
                        result.textBlocks.forEach { block ->
                            block.lines.forEach { line ->
                                val txt = line.text.trim()
                                val box = line.boundingBox
                                if (txt.isNotBlank() && box != null) {
                                    items.add(OcrTextElement(txt, box))
                                }
                            }
                        }
                        if (cont.isActive) cont.resume(items)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(emptyList())
                    }
            }
        } ?: emptyList()
    }

    private suspend fun runVisionModelOnBitmap(
        bitmap: Bitmap,
        prompt: String,
        ocrHint: String
    ): String {
        val cacheDir = File(context.cacheDir, "vision_frames").apply { mkdirs() }
        val frameFile = File(cacheDir, "screen_frame.jpg")
        return try {
            FileOutputStream(frameFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
            }
            val uri = Uri.fromFile(frameFile)
            val fullPrompt = buildString {
                append(prompt)
                if (ocrHint.isNotBlank()) {
                    append("\n\nOn-screen OCR text context:\n")
                    append(ocrHint.take(1200))
                }
            }
            val events = aiRouter.routeAndStream(
                history = listOf(LlmMessage("user", fullPrompt)),
                userInput = fullPrompt,
                imageUri = uri
            ).toList()
            val text = events.joinToString("") { it.delta }.trim()
            if (text.isNotBlank()) text else events.lastOrNull { it.error != null }?.error.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Vision model analysis failed: ${e.message ?: "unavailable"}"
        } finally {
            runCatching { frameFile.delete() }
        }
    }
}
