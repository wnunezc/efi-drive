package com.efidriver.icarosnet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.efidriver.icarosnet.license.AppLicenseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LicenseScreen(onLicenseReady: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val scope = rememberCoroutineScope()
    val licenseManager = remember { AppLicenseManager(context) }
    var snapshot by remember { mutableStateOf(licenseManager.getSnapshot()) }
    var licenseKey by remember { mutableStateOf(snapshot.licenseKey.orEmpty()) }
    var statusMessage by remember {
        mutableStateOf(
            if (snapshot.valid) "Licencia guardada, requiere revalidación."
            else "Ingresa tu llave para activar Efi-Driver."
        )
    }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Licencia Efi-Driver",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Activa una licencia vigente para usar el servicio.",
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.secondary
        )

        DeviceCodeCard(snapshot.deviceCode, snapshot.machineId, clipboard)

        Spacer(modifier = Modifier.height(20.dp))

        OutlinedTextField(
            value = licenseKey,
            onValueChange = { licenseKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Llave de licencia") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            enabled = !loading
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                loading = true
                statusMessage = "Validando licencia..."
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        licenseManager.activate(licenseKey)
                    }
                    snapshot = result.snapshot ?: licenseManager.getSnapshot()
                    statusMessage = result.message
                    loading = false
                    if (result.success && snapshot.isUsable()) {
                        onLicenseReady()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            Text(if (loading) "Validando..." else "Activar licencia")
        }

        if (snapshot.licenseKey != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    loading = true
                    statusMessage = "Validando licencia..."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            licenseManager.validateStored()
                        }
                        snapshot = result.snapshot ?: licenseManager.getSnapshot()
                        statusMessage = result.message
                        loading = false
                        if (result.success && snapshot.isUsable()) {
                            onLicenseReady()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) {
                Text("Revalidar licencia guardada")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = statusMessage,
            color = if (snapshot.isUsable()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun DeviceCodeCard(deviceCode: String, machineId: String, clipboard: ClipboardManager) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("ID del dispositivo", fontWeight = FontWeight.SemiBold)
            Text(
                text = deviceCode,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Machine ID", fontWeight = FontWeight.SemiBold)
            Text(
                text = machineId,
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Efi-Driver device code", deviceCode))
                }) {
                    Text("Copiar")
                }
            }
        }
    }
}
