package com.efidriver.icarosnet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.efidriver.icarosnet.engine.SettingsManager

@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }

    // Función auxiliar para formatear decimales a String limpio
    fun formatDecimal(value: Double): String = String.format("%.2f", value).replace(",", ".")

    var maxPickup by remember { mutableStateOf(formatDecimal(settingsManager.maxPickupDistance)) }
    var minUsdKm by remember { mutableStateOf(formatDecimal(settingsManager.minUsdPerKm)) }
    var previewTripDistance by remember { mutableStateOf(formatDecimal(settingsManager.previewTripDistanceKm)) }
    var commission by remember { mutableStateOf(formatDecimal(settingsManager.commissionPercent)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "Panel de Control Efi-Driver",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Configura tus umbrales de rentabilidad",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        ConfigInput(
            label = "Distancia Máx. Recogida (N km)",
            value = maxPickup,
            onValueChange = { 
                maxPickup = it
                val sanitized = it.replace(",", ".")
                sanitized.toDoubleOrNull()?.let { v -> settingsManager.maxPickupDistance = v }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        ConfigInput(
            label = "Rentabilidad Mínima ($/km)",
            value = minUsdKm,
            onValueChange = { 
                minUsdKm = it
                val sanitized = it.replace(",", ".")
                sanitized.toDoubleOrNull()?.let { v -> settingsManager.minUsdPerKm = v }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        ConfigInput(
            label = "Distancia Estimada Viaje Preview (km)",
            value = previewTripDistance,
            onValueChange = {
                previewTripDistance = it
                val sanitized = it.replace(",", ".")
                sanitized.toDoubleOrNull()?.let { v -> settingsManager.previewTripDistanceKm = v }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        ConfigInput(
            label = "Comisión de Plataforma (%)",
            value = commission,
            onValueChange = { 
                commission = it
                val sanitized = it.replace(",", ".")
                sanitized.toDoubleOrNull()?.let { v -> settingsManager.commissionPercent = v }
            }
        )

        Spacer(modifier = Modifier.weight(1f))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Estado: Escaneando InDrive en tiempo real...",
                modifier = Modifier.padding(16.dp),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ConfigInput(label: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
    }
}
