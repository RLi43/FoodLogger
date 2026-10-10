package ch.foodlogger.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.content.FileProvider
import ch.foodlogger.core.OcrLine
import ch.foodlogger.core.Receipts
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a receipt into its printed lines, on the device: the text of a PDF (as shared by the Migros and
 * Coop apps), or text recognised in a photo of a paper receipt.
 */
class ReceiptReader(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** A fresh content URI the camera app can write the next receipt photo to. */
    fun photoUri(): Uri {
        val dir = File(context.cacheDir, "receipts").apply { mkdirs() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, "receipt.jpg"))
    }

    suspend fun read(uri: Uri, mimeType: String?): List<String> {
        if (!isPdf(uri, mimeType)) return recognize(InputImage.fromFilePath(context, uri))
        // A PDF without a text layer (a scan) is read like a photo.
        return pdfText(uri).takeIf { lines -> lines.any { it.isNotBlank() } } ?: pdfPages(uri)
    }

    /** Apps do not always label a shared file correctly, so the file's first bytes decide when the type is unclear. */
    private suspend fun isPdf(uri: Uri, mimeType: String?): Boolean {
        val type = mimeType ?: context.contentResolver.getType(uri)
        if (type == "application/pdf") return true
        if (type != null && type.startsWith("image/")) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> String(input.readNBytesCompat(5), Charsets.US_ASCII) == "%PDF-" }
            }.getOrNull() ?: false
        }
    }

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(bytes, read, count - read)
            if (n < 0) break
            read += n
        }
        return bytes.copyOf(read)
    }

    /** The PDF's own text, line by line in reading order; empty when it has none or cannot be read. */
    private suspend fun pdfText(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        try {
            PDFBoxResourceLoader.init(context)
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(document).lines()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        } catch (e: LinkageError) {
            // Encrypted PDFs need the crypto library, which is left out to keep the app small.
            emptyList()
        }
    }

    /** Renders each page at twice its size and recognises the text on it. */
    private suspend fun pdfPages(uri: Uri): List<String> {
        val bitmaps = withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()
            descriptor.use {
                PdfRenderer(it).use { renderer ->
                    (0 until renderer.pageCount.coerceAtMost(MAX_PAGES)).map { index ->
                        renderer.openPage(index).use { page ->
                            Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also { bitmap ->
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }
        }
        return bitmaps.flatMap { recognize(InputImage.fromBitmap(it, 0)) }
    }

    private suspend fun recognize(image: InputImage): List<String> {
        val text = recognizer.process(image).await()
        val pieces = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
        }
        return Receipts.rows(pieces)
    }

    private companion object {
        const val MAX_PAGES = 5
    }
}
