package com.omlight

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.omlight.ui.AppRoot
import com.omlight.ui.OMTheme

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ConnectGate re-checks on use; no-op here. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestWifiPerms()
        setContent {
            OMTheme { AppRoot() }
        }
    }

    /** Nearby-wifi (31+) or location (29-30) for in-app AP join. */
    private fun requestWifiPerms() {
        if (Build.VERSION.SDK_INT < 29) return
        val want = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } + if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        } + if (Build.VERSION.SDK_INT >= 37) {
            // Android 17 local-network enforcement: declaring
            // ACCESS_LOCAL_NETWORK without requesting it leaves LAN
            // blocked even for targetSdk 36 apps.
            arrayOf("android.permission.ACCESS_LOCAL_NETWORK")
        } else {
            emptyArray()
        }
        val missing = want.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
}
