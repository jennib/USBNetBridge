package com.jenniferbeidas.usbnetserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MacroEditorActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                MacroEditorScreen(onSave = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorScreen(onSave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("macros", MODE_PRIVATE) }
    var macros by remember { mutableStateOf(prefs.getStringSet("macros", emptySet())!!.map { it.split("|").let { p -> Macro(p[0], p[1]) } }.toMutableList()) }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Edit Macros") },
                actions = {
                    IconButton(onClick = { 
                        val macroStrings = macros.map { "${it.name}|${it.command}" }.toSet()
                        prefs.edit().putStringSet("macros", macroStrings).apply()
                        onSave()
                    }) {
                        Icon(Icons.Default.Done, contentDescription = "Save Macros")
                    }
                }
            ) 
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { macros.add(Macro("New Macro", "")) }) {
                Icon(Icons.Default.Add, contentDescription = "Add Macro")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            itemsIndexed(macros) { index, macro ->
                MacroEditCard(macro = macro, onDelete = {
                    macros.removeAt(index)
                }, onUpdate = { updatedMacro ->
                    macros[index] = updatedMacro
                })
            }
            item {
                Spacer(modifier = Modifier.height(80.dp)) // Space for the FAB
            }
        }
    }
}

@Composable
fun MacroEditCard(macro: Macro, onDelete: () -> Unit, onUpdate: (Macro) -> Unit) {
    var name by remember(macro.name) { mutableStateOf(macro.name) }
    var command by remember(macro.command) { mutableStateOf(macro.command) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { 
                    name = it 
                    onUpdate(Macro(it, command))
                },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { 
                    command = it
                    onUpdate(Macro(name, it))
                },
                label = { Text("Command (e.g., \$X\\n or 0x18)") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Macro")
                }
            }
        }
    }
}