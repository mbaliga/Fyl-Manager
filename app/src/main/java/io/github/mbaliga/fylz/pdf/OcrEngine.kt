package io.github.mbaliga.fylz.pdf

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OcrScript {
    LATIN,
    DEVANAGARI,
}

/**
 * Abstracts which on-device text-recognition backend Fylz uses behind a seam for a decision
 * Madhav hasn't made yet (P0.13, decision D1): no behavior change today, since [MlKitOcrEngine]
 * is the only implementation, wrapping ML Kit's `TextRecognizer` exactly as
 * [PdfPageTools][io.github.mbaliga.fylz.pdf.PdfPageTools] (P1.13 absorbed the same OCR wiring
 * `PdfToolService` used to own) and
 * [SearchablePdfService][io.github.mbaliga.fylz.pdf.SearchablePdfService] both already did
 * directly before this. Returns ML Kit's own [Text] result -- both call sites already depend on
 * its bounding-box structure to lay text back onto a page, so redesigning that is a separate,
 * larger decision than "which engine creates the recognizer," and out of this task's scope.
 */
interface OcrEngine : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap): Text
}

/** Creates one [OcrEngine] per top-level operation (one `export`/`exportPages` call, not one per
 * page) -- matching how both existing call sites already scoped and closed their recognizer. */
fun interface OcrEngineFactory {
    fun create(script: OcrScript): OcrEngine
}

class MlKitOcrEngine(script: OcrScript) : OcrEngine {
    private val recognizer: TextRecognizer = when (script) {
        OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrScript.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): Text =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

    override fun close() {
        recognizer.close()
    }

    companion object : OcrEngineFactory {
        override fun create(script: OcrScript): OcrEngine = MlKitOcrEngine(script)
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
