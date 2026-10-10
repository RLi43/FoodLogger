package ch.foodlogger.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.health.connect.client.PermissionController
import ch.foodlogger.app.ui.FoodLoggerApp
import ch.foodlogger.app.ui.FoodLoggerTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>
    private lateinit var takeLabelPhoto: ActivityResultLauncher<Uri>
    private lateinit var pickLabelPhoto: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var pickBarcodePhoto: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var pickReceipt: ActivityResultLauncher<Array<String>>
    private lateinit var takeReceiptPhoto: ActivityResultLauncher<Uri>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract(),
        ) { viewModel.refresh() }
        // The camera app takes the photo, so FoodLogger needs no CAMERA permission. The file is always the same,
        // so its URI can be rebuilt here even if the process was restarted while the camera was open.
        takeLabelPhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            if (saved) viewModel.scanLabel(viewModel.labelReader.photoUri())
        }
        pickLabelPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(viewModel::scanLabel)
        }
        pickBarcodePhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(viewModel::scanBarcodePhoto)
        }
        pickReceipt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.openReceipt(it, null) }
        }
        takeReceiptPhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            if (saved) viewModel.openReceipt(viewModel.receiptReader.photoUri(), "image/jpeg")
        }
        // Opened from another app's Share menu with a receipt; not again when the activity is recreated.
        if (savedInstanceState == null) openSharedReceipt(intent)

        enableEdgeToEdge()
        setContent {
            FoodLoggerTheme {
                FoodLoggerApp(
                    viewModel = viewModel,
                    onScan = ::scan,
                    onScanPhoto = { pickBarcodePhoto.launch(imageOnly()) },
                    onPhotographLabel = ::photographLabel,
                    onPickLabel = ::pickLabel,
                    onPickReceipt = { pickReceipt.launch(arrayOf("application/pdf", "image/*")) },
                    onPhotographReceipt = ::photographReceipt,
                    onGrantPermission = { requestPermissions.launch(HealthConnectSink.PERMISSIONS) },
                    onInstallHealthConnect = ::openHealthConnectInStore,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSharedReceipt(intent)
    }

    private fun openSharedReceipt(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        viewModel.openReceipt(uri, intent.type)
    }

    private fun photographReceipt() {
        try {
            takeReceiptPhoto.launch(viewModel.receiptReader.photoUri())
        } catch (e: ActivityNotFoundException) {
            // No camera app: a saved photo or PDF still works.
            pickReceipt.launch(arrayOf("application/pdf", "image/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions, the date or entries in Health Connect may have changed while we were away.
        viewModel.refresh()
        // A new build may have been published since the app was last in the foreground.
        viewModel.checkForUpdate()
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, *PRODUCT_2D_FORMATS)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(this, options)
            .startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(viewModel::onScanned) }
    }

    private fun photographLabel() {
        try {
            takeLabelPhoto.launch(viewModel.labelReader.photoUri())
        } catch (e: ActivityNotFoundException) {
            // No camera app: an existing photo still works.
            pickLabel()
        }
    }

    private fun pickLabel() = pickLabelPhoto.launch(imageOnly())

    private fun imageOnly() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    private fun openHealthConnectInStore() {
        val uri = Uri.parse(
            "market://details?id=${HealthConnectSink.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding",
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
    }
}
