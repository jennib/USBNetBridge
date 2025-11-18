package com.jenniferbeidas.usbnetserver

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MacroEditorActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("macros", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                MacroEditorScreen(prefs = prefs, onSave = {
                    setResult(Activity.RESULT_OK)
                    finish()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorScreen(prefs: SharedPreferences, onSave: () -> Unit) {
    var macros by remember {
        val macroStrings = prefs.getStringSet("macros", emptySet()) ?: emptySet()
        mutableStateOf(macroStrings.mapNotNull {
            val parts = it.split("|", limit = 3)
            when (parts.size) {
                2 -> Macro(parts[0], parts[1], null)
                3 -> Macro(parts[0], parts[1], parts[2].ifEmpty { null })
                else -> null
            }
        })
    }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Edit Macros") },
                actions = {
                    IconButton(onClick = { 
                        val macroStrings = macros.map { "${it.name}|${it.command}|${it.colorHex.orEmpty()}" }.toSet()
                        prefs.edit().putStringSet("macros", macroStrings).apply()
                        onSave()
                    }) {
                        Icon(Icons.Default.Done, contentDescription = "Save Macros")
                    }
                }
            ) 
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { macros = macros + Macro("New Macro", "") }) {
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
                    macros = macros.filterIndexed { i, _ -> i != index }
                }, onUpdate = { updatedMacro ->
                    macros = macros.toMutableList().also { it[index] = updatedMacro }
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
    var name by remember(macro) { mutableStateOf(macro.name) }
    var command by remember(macro) { mutableStateOf(macro.command) }
    var colorHex by remember(macro) { mutableStateOf(macro.colorHex) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it 
                        onUpdate(Macro(it, command, colorHex))
                    },
                    label = { Text("Name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Macro")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { 
                    command = it
                    onUpdate(Macro(name, it, colorHex))
                },
                label = { Text("Command") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            ColorPicker(selectedColor = colorHex, onColorSelected = {
                colorHex = it
                onUpdate(Macro(name, command, it))
            })
        }
    }
}

@Composable
fun ColorPicker(selectedColor: String?, onColorSelected: (String?) -> Unit) {
    val colors = listOf(
        null, // Default
        "#FF4444", // Red
        "#FFBB33", // Orange
        "#00C851", // Green
        "#33B5E5", // Blue
        "#AA66CC"  // Purple
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEach { colorHex ->
            val color = if(colorHex != null) Color(android.graphics.Color.parseColor(colorHex)) else MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onColorSelected(colorHex) }
                    .border(
                        width = 2.dp, 
                        color = if (selectedColor == colorHex) MaterialTheme.colorScheme.onSurface else Color.Transparent, 
                        shape = CircleShape
                    )
            ) {
                if (selectedColor == colorHex) {
                    Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}