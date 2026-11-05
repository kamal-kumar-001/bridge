package com.bridge.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bridge.app.core.model.ConnectionStatus
import com.bridge.app.core.model.FileTransfer
import com.bridge.app.core.model.PeerDevice
import com.bridge.app.ui.components.DeviceCard
import com.bridge.app.ui.components.QuickActionRow
import com.bridge.app.ui.components.TransferProgressCard
import com.bridge.app.ui.theme.AccentGreen

@Composable
fun HomeScreen(
    connectionStatus: ConnectionStatus,
    lastConnectionError: String? = null,
    connectedDevice: PeerDevice?,
    discoveredDevices: List<PeerDevice>,
    activeTransfers: List<FileTransfer>,
    onConnectToDevice: (PeerDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSendFileClick: () -> Unit,
    onSendText: (String) -> Unit,
    onSyncClipboardClick: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSendTextDialog by remember { mutableStateOf(false) }
    var textToSend by remember { mutableStateOf("") }

    if (showSendTextDialog) {
        AlertDialog(
            onDismissRequest = { showSendTextDialog = false },
            title = { Text("Send Text to Desktop") },
            text = {
                OutlinedTextField(
                    value = textToSend,
                    onValueChange = { textToSend = it },
                    label = { Text("Text or URL") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (textToSend.isNotBlank()) {
                            onSendText(textToSend)
                            textToSend = ""
                            showSendTextDialog = false
                        }
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSendTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Status Card
        item {
            ConnectionStatusCard(
                status = connectionStatus,
                connectedDevice = connectedDevice,
                onDisconnect = onDisconnect
            )
        }

        if (lastConnectionError != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Connection failed", fontWeight = FontWeight.Bold)
                        Text(lastConnectionError, style = MaterialTheme.typography.bodyMedium)
                        Text("Send a screenshot of this message. Do not change network security settings.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Quick Actions Row
        item {
            val isConnected = connectionStatus == ConnectionStatus.CONNECTED
            QuickActionRow(
                enabled = isConnected,
                onSendFile = onSendFileClick,
                onSendText = { showSendTextDialog = true },
                onSyncClipboard = onSyncClipboardClick
            )
        }

        // Active Transfers
        if (activeTransfers.isNotEmpty()) {
            item {
                Text(
                    text = "File Transfers",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(activeTransfers, key = { it.id }) { transfer ->
                TransferProgressCard(
                    transfer = transfer,
                    onCancelClick = { onCancelTransfer(transfer.id) }
                )
            }
        }

        // Nearby Discovered Devices
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Nearby Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (discoveredDevices.isEmpty()) {
                    Text(
                        text = "Searching...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        val nonConnectedDiscovered = discoveredDevices.filter { it.id != connectedDevice?.id }
        if (nonConnectedDiscovered.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Looking for Mac, Windows, or Linux PCs on local Wi-Fi or Phone Hotspot...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(nonConnectedDiscovered, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    isConnected = false,
                    onConnectClick = { onConnectToDevice(device) },
                    onDisconnectClick = {}
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    status: ConnectionStatus,
    connectedDevice: PeerDevice?,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status == ConnectionStatus.CONNECTED)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (status == ConnectionStatus.CONNECTED) AccentGreen
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Text(
                    text = if (status == ConnectionStatus.CONNECTED) "Connected" else "Waiting for Connection",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status == ConnectionStatus.CONNECTED)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (status == ConnectionStatus.CONNECTED && connectedDevice != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = connectedDevice.osType.iconEmoji,
                                fontSize = 28.sp
                            )
                            Column {
                                Text(
                                    text = connectedDevice.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${connectedDevice.osType.displayName} • ${connectedDevice.networkMode.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                }
            } else {
                Text(
                    text = "Open Bridge on your Mac, Windows, or Linux desktop. Ensure both devices are on the same Wi-Fi or your Mac is connected to this phone's Hotspot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
