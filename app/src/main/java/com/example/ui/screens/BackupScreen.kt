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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.WallpaperViewModel

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
    var pendingManualExport by remember { mutableStateOf(false) }
    var pendingAutoSyncEnable by remember { mutableStateOf(false) }

    // SAF Open Document to import and restore
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
                    val msg = if (success) "Configuration & wallpapers successfully restored!" else "Restore failed"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(context, "Could not open the selected backup.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Choose one destination reused by manual export and optional automatic sync.
    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.setBackupFolder(treeUri.toString())
                if (pendingAutoSyncEnable) viewModel.setAutoBackupEnabled(true)
                if (pendingManualExport) {
                    viewModel.exportBackupToFolder(treeUri.toString()) { success ->
                        val msg = if (success) {
                            "Backup saved as paperpages_backup.zip in the selected folder."
                        } else {
                            "Export failed. Check that the selected folder is writable."
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "Backup folder selected.", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, "Could not access the selected folder.", Toast.LENGTH_LONG).show()
            } finally {
                pendingAutoSyncEnable = false
                pendingManualExport = false
            }
        } else {
            pendingAutoSyncEnable = false
            pendingManualExport = false
        }
    }

    fun exportToSelectedFolder() {
        val folderUri = appState.backupFolderPath
        if (folderUri.isNullOrEmpty()) {
            pendingManualExport = true
            openFolderLauncher.launch(null)
            return
        }
        viewModel.exportBackupToFolder(folderUri) { success ->
            val msg = if (success) {
                "Backup saved as paperpages_backup.zip in the selected folder."
            } else {
                "Export failed. Check that the selected folder is writable."
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Explainer Card: Single-Version Philosophy
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.CleaningServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Clean Single-File Backup Policy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "PaperPages stores your settings, offsets, original photos, and page images in paperpages_backup.zip. Choose a destination folder and later exports reuse that same file instead of creating numbered copies.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Section: Manual Backup & Restore
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_backup_card"),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Manual Export & Restore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Save your entire setup to a single file, or restore from an existing backup on any device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            exportToSelectedFolder()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("export_backup_button")
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Backup")
                    }

                    OutlinedButton(
                        onClick = {
                            importFileLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import_backup_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore")
                    }
                }
            }
        }

        // Section: Backup Settings
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("backup_settings_card"),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Backup settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Choose one folder for manual backups and optional automatic sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (!appState.backupFolderPath.isNullOrEmpty()) "Backup folder" else "No folder selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = appState.backupFolderPath?.takeIf { it.isNotEmpty() }?.let {
                                        "Path: ${getBackupFolderDisplayPath(it).trimEnd('/')}/"
                                    } ?: "Tap Select Folder to link SAF storage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    softWrap = true
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                pendingAutoSyncEnable = false
                                pendingManualExport = false
                                openFolderLauncher.launch(null)
                            },
                            modifier = Modifier.testTag("select_folder_button")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Select")
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automatic sync",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Update the backup when your setup changes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = appState.autoBackupEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && appState.backupFolderPath.isNullOrEmpty()) {
                                pendingAutoSyncEnable = true
                                pendingManualExport = false
                                openFolderLauncher.launch(null)
                            } else {
                                viewModel.setAutoBackupEnabled(enabled)
                            }
                        },
                        modifier = Modifier.testTag("toggle_auto_backup")
                    )
                }
            }
        }
    }
}
