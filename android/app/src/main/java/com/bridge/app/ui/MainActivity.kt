package com.bridge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.bridge.app.service.BridgeForegroundService
import com.bridge.app.service.ServiceController
import com.bridge.app.ui.components.PairingDialog
import com.bridge.app.ui.screens.DevicesScreen
import com.bridge.app.ui.screens.HomeScreen
import com.bridge.app.ui.screens.SettingsScreen
import com.bridge.app.ui.theme.BridgeTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: ServiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ServiceController.getInstance(this)
        BridgeForegroundService.startService(this)

        requestPermissionsIfNeeded()

        setContent {
            BridgeTheme {
                MainAppScreen(controller)
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        if (permissions.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                .launch(permissions.toTypedArray())
        }
    }
}

enum class NavigationTab(val label: String) {
    HOME("Home"),
    DEVICES("Devices"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(controller: ServiceController) {
    var selectedTab by remember { mutableStateOf(NavigationTab.HOME) }

    val lastConnectionError by controller.lastConnectionError.collectAsState()
    val connectionStatus by controller.connectionStatus.collectAsState()
    val connectedDevice by controller.connectedDevice.collectAsState()
    val discoveredDevices by controller.discoveredDevices.collectAsState()
    val activeTransfers by controller.fileTransferManager.activeTransfers.collectAsState()
    val pairedDevices by controller.pairedDeviceRepo.pairedDevices.collectAsState()
    val clipboardEnabled by controller.clipboardManager.enabled.collectAsState()
    val pendingPairing by controller.pairingManager.pendingPairingRequest.collectAsState()

    val pendingFile by controller.fileTransferManager.pendingOffer.collectAsState()
    pendingFile?.let { offer ->
        AlertDialog(
            onDismissRequest = { controller.fileTransferManager.respondToOffer(false) },
            title = { Text("Receive file?") },
            text = { Text("${offer.message.fileName}\n${offer.message.fileSize} bytes\nSaved in Bridge app storage. Removed when the app is uninstalled.") },
            confirmButton = { Button(onClick = { controller.fileTransferManager.respondToOffer(true) }) { Text("Receive") } },
            dismissButton = { OutlinedButton(onClick = { controller.fileTransferManager.respondToOffer(false) }) { Text("Decline") } }
        )
    }

    var deviceName by remember { mutableStateOf(controller.keyStoreManager.deviceName) }

    // File Picker Launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            controller.sendFile(uri)
        }
    }

    // Pairing prompt dialog
    pendingPairing?.let { req ->
        PairingDialog(
            request = req,
            onAccept = { controller.pairingManager.acceptPairing() },
            onReject = { controller.pairingManager.rejectPairing() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.HOME,
                    onClick = { selectedTab = NavigationTab.HOME },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.DEVICES,
                    onClick = { selectedTab = NavigationTab.DEVICES },
                    icon = { Icon(Icons.Default.Devices, contentDescription = "Devices") },
                    label = { Text("Devices") }
                )
                NavigationBarItem(
                    selected = selectedTab == NavigationTab.SETTINGS,
                    onClick = { selectedTab = NavigationTab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            NavigationTab.HOME -> {
                HomeScreen(
                    connectionStatus = connectionStatus,
                    lastConnectionError = lastConnectionError,
                    connectedDevice = connectedDevice,
                    discoveredDevices = discoveredDevices,
                    activeTransfers = activeTransfers,
                    onConnectToDevice = { controller.connectToDevice(it) },
                    onDisconnect = { controller.disconnect() },
                    onSendFileClick = { filePickerLauncher.launch("*/*") },
                    onSendText = { controller.sendText(it) },
                    onSyncClipboardClick = { controller.clipboardManager.syncCurrentClipboardNow() },
                    onCancelTransfer = { controller.fileTransferManager.cancelTransfer(it) },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            NavigationTab.DEVICES -> {
                DevicesScreen(
                    pairedDevices = pairedDevices,
                    onRevokeDevice = { controller.revokeDevice(it) },
                    modifier = Modifier.padding(paddingValues)
                )
            }
            NavigationTab.SETTINGS -> {
                SettingsScreen(
                    deviceName = deviceName,
                    isClipboardSyncEnabled = clipboardEnabled,
                    onClipboardSyncChanged = { controller.clipboardManager.setEnabled(it) },
                    onDeviceNameChanged = {
                        controller.keyStoreManager.deviceName = it
                        deviceName = it
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}
