package com.jenniferbeidas.usbnetserver

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.hoho.android.usbserial.driver.UsbSerialPort

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Pass the Activity context directly to the screen
            SettingsScreen(context = this, onSave = { finish() })
        }
    }
}

@Composable
private fun SettingsScreen(context: Context, onSave: () -> Unit) {
    // 1. Use the standard shared preferences so all parts of the app (receivers, etc.) can access it.
    val sharedPreferences = remember { PreferenceManager.getDefaultSharedPreferences(context) }

    // --- State for "Start on Boot" ---
    var startOnBoot by remember {
        mutableStateOf(sharedPreferences.getBoolean("start_on_boot", true))
    }

    // --- State for Serial Settings (no changes here) ---
    val baudRateOptions = listOf("9600", "19200", "38400", "57600", "115200")
    val dataBitsOptions = listOf("7", "8")
    val stopBitsOptions = mapOf("1" to UsbSerialPort.STOPBITS_1, "1.5" to UsbSerialPort.STOPBITS_1_5, "2" to UsbSerialPort.STOPBITS_2)
    val parityOptions = mapOf("None" to UsbSerialPort.PARITY_NONE, "Even" to UsbSerialPort.PARITY_EVEN, "Odd" to UsbSerialPort.PARITY_ODD, "Mark" to UsbSerialPort.PARITY_MARK, "Space" to UsbSerialPort.PARITY_SPACE)

    var baudRate by remember { mutableStateOf(sharedPreferences.getInt("baud_rate", 115200).toString()) }
    var dataBits by remember { mutableStateOf(sharedPreferences.getInt("data_bits", 8).toString()) }
    var stopBits by remember { mutableStateOf(stopBitsOptions.entries.find { it.value == sharedPreferences.getInt("stop_bits", UsbSerialPort.STOPBITS_1) }?.key ?: "1") }
    var parity by remember { mutableStateOf(parityOptions.entries.find { it.value == sharedPreferences.getInt("parity", UsbSerialPort.PARITY_NONE) }?.key ?: "None") }


    Column(modifier = Modifier.padding(16.dp)) {

        // --- 2. ADDED "Start on Boot" UI ---
        Text("General", modifier = Modifier.padding(bottom = 8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Start on Boot")
            Switch(
                checked = startOnBoot,
                onCheckedChange = { isChecked ->
                    startOnBoot = isChecked
                    // 3. Immediately save the change and update the boot receiver
                    with(sharedPreferences.edit()) {
                        putBoolean("start_on_boot", isChecked)
                        apply()
                    }
                    // THE FIX IS HERE: The typo "we" is removed.
                    BootReceiverToggler.updateComponentState(context, isChecked)
                }
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        // --- END OF ADDED UI ---


        Text("Serial Port Settings", modifier = Modifier.padding(bottom = 16.dp))

        SettingsDropdown(label = "Baud Rate", selectedOption = baudRate, options = baudRateOptions) { baudRate = it }
        SettingsDropdown(label = "Data Bits", selectedOption = dataBits, options = dataBitsOptions) { dataBits = it }
        SettingsDropdown(label = "Stop Bits", selectedOption = stopBits, options = stopBitsOptions.keys.toList()) { stopBits = it }
        SettingsDropdown(label = "Parity", selectedOption = parity, options = parityOptions.keys.toList()) { parity = it }

        Button(onClick = {
            // Save serial settings when the button is clicked
            with(sharedPreferences.edit()) {
                putInt("baud_rate", baudRate.toInt())
                putInt("data_bits", dataBits.toInt())
                putInt("stop_bits", stopBitsOptions.getValue(stopBits))
                putInt("parity", parityOptions.getValue(parity))
                apply()
            }
            onSave()
        }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("Save")
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(text = label)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            TextField(
                value = selectedOption,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor()
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
