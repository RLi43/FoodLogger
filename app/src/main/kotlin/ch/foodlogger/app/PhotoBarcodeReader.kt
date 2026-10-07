package ch.foodlogger.app

import android.content.Context
import android.net.Uri
import ch.foodlogger.core.Barcodes
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/** Finds a product barcode in a saved photo, on the device. */
class PhotoBarcodeReader(private val context: Context) {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
    }

    /** The first barcode in the photo that is a valid product code, or null when there is none. */
    suspend fun read(uri: Uri): String? =
        scanner.process(InputImage.fromFilePath(context, uri)).await()
            .firstNotNullOfOrNull { barcode -> barcode.rawValue?.takeIf { Barcodes.normalize(it) != null } }
}
