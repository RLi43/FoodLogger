package ch.foodlogger.app

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import ch.foodlogger.core.LabelScan
import ch.foodlogger.core.NutritionLabel
import ch.foodlogger.core.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

/** Reads a photo of a nutrition label with ML Kit's on-device text recognition (Latin script). */
class LabelReader(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** A fresh content URI the camera app can write the next label photo to. */
    fun photoUri(): Uri {
        val dir = File(context.cacheDir, "labels").apply { mkdirs() }
        val file = File(dir, "label.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    suspend fun read(uri: Uri): LabelScan {
        // fromFilePath applies the photo's EXIF rotation, so the table rows come out horizontal.
        val text = recognizer.process(InputImage.fromFilePath(context, uri)).await()
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
        }
        return NutritionLabel.parseLines(lines)
    }
}
