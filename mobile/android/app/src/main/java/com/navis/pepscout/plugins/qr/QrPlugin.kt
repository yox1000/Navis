package com.navis.pepscout.plugins.qr

import android.Manifest
import android.content.Intent
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission

@CapacitorPlugin(
    name = "QrPlugin",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA])
    ]
)
class QrPlugin : Plugin() {
    
    companion object {
        const val QR_SCAN_REQUEST_CODE = 1002
    }

    @PluginMethod
    fun scan(call: PluginCall) {
        if (!hasCameraPermission()) {
            call.reject("Camera permission not granted")
            return
        }

        saveCall(call)
        startQrScanActivity()
    }

    private fun hasCameraPermission(): Boolean {
        return getPermissionState(Manifest.permission.CAMERA) == com.getcapacitor.PermissionState.GRANTED
    }

    private fun startQrScanActivity() {
        val intent = Intent(activity, QrScanActivity::class.java)
        startActivityForResult(call, intent, "qrScanResult")
    }

    @ActivityCallback
    private fun qrScanResult(call: PluginCall?, result: android.app.Activity.Result?) {
        if (call == null) return

        val data = result?.data
        if (result?.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val payload = data.getStringExtra("qr_payload")
            if (payload != null) {
                val ret = JSObject().apply {
                    put("payload", payload)
                }
                call.resolve(ret)
            } else {
                call.reject("No QR code data received")
            }
        } else {
            call.reject("QR scan cancelled or failed")
        }
    }
}