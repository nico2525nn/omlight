package com.omlight.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** QR formats we accept from the scanner. */
private val QR_ONLY = GmsBarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    .enableAutoZoom()
    .build()

/**
 * Headless QR scan trigger. Launches the GMS Code Scanner (OS-provided
 * full-screen UI + decode) and reports the raw payload via [onScanned].
 * No camera code in our APK: GMS owns the permission, preview, decode.
 *
 * Compose with [start] true to fire once; set false / remove to reset.
 */
@Composable
fun QrScanTrigger(start: Boolean, onScanned: (String) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* re-checked below on next pass */ }

    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        // GMS scanner needs the camera permission on our UID.
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (!asked) {
                asked = true
                ask.launch(Manifest.permission.CAMERA)
            }
            // Permission flow is async; the caller re-fires on return.
            // Signal done so the sheet state doesn't stick open.
            onDone()
            return@LaunchedEffect
        }
        val activity = ctx as? ComponentActivity
        if (activity == null) {
            onDone()
            return@LaunchedEffect
        }
        try {
            val scanner = GmsBarcodeScanning.getClient(activity, QR_ONLY)
            scanner.startScan()
                .addOnSuccessListener { code ->
                    code.rawValue?.let(onScanned)
                    onDone()
                }
                .addOnFailureListener { onDone() }
                .addOnCanceledListener { onDone() }
        } catch (_: Exception) {
            // GMS missing (de-Googled ROM): caller falls back to paste.
            onDone()
        }
    }
}
