package com.navis.pepscout.plugins.cv

import android.Manifest
import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@CapacitorPlugin(
    name = "CVPlugin",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA])
    ]
)
class CVPlugin : Plugin() {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private var imageProcessor: ImageProcessor? = null
    private var cameraExecutor: ExecutorService? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isAnalyzing = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Model input/output specifications
    private val inputSize = 320
    private val maxDetections = 10
    private val scoreThreshold = 0.5f
    private val centerCropRatio = 0.6f // Only detect objects in center 60% of frame
    private val minBoxAreaRatio = 0.01f // Minimum box area relative to input size
    
    companion object {
        const val TAG = "CVPlugin"
    }

    override fun load() {
        super.load()
        loadModel()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun loadModel() {
        try {
            // Load lightweight model from assets
            val modelBuffer = FileUtil.loadMappedFile(context, "models/detect.tflite")
            interpreter = Interpreter(modelBuffer)
            
            // Load labels
            labels = FileUtil.loadLabels(context, "models/labels.txt")
            
            // Setup image processor
            imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
                .build()
                
            Log.d(TAG, "Model loaded successfully with ${labels.size} classes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
        }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (!hasCameraPermission()) {
            call.reject("Camera permission not granted")
            return
        }

        if (interpreter == null) {
            call.reject("Model not loaded")
            return
        }

        if (isAnalyzing) {
            call.resolve()
            return
        }

        startCamera()
        isAnalyzing = true
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        stopCamera()
        isAnalyzing = false
        call.resolve()
    }

    private fun hasCameraPermission(): Boolean {
        return getPermissionState(Manifest.permission.CAMERA) == com.getcapacitor.PermissionState.GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(inputSize, inputSize))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis?.setAnalyzer(cameraExecutor!!, HazardAnalyzer())

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    activity as androidx.lifecycle.LifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
                
                Log.d(TAG, "Camera started for CV analysis")
            } catch (exc: Exception) {
                Log.e(TAG, "Camera initialization failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageAnalysis = null
        Log.d(TAG, "Camera stopped")
    }

    private inner class HazardAnalyzer : ImageAnalysis.Analyzer {
        private var lastAnalysisTime = 0L
        private val analysisInterval = 500L // Analyze every 500ms

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastAnalysisTime < analysisInterval) {
                imageProxy.close()
                return
            }

            try {
                val mediaImage = imageProxy.image
                if (mediaImage != null && interpreter != null) {
                    processImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                    lastAnalysisTime = currentTime
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
            } finally {
                imageProxy.close()
            }
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImage(image: android.media.Image, rotationDegrees: Int) {
        try {
            // Convert to TensorImage
            val bitmap = imageProxyToBitmap(image)
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val processedImage = imageProcessor?.process(tensorImage)

            // Run inference
            val detections = runInference(processedImage!!)
            
            // Filter detections and emit hazard events
            filterAndEmitHazards(detections)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in image processing", e)
        }
    }

    private fun imageProxyToBitmap(image: android.media.Image): Bitmap {
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

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun runInference(tensorImage: TensorImage): Array<Detection> {
        val inputBuffer = tensorImage.buffer
        
        // Output tensors: [boxes, classes, scores, num_detections]
        val outputBoxes = Array(1) { Array(maxDetections) { FloatArray(4) } }
        val outputClasses = Array(1) { FloatArray(maxDetections) }
        val outputScores = Array(1) { FloatArray(maxDetections) }
        val outputNumDetections = FloatArray(1)

        val outputs = mapOf(
            0 to outputBoxes,
            1 to outputClasses,
            2 to outputScores,
            3 to outputNumDetections
        )

        interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        val detections = mutableListOf<Detection>()
        val numDetections = outputNumDetections[0].toInt()

        for (i in 0 until minOf(numDetections, maxDetections)) {
            val score = outputScores[0][i]
            if (score >= scoreThreshold) {
                val box = outputBoxes[0][i]
                val classId = outputClasses[0][i].toInt()
                
                detections.add(Detection(
                    box = box,
                    classId = classId,
                    confidence = score,
                    label = if (classId < labels.size) labels[classId] else "unknown"
                ))
            }
        }

        return detections.toTypedArray()
    }

    private fun filterAndEmitHazards(detections: Array<Detection>) {
        val centerX = inputSize / 2f
        val centerY = inputSize / 2f
        val centerRadius = inputSize * centerCropRatio / 2f
        val minArea = inputSize * inputSize * minBoxAreaRatio

        for (detection in detections) {
            val box = detection.box
            val boxCenterX = (box[1] + box[3]) / 2f * inputSize // ymin, xmin, ymax, xmax
            val boxCenterY = (box[0] + box[2]) / 2f * inputSize
            val boxWidth = (box[3] - box[1]) * inputSize
            val boxHeight = (box[2] - box[0]) * inputSize
            val boxArea = boxWidth * boxHeight

            // Check if detection is in center region and large enough
            val distanceFromCenter = kotlin.math.sqrt(
                (boxCenterX - centerX) * (boxCenterX - centerX) +
                (boxCenterY - centerY) * (boxCenterY - centerY)
            )

            if (distanceFromCenter <= centerRadius && boxArea >= minArea) {
                val hazard = createHazardEvent(detection, boxHeight / inputSize)
                emitHazardEvent(hazard)
            }
        }
    }

    private fun createHazardEvent(detection: Detection, heightRatio: Float): JSObject {
        val severity = when {
            heightRatio > 0.6 -> "danger"
            heightRatio > 0.3 -> "warn"
            else -> "info"
        }

        val kind = when (detection.label.lowercase()) {
            "person", "cyclist", "motorcyclist" -> "moving_object"
            "chair", "bench", "table", "couch" -> "obstacle"
            "bicycle", "motorcycle", "car", "bus", "truck" -> "moving_object"
            else -> "obstacle"
        }

        val mappedLabel = when (detection.label.lowercase()) {
            "person" -> "person"
            "bicycle", "motorcycle" -> "bike"
            "chair", "bench", "table", "couch" -> "chair"
            else -> "unknown"
        }

        return JSObject().apply {
            put("id", "hazard_${System.currentTimeMillis()}")
            put("ts", System.currentTimeMillis())
            put("where", JSObject().apply {
                put("type", "indoor") // Assume indoor for CV detection
            })
            put("kind", kind)
            put("label", mappedLabel)
            put("severity", severity)
            put("ttl_s", 3)
        }
    }

    private fun emitHazardEvent(hazard: JSObject) {
        notifyListeners("hazard", hazard)
        Log.d(TAG, "Hazard detected: ${hazard.getString("label")} - ${hazard.getString("severity")}")
    }

    override fun handleOnDestroy() {
        scope.cancel()
        stopCamera()
        cameraExecutor?.shutdown()
        interpreter?.close()
        super.handleOnDestroy()
    }

    data class Detection(
        val box: FloatArray,
        val classId: Int,
        val confidence: Float,
        val label: String
    )
}