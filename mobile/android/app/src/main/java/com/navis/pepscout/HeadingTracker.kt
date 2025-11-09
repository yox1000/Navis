package com.navis.pepscout

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class HeadingTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var fallbackSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
    private var isTracking = false
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading.asStateFlow()
    private var lastEmitTime = 0L
    private val emitInterval = 500L

    fun start() {
        if (isTracking) return
        val sensor = rotationSensor ?: fallbackSensor
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            isTracking = true
        }
    }

    fun stop() {
        if (isTracking) {
            sensorManager.unregisterListener(this)
            isTracking = false
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmitTime < emitInterval) return

        val azimuth = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                var degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (degrees < 0) degrees += 360f
                degrees
            }
            Sensor.TYPE_ORIENTATION -> {
                var degrees = event.values[0]
                if (degrees < 0) degrees += 360f
                degrees
            }
            else -> return
        }

        _heading.value = azimuth
        lastEmitTime = currentTime
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not required
    }
}
