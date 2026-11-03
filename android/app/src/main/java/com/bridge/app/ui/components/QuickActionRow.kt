package com.bridge.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun QuickActionRow(
    enabled: Boolean,
    onSendFile: () -> Unit,
    onSendText: () -> Unit,
    onSyncClipboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickActionButton(
            label = "Send File",
            icon = Icons.Default.AttachFile,
            enabled = enabled,
            onClick = onSendFile,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            label = "Send Text",
            icon = Icons.Default.Send,
            enabled = enabled,
            onClick = onSendText,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            label = "Sync Clip",
            icon = Icons.Default.Assignment,
            enabled = enabled,
            onClick = onSyncClipboard,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
