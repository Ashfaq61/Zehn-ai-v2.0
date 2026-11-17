package com.zehn.assistant.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.zehn.assistant.data.db.DocumentChunkEntity
import com.zehn.assistant.data.db.ProjectRagDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.sqrt

data class RagCitation(
    val documentName: String,
    val pageOrSection: Int,
    val snippet: String,
    val score: Float
)

@Singleton
class RagEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRagDao: ProjectRagDao
) {
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun ingestDocument(
        projectId: Long,
        uri: Uri,
        displayName: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
            val pages: List<Pair<Int, String>> = when {
                mime.contains("pdf") || displayName.endsWith(".pdf", ignoreCase = true) -> {
                    extractPdfViaRendererAndOcr(uri)
                }
                mime.startsWith("image/") -> {
                    val bmp = context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    } ?: error("Cannot decode image")
                    val text = ocrBitmap(bmp)
                    listOf(1 to text)
                }
                else -> {
                    val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).readText()
                    }.orEmpty()
                    chunkPlainText(raw).mapIndexed { idx, s -> (idx + 1) to s }
                }
            }

            val chunks = mutableListOf<DocumentChunkEntity>()
            pages.forEach { (pageNum, pageText) ->
                if (pageText.isBlank()) return@forEach
                chunkPlainText(pageText, maxChars = 480, overlap = 80).forEach { slice ->
                    val vec = computeEmbedding(slice)
                    chunks.add(
                        DocumentChunkEntity(
                            projectId = projectId,
                            documentName = displayName,
                            sourceUri = uri.toString(),
                            pageOrSection = pageNum,
                            chunkText = slice,
                            embeddingCsv = vec.joinToString(",")
                        )
                    )
                }
            }
            if (chunks.isEmpty()) error("No readable text found in $displayName")
            projectRagDao.insertChunks(chunks)
            projectRagDao.incrementDocCount(projectId)
            chunks.size
        }
    }

    suspend fun queryCitations(
        projectId: Long?,
        query: String,
        topK: Int = 4
    ): List<RagCitation> = withContext(Dispatchers.IO) {
        val candidates = if (projectId != null) {
            projectRagDao.getChunksForProject(projectId)
        } else {
            projectRagDao.getAllChunks()
        }
        if (candidates.isEmpty()) return@withContext emptyList()
        val queryVec = computeEmbedding(query)

        candidates.map { chunk ->
            val vec = chunk.embeddingCsv.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            val cos = cosineSimilarity(queryVec, vec)
            val lexicalBoost = if (chunk.chunkText.contains(query, ignoreCase = true)) 0.2f else 0f
            RagCitation(
                documentName = chunk.documentName,
                pageOrSection = chunk.pageOrSection,
                snippet = chunk.chunkText.take(260),
                score = cos + lexicalBoost
            )
        }
            .filter { it.score > 0.12f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private suspend fun extractPdfViaRendererAndOcr(uri: Uri): List<Pair<Int, String>> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        val results = mutableListOf<Pair<Int, String>>()
        pfd.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val count = renderer.pageCount.coerceAtMost(20)
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val bmp = Bitmap.createBitmap(
                            (page.width * 1.5f).toInt().coerceAtLeast(600),
                            (page.height * 1.5f).toInt().coerceAtLeast(800),
                            Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = ocrBitmap(bmp)
                        bmp.recycle()
                        if (text.isNotBlank()) results.add((i + 1) to text)
                    }
                }
            }
        }
        return results
    }

    suspend fun ocrBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result.text)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume("")
            }
    }

    private fun chunkPlainText(text: String, maxChars: Int = 520, overlap: Int = 80): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length <= maxChars) return listOf(cleaned)
        val out = mutableListOf<String>()
        var start = 0
        while (start < cleaned.length) {
            val end = (start + maxChars).coerceAtMost(cleaned.length)
            out.add(cleaned.substring(start, end))
            if (end == cleaned.length) break
            start = (end - overlap).coerceAtLeast(start + 1)
        }
        return out
    }

    /**
     * 64-dimensional normalized character-trigram + word-token hashing embedding for fast on-device SQLite retrieval.
     */
    fun computeEmbedding(text: String): FloatArray {
        val dim = 64
        val vec = FloatArray(dim)
        val norm = text.lowercase()
        val words = norm.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        for (w in words) {
            val idx = (w.hashCode() and 0x7fffffff) % dim
            vec[idx] += 2.0f
        }
        for (i in 0..(norm.length - 3).coerceAtLeast(-1)) {
            val tri = norm.substring(i, i + 3)
            val idx = (tri.hashCode() and 0x7fffffff) % dim
            vec[idx] += 0.5f
        }
        var mag = 0f
        for (v in vec) mag += v * v
        val root = sqrt(mag)
        if (root > 0f) {
            for (i in vec.indices) vec[i] /= root
        }
        return vec
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
