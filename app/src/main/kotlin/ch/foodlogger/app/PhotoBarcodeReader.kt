package ch.foodlogger.app

import android.content.Context
import android.net.Uri
import ch.foodlogger.core.Barcodes
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/**
 * 2D codes that can carry the product number (GTIN), as on packs with a GS1 Data Matrix
 * or a QR code instead of, or next to, the usual barcode.
 */
val PRODUCT_2D_FORMATS = intArrayOf(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE)

/** Finds a product barcode in a saved photo, on the device. */
class PhotoBarcodeReader(private val context: Context) {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, *PRODUCT_2D_FORMATS)
                .build(),
        )
    }

    /** The product code of the first barcode or 2D code in the photo that has one, or null when there is none. */
    suspend fun read(uri: Uri): String? =
        scanner.process(InputImage.fromFilePath(context, uri)).await()
            .firstNotNullOfOrNull { barcode -> barcode.rawValue?.let(Barcodes::normalize) }
}
