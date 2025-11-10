package com.jenniferbeidas.usbnetserver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Privacy Policy", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                Text(
                    text = "This app, USB Net Bridge, does not collect, store, or share any personal data. " +
                            "All data from your camera and USB serial device is streamed directly to your local network and is not sent to any external servers. " +
                            "The app requires camera and USB permissions to function, but this data is only used for the purpose of streaming and is not stored or analyzed.",
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}