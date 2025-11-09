package com.navis.pepscout

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.navis.pepscout.detector.HazardDetector
import com.navis.pepscout.plugins.qr.QrScanActivity
import com.navis.pepscout.ui.PepScoutApp
import com.navis.pepscout.ui.theme.PepScoutTheme
import com.navis.pepscout.viewmodel.PepScoutViewModel

/** Entry point for the native Pep Scout experience. */
class MainActivity : ComponentActivity() {

    private val viewModel: PepScoutViewModel by viewModels()

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onLocationPermissionChanged(granted)
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onCameraPermissionChanged(granted)
        }

    private val recordPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onMicrophonePermissionChanged(granted)
        }

    private val qrScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val payload = result.data?.getStringExtra("qr_payload")
                payload?.let(viewModel::onIndoorQrScanned)
            }
        }

    private lateinit var hazardDetector: HazardDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hazardDetector = HazardDetector(this) { event ->
            viewModel.onHazardDetected(event)
        }

        setContent {
            PepScoutTheme {
                PepScoutApp(
                    viewModel = viewModel,
                    onRequestLocationPermission = {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onRequestMicrophonePermission = {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onStartHazardDetection = { hazardDetector.start(this) },
                    onStopHazardDetection = { hazardDetector.stop() },
                    onScanQr = {
                        qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        hazardDetector.stop()
        viewModel.cleanUp()
        super.onDestroy()
    }
}
