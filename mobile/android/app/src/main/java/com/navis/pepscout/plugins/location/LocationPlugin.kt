package com.navis.pepscout.plugins.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.location.Location
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

@CapacitorPlugin(
    name = "LocationPlugin",
    permissions = [
        Permission(strings = [Manifest.permission.ACCESS_FINE_LOCATION]),
        Permission(strings = [Manifest.permission.ACCESS_COARSE_LOCATION]),
        Permission(strings = [Manifest.permission.FOREGROUND_SERVICE]),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS])
    ]
)
class LocationPlugin : Plugin() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "location_service"
        const val NOTIFICATION_ID = 1001
    }

    override fun load() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)
        createNotificationChannel()
    }

    @PluginMethod
    fun start(call: PluginCall) {
        if (!hasLocationPermissions()) {
            call.reject("Location permissions not granted")
            return
        }

        if (isTracking) {
            call.resolve()
            return
        }

        startLocationUpdates()
        isTracking = true
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        stopLocationUpdates()
        isTracking = false
        call.resolve()
    }

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TimeUnit.SECONDS.toMillis(2)
        ).apply {
            setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(2))
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    emitLocationEvent(location)
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            context.mainLooper
        )

        showForegroundNotification()
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun emitLocationEvent(location: Location) {
        val data = JSObject().apply {
            put("lat", location.latitude)
            put("lon", location.longitude)
            put("accuracy_m", location.accuracy.toDouble())
            put("bearing_deg", if (location.hasBearing()) location.bearing.toDouble() else 0.0)
            put("ts", System.currentTimeMillis())
        }
        
        notifyListeners("location", data)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Location Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Pep Scout navigation location updates"
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showForegroundNotification() {
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Pep Scout")
            .setContentText("Navigation active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun handleOnDestroy() {
        scope.cancel()
        stopLocationUpdates()
        super.handleOnDestroy()
    }
}