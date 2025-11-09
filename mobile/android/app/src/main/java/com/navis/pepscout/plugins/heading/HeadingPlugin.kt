package com.navis.pepscout.plugins.heading

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.*

@CapacitorPlugin(name = "HeadingPlugin")
class HeadingPlugin : Plugin(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var isTracking = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastEmitTime = 0L
    private val emitInterval = 500L // 500ms

    override fun load() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        if (rotationVectorSensor == null) {
            // Fallback to magnetometer + accelerometer if rotation vector not available
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        }
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (rotationVectorSensor == null) {
            call.reject("Rotation vector sensor not available")
            return
        }

        if (isTracking) {
            call.resolve()
            return
        }

        sensorManager.registerListener(
            this,
            rotationVectorSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        
        isTracking = true
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        if (isTracking) {
            sensorManager.unregisterListener(this)
            isTracking = false
        }
        call.resolve()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isTracking) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmitTime < emitInterval) return

        val azimuth = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                
                // Convert from radians to degrees and normalize to 0-360
                var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (degrees < 0) degrees += 360f
                degrees
            }
            Sensor.TYPE_ORIENTATION -> {
                // Deprecated but fallback option
                var degrees = event.values[0]
                if (degrees < 0) degrees += 360f
                degrees
            }
            else -> return
        }

        emitHeadingEvent(azimuth)
        lastEmitTime = currentTime
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Handle accuracy changes if needed
    }

    private fun emitHeadingEvent(azimuth: Float) {
        val data = JSObject().apply {
            put("azimuth", azimuth.toDouble())
            put("ts", System.currentTimeMillis())
        }
        
        notifyListeners("heading", data)
    }

    override fun handleOnDestroy() {
        scope.cancel()
        if (isTracking) {
            sensorManager.unregisterListener(this)
        }
        super.handleOnDestroy()
    }
}