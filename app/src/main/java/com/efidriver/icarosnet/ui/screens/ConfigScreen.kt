package com.efidriver.icarosnet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.efidriver.icarosnet.engine.SettingsManager
import com.efidriver.icarosnet.license.AppLicenseManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val settingsManager = remember { SettingsManager(context) }
    val licenseManager = remember { AppLicenseManager(context) }
    var licenseSnapshot by remember { mutableStateOf(licenseManager.getSnapshot()) }

    // Función auxiliar para formatear decimales a String limpio
    fun formatDecimal(value: Double): String = String.format("%.2f", value).replace(",", ".")

    var maxPickup by remember { mutableStateOf(formatDecimal(settingsManager.maxPickupDistance)) }
    var minUsdKm by remember { mutableStateOf(formatDecimal(settingsManager.minUsdPerKm)) }
    var previewTripDistance by remember { mutableStateOf(formatDecimal(settingsManager.previewTripDistanceKm)) }
    var commission by remember { mutableStateOf(formatDecimal(settingsManager.commissionPercent)) }
    var structuralProbeDebugEnabled by remember { mutableStateOf(settingsManager.structuralProbeDebugEnabled) }
    var showFormulaHelp by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
            modifier = Modifier.padding(bottom = 12.dp)
        )
        OutlinedButton(
            onClick = { showFormulaHelp = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text("Ayuda: fórmula de rentabilidad")
        }

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

        Spacer(modifier = Modifier.height(24.dp))

        DebugSwitch(
            title = "Sonda estructural debug",
            description = "Activa logs profundos del árbol de InDrive. Puede afectar rendimiento.",
            checked = structuralProbeDebugEnabled,
            onCheckedChange = {
                structuralProbeDebugEnabled = it
                settingsManager.structuralProbeDebugEnabled = it
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        LicenseStatusCard(
            deviceCode = licenseSnapshot.deviceCode,
            machineId = licenseSnapshot.machineId,
            expiresAt = licenseSnapshot.expiresAtEpochMs?.formatDateTime() ?: "Sin fecha",
            lastValidatedAt = licenseSnapshot.lastValidatedAtEpochMs?.formatDateTime() ?: "No validada",
            usable = licenseSnapshot.isUsable(),
            onCopyDeviceCode = {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Efi-Driver device code", licenseSnapshot.deviceCode)
                )
            },
            onClearLicense = {
                licenseSnapshot = licenseManager.clearLicense()
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
        
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

    if (showFormulaHelp) {
        ProfitabilityFormulaDialog(
            onDismiss = { showFormulaHelp = false }
        )
    }
}

@Composable
private fun LicenseStatusCard(
    deviceCode: String,
    machineId: String,
    expiresAt: String,
    lastValidatedAt: String,
    usable: Boolean,
    onCopyDeviceCode: () -> Unit,
    onClearLicense: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Licencia", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(if (usable) "Estado: activa" else "Estado: requiere validación")
            Text("Vence: $expiresAt")
            Text("Última validación: $lastValidatedAt")
            Spacer(modifier = Modifier.height(10.dp))
            Text("ID dispositivo", fontWeight = FontWeight.SemiBold)
            Text(deviceCode, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Machine ID", fontWeight = FontWeight.SemiBold)
            Text(machineId, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyDeviceCode) {
                    Text("Copiar ID")
                }
                OutlinedButton(onClick = onClearLicense) {
                    Text("Quitar licencia")
                }
            }
        }
    }
}

private fun Long.formatDateTime(): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))
}

@Composable
private fun ProfitabilityFormulaDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Entendido")
            }
        },
        title = {
            Text("Fórmula de rentabilidad")
        },
        text = {
            Column {
                Text("Ingreso neto = tarifa del viaje - comisión de plataforma.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Distancia total = distancia de recogida + distancia del viaje.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Rentabilidad = ingreso neto / distancia total.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Gana = ingreso neto - (distancia total x rentabilidad mínima).")
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Un viaje es rentable solo si la recogida está dentro del máximo configurado y Gana es mayor o igual a $0.00.",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Si el neto queda por debajo del costo base, el overlay muestra Pierde en lugar de Gana.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("En PREVIEW, la distancia del viaje usa el valor estimado de settings. En REAL, se usa la distancia leída desde la tarjeta del viaje.")
            }
        }
    )
}

@Composable
fun DebugSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(text = description, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
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
