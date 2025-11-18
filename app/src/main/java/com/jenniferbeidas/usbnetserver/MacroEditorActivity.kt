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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MacroEditorActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("macros", Context.MODE_PRIVATE)
        val gson = Gson()

        setContent {
            MaterialTheme {
                MacroEditorScreen(prefs = prefs, gson = gson, onSave = {
                    setResult(Activity.RESULT_OK)
                    finish()
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorScreen(prefs: SharedPreferences, gson: Gson, onSave: () -> Unit) {
    var macros by remember {
        val macrosJson = prefs.getString("macros", null)
        val type = object : TypeToken<List<Macro>>() {}.type
        val loadedMacros = if (macrosJson != null) {
            gson.fromJson<List<Macro>>(macrosJson, type)
        } else {
            emptyList()
        }
        mutableStateOf(loadedMacros.toMutableList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Macros") },
                actions = {
                    IconButton(onClick = {
                        val macrosJson = gson.toJson(macros)
                        prefs.edit().putString("macros", macrosJson).apply()
                        onSave()
                    }) {
                        Icon(Icons.Default.Done, contentDescription = "Save Macros")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { macros = (macros + Macro("New Macro", "")).toMutableList() }) {
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
                MacroEditCard(
                    macro = macro,
                    onDelete = {
                        macros = macros.filterIndexed { i, _ -> i != index }.toMutableList()
                    },
                    onUpdate = { updatedMacro ->
                        macros = macros.toMutableList().also { it[index] = updatedMacro }
                    },
                    onMoveUp = {
                        if (index > 0) {
                            val newMacros = macros.toMutableList()
                            val temp = newMacros[index]
                            newMacros[index] = newMacros[index - 1]
                            newMacros[index - 1] = temp
                            macros = newMacros
                        }
                    },
                    onMoveDown = {
                        if (index < macros.size - 1) {
                            val newMacros = macros.toMutableList()
                            val temp = newMacros[index]
                            newMacros[index] = newMacros[index + 1]
                            newMacros[index + 1] = temp
                            macros = newMacros
                        }
                    }
                )
            }
            item {
                Spacer(modifier = Modifier.height(80.dp)) // Space for the FAB
            }
        }
    }
}

@Composable
fun MacroEditCard(
    macro: Macro,
    onDelete: () -> Unit,
    onUpdate: (Macro) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var name by remember(macro) { mutableStateOf(macro.name) }
    var command by remember(macro) { mutableStateOf(macro.command) }
    var colorHex by remember(macro) { mutableStateOf(macro.colorHex) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                }
                IconButton(onClick = onMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                }
            }
            Column(modifier = Modifier.padding(8.dp).weight(1f)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onUpdate(Macro(it, command, colorHex))
                    },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
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
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Macro")
            }
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
