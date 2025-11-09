package com.navis.pepscout.cv

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Enhanced computer vision processor using OpenCV
 * Provides free-space vector computation and wall proximity detection
 */
class EnhancedCVProcessor {
    
    companion object {
        private const val TAG = "EnhancedCVProcessor"
        private const val HORIZONTAL_BINS = 7 // Number of horizontal bins for free-space analysis
        private const val EDGE_THRESHOLD = 100.0 // Canny edge detection threshold
        private const val WALL_EDGE_DENSITY_THRESHOLD = 0.3f // Threshold for wall detection
        private const val FOE_CONFIDENCE_THRESHOLD = 0.7f // Focus of expansion confidence threshold
    }
    
    // Frame history for temporal analysis
    private val frameHistory = mutableListOf<Mat>()
    private val maxFrameHistory = 5
    
    init {
        // Initialize OpenCV if not already done
        if (!org.opencv.android.OpenCVLoaderCallback::class.java.isAssignableFrom(Any::class.java)) {
            Log.d(TAG, "OpenCV not loaded - will use fallback methods")
        }
    }
    
    /**
     * Process frame for safety features
     * Returns combined safety information
     */
    suspend fun processSafetyFeatures(bitmap: Bitmap): SafetyFeatures {
        return withContext(Dispatchers.Default) {
            try {
                // Convert bitmap to OpenCV Mat
                val frame = Mat()
                Utils.bitmapToMat(bitmap, frame)
                
                // Add to frame history
                addToFrameHistory(frame.clone())
                
                // Compute various safety features
                val freeSpaceVector = computeFreeSpaceVector(frame)
                val wallProximity = computeWallProximity(frame)
                val opticalFlow = computeOpticalFlow(frame)
                
                SafetyFeatures(
                    freeSpaceVector = freeSpaceVector,
                    wallProximity = wallProximity,
                    opticalFlow = opticalFlow,
                    timestamp = System.currentTimeMillis()
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing safety features", e)
                SafetyFeatures.empty()
            }
        }
    }
    
    /**
     * Compute free-space vector using disparity and depth estimation
     */
    private fun computeFreeSpaceVector(frame: Mat): FreeSpaceVector {
        try {
            // Convert to grayscale
            val gray = Mat()
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            
            // Apply Gaussian blur to reduce noise
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            
            // Detect edges using Canny
            val edges = Mat()
            Imgproc.Canny(blurred, edges, EDGE_THRESHOLD, EDGE_THRESHOLD * 2)
            
            // Divide frame into horizontal bins
            val height = edges.rows()
            val width = edges.cols()
            val binWidth = width / HORIZONTAL_BINS
            val binScores = FloatArray(HORIZONTAL_BINS)
            
            // Analyze each bin for obstacle density
            for (bin in 0 until HORIZONTAL_BINS) {
                val startX = bin * binWidth
                val endX = minOf((bin + 1) * binWidth, width)
                
                // Focus on lower 60% of image (ground level obstacles)
                val startY = (height * 0.4).toInt()
                val endY = height
                
                val roi = Rect(startX, startY, endX - startX, endY - startY)
                val binEdges = Mat(edges, roi)
                
                // Count edge pixels in this bin
                val edgeCount = Core.countNonZero(binEdges)
                val totalPixels = binEdges.rows() * binEdges.cols()
                val edgeDensity = edgeCount.toFloat() / totalPixels
                
                // Lower edge density = more free space
                binScores[bin] = 1.0f - edgeDensity
            }
            
            // Find the bin with the highest free-space score
            val maxBinIndex = binScores.indices.maxByOrNull { binScores[it] } ?: (HORIZONTAL_BINS / 2)
            val maxScore = binScores[maxBinIndex]
            
            // Convert bin index to angle (-90 to +90 degrees)
            val centerBin = HORIZONTAL_BINS / 2
            val anglePerBin = 180.0 / HORIZONTAL_BINS
            val angle = (maxBinIndex - centerBin) * anglePerBin
            
            // Calculate confidence based on score difference from center
            val confidence = maxScore.coerceIn(0.0f, 1.0f)
            
            return FreeSpaceVector(
                angleDeg = angle,
                confidence = confidence,
                binDistribution = binScores
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error computing free-space vector", e)
            return FreeSpaceVector(0.0, 0.0f, FloatArray(HORIZONTAL_BINS))
        }
    }
    
    /**
     * Compute wall proximity using edge density and focus of expansion
     */
    private fun computeWallProximity(frame: Mat): WallProximity {
        try {
            // Convert to grayscale
            val gray = Mat()
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
            
            // Detect edges
            val edges = Mat()
            Imgproc.Canny(gray, edges, EDGE_THRESHOLD, EDGE_THRESHOLD * 2)
            
            // Analyze central region for wall-like patterns
            val height = edges.rows()
            val width = edges.cols()
            val centerX = width / 2
            val centerY = height / 2
            val roiSize = minOf(width, height) / 3
            
            val roi = Rect(
                centerX - roiSize / 2,
                centerY - roiSize / 2,
                roiSize,
                roiSize
            )
            
            val centerEdges = Mat(edges, roi)
            
            // Calculate edge density in center region
            val edgeCount = Core.countNonZero(centerEdges)
            val totalPixels = centerEdges.rows() * centerEdges.cols()
            val edgeDensity = edgeCount.toFloat() / totalPixels
            
            // Detect focus of expansion (FOE) for wall detection
            val foeConfidence = detectFocusOfExpansion(edges)
            
            // Wall detected if high edge density + low FOE movement
            val isWallDetected = edgeDensity > WALL_EDGE_DENSITY_THRESHOLD && 
                                foeConfidence < FOE_CONFIDENCE_THRESHOLD
            
            return WallProximity(
                detected = isWallDetected,
                edgeDensity = edgeDensity,
                foeConfidence = foeConfidence,
                estimatedDistance = if (isWallDetected) 1.0 / edgeDensity else Double.MAX_VALUE
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error computing wall proximity", e)
            return WallProximity(false, 0.0f, 0.0f, Double.MAX_VALUE)
        }
    }
    
    /**
     * Detect focus of expansion to identify wall proximity
     */
    private fun detectFocusOfExpansion(edges: Mat): Float {
        try {
            // Use Hough lines to detect dominant orientations
            val lines = Mat()
            Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 50)
            
            if (lines.rows() == 0) {
                return 0.0f
            }
            
            // Analyze line orientations
            var verticalLines = 0
            var horizontalLines = 0
            
            for (i in 0 until lines.rows()) {
                val line = lines.get(i, 0)
                val theta = line[1]
                
                // Check if line is more vertical or horizontal
                val angle = Math.toDegrees(theta)
                when {
                    angle < 30 || angle > 150 -> verticalLines++
                    angle > 60 && angle < 120 -> horizontalLines++
                }
            }
            
            // High ratio of vertical to horizontal lines suggests wall
            val totalLines = verticalLines + horizontalLines
            if (totalLines == 0) return 0.0f
            
            val verticalRatio = verticalLines.toFloat() / totalLines
            return if (verticalRatio > 0.7) 1.0f - verticalRatio else verticalRatio
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting focus of expansion", e)
            return 0.0f
        }
    }
    
    /**
     * Compute optical flow for motion analysis
     */
    private fun computeOpticalFlow(currentFrame: Mat): OpticalFlow {
        try {
            if (frameHistory.size < 2) {
                return OpticalFlow(0.0f, 0.0f, 0.0f)
            }
            
            val prevFrame = frameHistory[frameHistory.size - 2]
            
            // Convert to grayscale
            val prevGray = Mat()
            val currGray = Mat()
            Imgproc.cvtColor(prevFrame, prevGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(currentFrame, currGray, Imgproc.COLOR_BGR2GRAY)
            
            // Detect corners in previous frame
            val corners = MatOfPoint2f()
            Imgproc.goodFeaturesToTrack(
                prevGray, corners, 100, 0.3, 7.0,
                Mat(), 7, false, 0.04
            )
            
            if (corners.total() == 0L) {
                return OpticalFlow(0.0f, 0.0f, 0.0f)
            }
            
            // Calculate optical flow
            val nextCorners = MatOfPoint2f()
            val status = MatOfByte()
            val errors = MatOfFloat()
            
            Imgproc.calcOpticalFlowPyrLK(
                prevGray, currGray, corners, nextCorners,
                status, errors
            )
            
            // Analyze flow vectors
            val prevPoints = corners.toArray()
            val nextPoints = nextCorners.toArray()
            val statusArray = status.toArray()
            
            var totalFlowX = 0.0
            var totalFlowY = 0.0
            var validPoints = 0
            
            for (i in prevPoints.indices) {
                if (statusArray[i] == 1.toByte()) {
                    val dx = nextPoints[i].x - prevPoints[i].x
                    val dy = nextPoints[i].y - prevPoints[i].y
                    totalFlowX += dx
                    totalFlowY += dy
                    validPoints++
                }
            }
            
            return if (validPoints > 0) {
                val avgFlowX = (totalFlowX / validPoints).toFloat()
                val avgFlowY = (totalFlowY / validPoints).toFloat()
                val magnitude = sqrt(avgFlowX * avgFlowX + avgFlowY * avgFlowY)
                
                OpticalFlow(avgFlowX, avgFlowY, magnitude)
            } else {
                OpticalFlow(0.0f, 0.0f, 0.0f)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error computing optical flow", e)
            return OpticalFlow(0.0f, 0.0f, 0.0f)
        }
    }
    
    /**
     * Add frame to history with size limit
     */
    private fun addToFrameHistory(frame: Mat) {
        frameHistory.add(frame)
        while (frameHistory.size > maxFrameHistory) {
            frameHistory.removeAt(0)
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        frameHistory.forEach { it.release() }
        frameHistory.clear()
    }
}

/**
 * Combined safety features from computer vision analysis
 */
data class SafetyFeatures(
    val freeSpaceVector: FreeSpaceVector,
    val wallProximity: WallProximity,
    val opticalFlow: OpticalFlow,
    val timestamp: Long
) {
    companion object {
        fun empty() = SafetyFeatures(
            FreeSpaceVector(0.0, 0.0f, FloatArray(7)),
            WallProximity(false, 0.0f, 0.0f, Double.MAX_VALUE),
            OpticalFlow(0.0f, 0.0f, 0.0f),
            System.currentTimeMillis()
        )
    }
}

/**
 * Free-space vector indicating direction of least obstacles
 */
data class FreeSpaceVector(
    val angleDeg: Double, // Angle from center (-90 to +90)
    val confidence: Float, // Confidence in the direction (0.0 to 1.0)
    val binDistribution: FloatArray // Free-space scores for each bin
)

/**
 * Wall proximity detection result
 */
data class WallProximity(
    val detected: Boolean,
    val edgeDensity: Float,
    val foeConfidence: Float, // Focus of expansion confidence
    val estimatedDistance: Double // Estimated distance to wall
)

/**
 * Optical flow analysis result
 */
data class OpticalFlow(
    val averageFlowX: Float,
    val averageFlowY: Float,
    val magnitude: Float
)