package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.WallpaperViewModel
import com.example.ui.components.DashboardBadge
import com.example.ui.components.DashboardBadgeTone
import com.example.ui.components.DashboardMetric
import com.example.ui.components.EditorSwitchRow
import com.example.ui.components.SettingDashboardCard
import com.example.ui.components.SettingEditorDialog

private fun getBackupFolderDisplayPath(folderUri: String): String {
    val uri = Uri.parse(folderUri)
    val documentId = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: IllegalArgumentException) {
        return folderUri
    }

    if (uri.authority == "com.android.externalstorage.documents") {
        val separator = documentId.indexOf(':')
        if (separator >= 0) {
            val volume = documentId.substring(0, separator)
            val relativePath = documentId.substring(separator + 1).trim('/')
            val storageRoot = if (volume == "primary") {
                "/storage/emulated/0"
            } else {
                "/storage/$volume"
            }
            return if (relativePath.isEmpty()) storageRoot else "$storageRoot/$relativePath"
        }
    }

    return "${uri.authority}/$documentId"
}

@Composable
fun BackupScreen(
    viewModel: WallpaperViewModel
) {
    val context = LocalContext.current
    val appState by viewModel.appState.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var draftFolderPath by remember(appState.backupFolderPath) {
        mutableStateOf(appState.backupFolderPath)
    }
    var draftAutoSync by remember(appState.autoBackupEnabled) {
        mutableStateOf(appState.autoBackupEnabled)
    }
    var pendingManualExport by remember { mutableStateOf(false) }
    var selectingForEditor by remember { mutableStateOf(false) }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (_: Exception) {
                null
            }
            if (input != null) {
                viewModel.importBackup(input) { success ->
                    val msg = if (success) {
                        context.getString(R.string.toast_backup_restored)
                    } else {
                        context.getString(R.string.toast_backup_restore_failed)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_open_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val uriString = treeUri.toString()
                if (selectingForEditor) {
                    draftFolderPath = uriString
                } else {
                    viewModel.setBackupFolder(uriString)
                    if (pendingManualExport) {
                        viewModel.exportBackupToFolder(uriString) { success ->
                            val msg = if (success) {
                                context.getString(R.string.toast_backup_saved)
                            } else {
                                context.getString(R.string.toast_backup_export_failed)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_backup_folder_selected),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_folder_access_failed),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                pendingManualExport = false
                selectingForEditor = false
            }
        } else {
            pendingManualExport = false
            selectingForEditor = false
        }
    }

    fun exportToSelectedFolder() {
        val folderUri = appState.backupFolderPath
        if (folderUri.isNullOrEmpty()) {
            selectingForEditor = false
            pendingManualExport = true
            openFolderLauncher.launch(null)
            return
        }
        viewModel.exportBackupToFolder(folderUri) { success ->
            val msg = if (success) {
                context.getString(R.string.toast_backup_saved)
            } else {
                context.getString(R.string.toast_backup_export_failed)
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val title = stringResource(R.string.backup_title)
    val folderDisplay = appState.backupFolderPath?.takeIf { it.isNotEmpty() }?.let {
        "${getBackupFolderDisplayPath(it).trimEnd('/')}/"
    } ?: stringResource(R.string.backup_no_folder)

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.Archive,
        cardTestTag = "backup_dashboard_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = {
            draftFolderPath = appState.backupFolderPath
            draftAutoSync = appState.autoBackupEnabled
            showEditor = true
        },
        badges = listOf(
            DashboardBadge(
                text = if (appState.autoBackupEnabled) {
                    stringResource(R.string.backup_badge_auto)
                } else {
                    stringResource(R.string.backup_badge_manual)
                },
                tone = if (appState.autoBackupEnabled) {
                    DashboardBadgeTone.PRIMARY
                } else {
                    DashboardBadgeTone.NEUTRAL
                }
            )
        ),
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.backup_folder_label),
                value = folderDisplay
            ),
            DashboardMetric(
                label = stringResource(R.string.backup_auto_sync_label),
                value = if (appState.autoBackupEnabled) {
                    stringResource(R.string.state_on)
                } else {
                    stringResource(R.string.state_off)
                }
            )
        ),
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { exportToSelectedFolder() },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("export_backup_button")
                ) {
                    Icon(
                        Icons.Default.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.backup_export))
                }

                OutlinedButton(
                    onClick = {
                        importFileLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream")
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("import_backup_button")
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.backup_restore))
                }
            }
        }
    )

    if (showEditor) {
        SettingEditorDialog(
            title = title,
            icon = Icons.Default.Archive,
            onDismiss = { showEditor = false },
            onConfirm = {
                viewModel.setBackupFolder(draftFolderPath)
                viewModel.setAutoBackupEnabled(draftAutoSync && !draftFolderPath.isNullOrEmpty())
                showEditor = false
            },
            dialogTestTag = "backup_editor_dialog"
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!draftFolderPath.isNullOrEmpty()) {
                                    stringResource(R.string.backup_folder_label)
                                } else {
                                    stringResource(R.string.backup_no_folder)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = draftFolderPath?.takeIf { it.isNotEmpty() }?.let {
                                    "${getBackupFolderDisplayPath(it).trimEnd('/')}/"
                                } ?: stringResource(R.string.backup_tap_select_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = true
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            selectingForEditor = true
                            pendingManualExport = false
                            openFolderLauncher.launch(null)
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("select_folder_button")
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.backup_select_folder))
                    }
                }
            }

            EditorSwitchRow(
                title = stringResource(R.string.backup_auto_sync_label),
                description = stringResource(R.string.backup_auto_sync_desc),
                checked = draftAutoSync,
                onCheckedChange = { enabled ->
                    if (enabled && draftFolderPath.isNullOrEmpty()) {
                        selectingForEditor = true
                        pendingManualExport = false
                        openFolderLauncher.launch(null)
                    } else {
                        draftAutoSync = enabled
                    }
                },
                switchTestTag = "toggle_auto_backup"
            )

            Text(
                text = stringResource(R.string.backup_policy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
