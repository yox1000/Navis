package com.navis.pepscout.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Simple on-device hazard detector that uses a lightweight TensorFlow Lite model.
 * Emits hazard events whenever something occupies the center of the frame.
 */
class HazardDetector(
    private val context: Context,
    private val onHazard: (HazardEvent) -> Unit
) {

    companion object {
        private const val TAG = "HazardDetector"
        private const val INPUT_SIZE = 320
        private const val MAX_DETECTIONS = 10
        private const val SCORE_THRESHOLD = 0.5f
        private const val CENTER_CROP_RATIO = 0.6f
        private const val MIN_BOX_AREA_RATIO = 0.01f
        private const val ANALYSIS_INTERVAL = 500L
    }

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val imageProcessor by lazy {
        ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))
            .build()
    }

    private var cameraExecutor: ExecutorService? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastAnalysisTime = 0L
    private var isRunning = false

    init {
        loadModel()
    }

    fun start(owner: LifecycleOwner) {
        if (isRunning) return
        if (!hasCameraPermission()) {
            Log.w(TAG, "Camera permission missing; cannot start hazard detection")
            return
        }

        if (interpreter == null) {
            Log.w(TAG, "Model not loaded; hazard detection disabled")
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            cameraExecutor = Executors.newSingleThreadExecutor()
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(INPUT_SIZE, INPUT_SIZE))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalyzer?.setAnalyzer(cameraExecutor!!, Analyzer())

            cameraProvider?.unbindAll()
            try {
                cameraProvider?.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    imageAnalyzer
                )
                isRunning = true
                Log.d(TAG, "Hazard detection started")
            } catch (err: java.lang.Exception) {
                Log.e(TAG, "Failed to bind camera lifecycle", err)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        if (!isRunning) return
        cameraProvider?.unbindAll()
        imageAnalyzer?.clearAnalyzer()
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
        isRunning = false
        Log.d(TAG, "Hazard detection stopped")
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun loadModel() {
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "models/detect.tflite")
            interpreter = Interpreter(modelBuffer)
            labels = FileUtil.loadLabels(context, "models/labels.txt")
            Log.d(TAG, "Loaded hazard model with ${labels.size} labels")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to load hazard model", e)
            interpreter = null
            labels = emptyList()
        }
    }

    private fun runInference(image: TensorImage): Array<Detection> {
        val inputBuffer = image.buffer

        val outputBoxes = Array(1) { Array(MAX_DETECTIONS) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(MAX_DETECTIONS) }
        val outputScores = Array(1) { FloatArray(MAX_DETECTIONS) }
        val outputCount = FloatArray(1)

        val outputMap = mapOf(
            0 to outputBoxes,
            1 to outputClasses,
            2 to outputScores,
            3 to outputCount
        )

        interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        val results = mutableListOf<Detection>()
        val numDetections = outputCount.first().toInt().coerceAtMost(MAX_DETECTIONS)

        for (i in 0 until numDetections) {
            val score = outputScores[0][i]
            if (score < SCORE_THRESHOLD) continue

            val box = outputBoxes[0][i]
            val classId = outputClasses[0][i].toInt()
            val label = if (classId in labels.indices) labels[classId] else "unknown"

            results.add(
                Detection(
                    box = box,
                    label = label,
                    confidence = score
                )
            )
        }

        return results.toTypedArray()
    }

    private fun filterAndEmit(detections: Array<Detection>) {
        val centerX = INPUT_SIZE / 2f
        val centerY = INPUT_SIZE / 2f
        val centerRadius = INPUT_SIZE * CENTER_CROP_RATIO / 2f
        val minArea = INPUT_SIZE * INPUT_SIZE * MIN_BOX_AREA_RATIO

        for (detection in detections) {
            val box = detection.box
            val boxCenterX = (box[1] + box[3]) / 2f * INPUT_SIZE
            val boxCenterY = (box[0] + box[2]) / 2f * INPUT_SIZE
            val boxWidth = (box[3] - box[1]) * INPUT_SIZE
            val boxHeight = (box[2] - box[0]) * INPUT_SIZE
            val boxArea = boxWidth * boxHeight
            val distanceFromCenter = kotlin.math.sqrt(
                (boxCenterX - centerX) * (boxCenterX - centerX) +
                    (boxCenterY - centerY) * (boxCenterY - centerY)
            )

            if (distanceFromCenter <= centerRadius && boxArea >= minArea) {
                val severity = when {
                    boxHeight / INPUT_SIZE > 0.6 -> "danger"
                    boxHeight / INPUT_SIZE > 0.3 -> "warn"
                    else -> "info"
                }

                val mappedLabel = mapLabel(detection.label.lowercase())
                val kind = mapKind(mappedLabel)

                onHazard(
                    HazardEvent(
                        id = "hazard_${System.currentTimeMillis()}",
                        label = mappedLabel,
                        severity = severity,
                        kind = kind,
                        confidence = detection.confidence,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun mapLabel(label: String): String {
        return when {
            label.contains("person") -> "person"
            label.contains("bicycle") || label.contains("motorcycle") -> "bike"
            label.contains("chair") || label.contains("bench") -> "chair"
            label.contains("car") || label.contains("truck") -> "vehicle"
            else -> "obstacle"
        }
    }

    private fun mapKind(mappedLabel: String): String {
        return when (mappedLabel) {
            "person" -> "moving_object"
            "bike", "vehicle" -> "moving_object"
            else -> "obstacle"
        }
    }

    private inner class Analyzer : ImageAnalysis.Analyzer {
        @ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisTime < ANALYSIS_INTERVAL) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image
            if (mediaImage != null && interpreter != null) {
                try {
                    val bitmap = toBitmap(mediaImage)
                    val tensorImage = TensorImage.fromBitmap(bitmap)
                    val processed = imageProcessor.process(tensorImage)
                    val results = runInference(processed)
                    filterAndEmit(results)
                    lastAnalysisTime = now
                } catch (e: Exception) {
                    Log.e(TAG, "Hazard analysis failed", e)
                }
            }

            imageProxy.close()
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    data class Detection(
        val box: FloatArray,
        val label: String,
        val confidence: Float
    )

    data class HazardEvent(
        val id: String,
        val label: String,
        val severity: String,
        val kind: String,
        val confidence: Float,
        val timestamp: Long
    )
}
