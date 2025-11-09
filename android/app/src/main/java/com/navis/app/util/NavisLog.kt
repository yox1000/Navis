package com.navis.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NavisLog {
    private const val DEFAULT_TAG = "Navis"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "navis.log")
        logFile = file
        writeLine("INFO", DEFAULT_TAG, "Logging initialized -> ${file.absolutePath}")
    }

    fun v(tag: String, message: String) = log("VERBOSE", tag, message) { Log.v(tag, message) }
    fun d(tag: String, message: String) = log("DEBUG", tag, message) { Log.d(tag, message) }
    fun i(tag: String, message: String) = log("INFO", tag, message) { Log.i(tag, message) }
    fun w(tag: String, message: String) = log("WARN", tag, message) { Log.w(tag, message) }
    fun e(tag: String, message: String, error: Throwable? = null) = log("ERROR", tag, buildString {
        append(message)
        if (error != null) {
            append(" :: ").append(error.message ?: error::class.java.simpleName)
        }
    }) {
        Log.e(tag, message, error)
    }

    private fun log(
        level: String,
        tag: String,
        message: String,
        logcat: () -> Unit
    ) {
        logcat()
        writeLine(level, tag, message)
    }

    @Synchronized
    private fun writeLine(level: String, tag: String, message: String) {
        val file = logFile ?: return
        val stamp = dateFormat.format(Date())
        val line = "$stamp [$level] $tag: $message\n"
        try {
            FileWriter(file, true).use { writer ->
                writer.write(line)
            }
        } catch (_: IOException) {
        }
    }
}
