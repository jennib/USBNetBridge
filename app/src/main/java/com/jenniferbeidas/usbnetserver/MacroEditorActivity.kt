package com.jenniferbeidas.usbnetserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MacroEditorActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Scaffold(
                topBar = { TopAppBar(title = { Text("Edit Macros") }) }
            ) {
                MacroEditorScreen(modifier = Modifier.padding(it))
            }
        }
    }

    @Composable
    fun MacroEditorScreen(modifier: Modifier = Modifier) {
        val prefs = remember { getSharedPreferences("macros", MODE_PRIVATE) }
        var macros by remember { mutableStateOf(prefs.getStringSet("macros", emptySet())!!.toMutableList()) }

        Column(modifier = modifier.padding(16.dp)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(macros) { macroString ->
                    val parts = macroString.split("|")
                    var name by remember { mutableStateOf(parts[0]) }
                    var command by remember { mutableStateOf(parts[1]) }

                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("Command") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val updatedMacros = macros.toMutableList()
                            val index = updatedMacros.indexOf(macroString)
                            updatedMacros[index] = "$name|$command"
                            macros = updatedMacros
                        }) { Text("Update") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val updatedMacros = macros.toMutableList()
                            updatedMacros.remove(macroString)
                            macros = updatedMacros
                        }) { Text("Delete") }
                    }
                }
            }

            var newName by remember { mutableStateOf("") }
            var newCommand by remember { mutableStateOf("") }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = newCommand,
                    onValueChange = { newCommand = it },
                    label = { Text("Command") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    val newMacro = "$newName|$newCommand"
                    val updatedMacros = macros.toMutableList()
                    updatedMacros.add(newMacro)
                    macros = updatedMacros
                    newName = ""
                    newCommand = ""
                }) { Text("Add") }
            }
            
            Button(
                onClick = { 
                    prefs.edit().putStringSet("macros", macros.toSet()).apply()
                    finish()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                Text("Save & Close")
            }
        }
    }
}
