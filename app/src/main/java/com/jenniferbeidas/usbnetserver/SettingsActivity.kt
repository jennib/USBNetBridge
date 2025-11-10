package com.jenniferbeidas.usbnetserver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.felhr.usbserial.UsbSerialInterface

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Scaffold(
                topBar = { TopAppBar(title = { Text("Settings") }) }
            ) {
                SettingsScreen(modifier = Modifier.padding(it), onSave = { finish() })
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier, onSave: () -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("serial_settings", Context.MODE_PRIVATE) }

    val baudRateOptions = listOf("9600", "19200", "38400", "57600", "115200")
    val dataBitsOptions = listOf("7", "8")
    val stopBitsOptions = mapOf("1" to UsbSerialInterface.STOP_BITS_1, "2" to UsbSerialInterface.STOP_BITS_2)
    val parityOptions = mapOf("None" to UsbSerialInterface.PARITY_NONE, "Even" to UsbSerialInterface.PARITY_EVEN, "Odd" to UsbSerialInterface.PARITY_ODD, "Mark" to UsbSerialInterface.PARITY_MARK, "Space" to UsbSerialInterface.PARITY_SPACE)

    var baudRate by remember { mutableStateOf(sharedPreferences.getInt("baud_rate", 115200).toString()) }
    var dataBits by remember { mutableStateOf(sharedPreferences.getInt("data_bits", 8).toString()) }
    var stopBits by remember { mutableStateOf(stopBitsOptions.entries.find { it.value == sharedPreferences.getInt("stop_bits", UsbSerialInterface.STOP_BITS_1) }?.key ?: "1") }
    var parity by remember { mutableStateOf(parityOptions.entries.find { it.value == sharedPreferences.getInt("parity", UsbSerialInterface.PARITY_NONE) }?.key ?: "None") }
    var startOnBoot by remember { mutableStateOf(sharedPreferences.getBoolean("start_on_boot", false)) }
    var keepScreenOn by remember { mutableStateOf(sharedPreferences.getBoolean("keep_screen_on", false)) }

    Column(modifier = modifier.padding(16.dp)) {
        Text("Serial Port Settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        SettingsDropdown(label = "Baud Rate", selectedOption = baudRate, options = baudRateOptions) { baudRate = it }
        SettingsDropdown(label = "Data Bits", selectedOption = dataBits, options = dataBitsOptions) { dataBits = it }
        SettingsDropdown(label = "Stop Bits", selectedOption = stopBits, options = stopBitsOptions.keys.toList()) { stopBits = it }
        SettingsDropdown(label = "Parity", selectedOption = parity, options = parityOptions.keys.toList()) { parity = it }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text("App Settings", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text(text = "Start on Boot")
            Switch(checked = startOnBoot, onCheckedChange = { startOnBoot = it })
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text(text = "Keep Screen On")
            Switch(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it })
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:${context.packageName}".toUri()
            context.startActivity(intent)
        }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
            Text("Open App System Settings")
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = { context.startActivity(Intent(context, PrivacyPolicyActivity::class.java)) }) {
            Text("Privacy Policy")
        }

        Button(onClick = {
            sharedPreferences.edit { clear() }
            onSave()
        }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red), modifier = Modifier.padding(top = 8.dp)) {
            Text("Reset to Default")
        }

        Button(onClick = {
            sharedPreferences.edit {
                putInt("baud_rate", baudRate.toInt())
                putInt("data_bits", dataBits.toInt())
                putInt("stop_bits", stopBitsOptions.getValue(stopBits))
                putInt("parity", parityOptions.getValue(parity))
                putBoolean("start_on_boot", startOnBoot)
                putBoolean("keep_screen_on", keepScreenOn)
            }
            onSave()
        }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Save and Close")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(label: String, selectedOption: String, options: List<String>, onOptionSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            TextField(
                value = selectedOption,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        onOptionSelected(option)
                        expanded = false
                    })
                }
            }
        }
    }
}