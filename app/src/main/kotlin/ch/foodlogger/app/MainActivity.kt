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

        enableEdgeToEdge()
        setContent {
            FoodLoggerTheme {
                FoodLoggerApp(
                    viewModel = viewModel,
                    onScan = ::scan,
                    onPhotographLabel = ::photographLabel,
                    onPickLabel = ::pickLabel,
                    onGrantPermission = { requestPermissions.launch(HealthConnectSink.PERMISSIONS) },
                    onInstallHealthConnect = ::openHealthConnectInStore,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions, the date or entries in Health Connect may have changed while we were away.
        viewModel.refresh()
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A)
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

    private fun pickLabel() =
        pickLabelPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun openHealthConnectInStore() {
        val uri = Uri.parse(
            "market://details?id=${HealthConnectSink.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding",
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
    }
}
