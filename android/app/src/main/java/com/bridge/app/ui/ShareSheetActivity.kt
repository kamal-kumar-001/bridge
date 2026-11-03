package com.bridge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bridge.app.core.model.ConnectionStatus
import com.bridge.app.service.ServiceController
import com.bridge.app.ui.theme.BridgeTheme

class ShareSheetActivity : ComponentActivity() {

    private lateinit var controller: ServiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ServiceController.getInstance(this)

        handleShareIntent()

        setContent {
            BridgeTheme {
                ShareConfirmationScreen(
                    controller = controller,
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun handleShareIntent() {
        val intent = intent ?: return
        val action = intent.action
        val type = intent.type ?: "*/*"

        val activeSession = controller.activeSession.value
        if (activeSession == null || !activeSession.isConnected) {
            Toast.makeText(this, "Bridge: Not connected to computer. Open Bridge first.", Toast.LENGTH_LONG).show()
            return
        }

        when (action) {
            Intent.ACTION_SEND -> {
                if (type.startsWith("text/plain")) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        controller.sendText(sharedText)
                        Toast.makeText(this, "Sent text to ${activeSession.peerDevice?.name}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } else {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }

                    if (uri != null) {
                        controller.sendFile(uri, type)
                        Toast.makeText(this, "Sending to ${activeSession.peerDevice?.name}...", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (!uris.isNullOrEmpty()) {
                    for (u in uris) {
                        controller.sendFile(u, type)
                    }
                    Toast.makeText(this, "Sending ${uris.size} files to ${activeSession.peerDevice?.name}...", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}

@Composable
fun ShareConfirmationScreen(
    controller: ServiceController,
    onDismiss: () -> Unit
) {
    val status by controller.connectionStatus.collectAsState()
    val connectedDevice by controller.connectedDevice.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Bridge Share", style = MaterialTheme.typography.titleLarge)

                    if (status == ConnectionStatus.CONNECTED && connectedDevice != null) {
                        Text(
                            text = "${connectedDevice!!.osType.iconEmoji} Connected to ${connectedDevice!!.name}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    } else {
                        Text(
                            text = "No computer connected. Please connect to your Mac, Windows, or Linux PC in Bridge.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
