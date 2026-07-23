package com.efidriver.icarosnet

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.efidriver.icarosnet.license.AppLicenseManager
import com.efidriver.icarosnet.ui.screens.ConfigScreen
import com.efidriver.icarosnet.ui.screens.LicenseScreen
import com.efidriver.icarosnet.ui.screens.PermissionsScreen
import com.efidriver.icarosnet.ui.screens.isAccessibilityServiceEnabled
import com.efidriver.icarosnet.services.ScraperAccessibilityService
import com.efidriver.icarosnet.ui.theme.EfidriverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EfidriverTheme {
                val licenseManager = remember { AppLicenseManager(this) }
                var licenseReady by remember { mutableStateOf(licenseManager.hasUsableLicense()) }
                var permissionsGranted by remember { 
                    mutableStateOf(
                        Settings.canDrawOverlays(this) && 
                        isAccessibilityServiceEnabled(this, ScraperAccessibilityService::class.java)
                    )
                }

                // Efecto para re-verificar cuando la actividad vuelve a estar activa
                DisposableEffect(Unit) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            licenseReady = licenseManager.hasUsableLicense()
                            permissionsGranted = Settings.canDrawOverlays(this@MainActivity) && 
                                isAccessibilityServiceEnabled(this@MainActivity, ScraperAccessibilityService::class.java)
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose {
                        lifecycle.removeObserver(observer)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        if (!licenseReady) {
                            LicenseScreen(onLicenseReady = {
                                licenseReady = true
                            })
                        } else if (!permissionsGranted) {
                            PermissionsScreen(onAllPermissionsGranted = {
                                permissionsGranted = true
                            })
                        } else {
                            ConfigScreen()
                        }
                    }
                }
            }
        }
    }
}
