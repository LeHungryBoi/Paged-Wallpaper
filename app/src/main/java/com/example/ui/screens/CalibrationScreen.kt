package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.WallpaperViewModel
import com.example.ui.components.DashboardMetric
import com.example.ui.components.SettingDashboardCard
import com.example.ui.components.SettingEditorDialog
import java.util.Locale

@Composable
fun CalibrationScreen(
    viewModel: WallpaperViewModel
) {
    val appState by viewModel.appState.collectAsState()
    val calib = appState.calibration
    var showEditor by remember { mutableStateOf(false) }

    val title = stringResource(R.string.calibration_title)
    val anchorsSummary = calib.customOffsets.joinToString(" · ") {
        String.format(Locale.US, "%.2f", it)
    }

    SettingDashboardCard(
        title = title,
        icon = Icons.Default.SwapHoriz,
        cardTestTag = "calibration_dashboard_card",
        editContentDescription = stringResource(R.string.cd_edit_setting, title),
        onEditClick = { showEditor = true },
        metrics = listOf(
            DashboardMetric(
                label = stringResource(R.string.calibration_pages_label),
                value = stringResource(R.string.calibration_pages_chip, calib.pageCount)
            ),
            DashboardMetric(
                label = stringResource(R.string.calibration_home_label),
                value = stringResource(R.string.studio_page_title, calib.homePageIndex + 1)
            ),
            DashboardMetric(
                label = stringResource(R.string.calibration_anchors_label),
                value = anchorsSummary
            )
        )
    )

    if (showEditor) {
        var draftPageCount by remember(calib) { mutableIntStateOf(calib.pageCount) }
        var draftHomeIndex by remember(calib) { mutableIntStateOf(calib.homePageIndex) }
        var draftOffsets by remember(calib) { mutableStateOf(calib.customOffsets) }

        fun uniformOffsetsFor(count: Int): List<Float> =
            (0 until count).map { i ->
                if (count <= 1) 0f else i.toFloat() / (count - 1).toFloat()
            }

        SettingEditorDialog(
            title = title,
            icon = Icons.Default.SwapHoriz,
            onDismiss = { showEditor = false },
            onConfirm = {
                if (draftPageCount != calib.pageCount) {
                    viewModel.setPageCount(draftPageCount)
                }
                if (draftHomeIndex != calib.homePageIndex || draftPageCount != calib.pageCount) {
                    viewModel.setHomePage(draftHomeIndex.coerceIn(0, draftPageCount - 1))
                }
                viewModel.updateCustomOffsets(draftOffsets)
                showEditor = false
            },
            dialogTestTag = "calibration_editor_dialog"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.calibration_page_count_question),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (2..6).forEach { count ->
                        FilterChip(
                            selected = draftPageCount == count,
                            onClick = {
                                draftPageCount = count
                                draftHomeIndex = draftHomeIndex.coerceIn(0, count - 1)
                                draftOffsets = uniformOffsetsFor(count)
                            },
                            label = { Text(stringResource(R.string.calibration_pages_chip, count)) },
                            leadingIcon = if (draftPageCount == count) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag("chip_count_$count")
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Home,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.calibration_home_question),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                Text(
                    text = stringResource(R.string.calibration_home_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (0 until draftPageCount).forEach { idx ->
                        val isHome = draftHomeIndex == idx
                        FilterChip(
                            selected = isHome,
                            onClick = { draftHomeIndex = idx },
                            label = {
                                Text(
                                    if (isHome) {
                                        stringResource(R.string.calibration_page_home_chip, idx + 1)
                                    } else {
                                        stringResource(R.string.studio_page_title, idx + 1)
                                    }
                                )
                            },
                            leadingIcon = if (isHome) {
                                {
                                    Icon(
                                        Icons.Default.Home,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.testTag("chip_home_$idx")
                        )
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
                        text = stringResource(R.string.calibration_anchors_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.calibration_anchors_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { draftOffsets = uniformOffsetsFor(draftPageCount) },
                    modifier = Modifier.testTag("reset_uniform_button")
                ) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.calibration_uniform),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            draftOffsets.forEachIndexed { index, offsetVal ->
                val isHome = index == draftHomeIndex
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isHome) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            }
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.studio_page_title, index + 1),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (isHome) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = stringResource(R.string.studio_home_badge),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = String.format(Locale.US, "%.2f", offsetVal),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = offsetVal,
                        onValueChange = { newVal ->
                            val next = draftOffsets.toMutableList()
                            next[index] = newVal
                            draftOffsets = next
                        },
                        valueRange = 0.0f..1.0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("anchor_slider_$index")
                    )
                }
            }
        }
    }
}
