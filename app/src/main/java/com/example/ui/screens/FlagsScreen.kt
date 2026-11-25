package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.WallpaperRepository
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class FlagFilterTab {
    ALL,
    MODIFIED,
    UNMODIFIED
}

private fun matchesFlagQuery(
    query: String,
    title: String,
    description: String,
    programmaticName: String
): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val q = trimmed.lowercase(Locale.ROOT)
    val slugWithoutHash = programmaticName.removePrefix("#")
    return title.lowercase(Locale.ROOT).contains(q) ||
        description.lowercase(Locale.ROOT).contains(q) ||
        programmaticName.lowercase(Locale.ROOT).contains(q) ||
        slugWithoutHash.lowercase(Locale.ROOT).contains(q)
}

@Composable
fun AdvancedFlagsScreen(
    initialSplitFraction: Float,
    initialFullPreview: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float, Boolean) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val defaultSplit = WallpaperRepository.DEFAULT_EDITOR_PREVIEW_SPLIT_FRACTION
    val defaultFullPreview = WallpaperRepository.DEFAULT_EDITOR_FULL_PREVIEW

    var draftSplitFraction by remember(initialSplitFraction) {
        mutableFloatStateOf(initialSplitFraction)
    }
    var draftFullPreview by remember(initialFullPreview) {
        mutableStateOf(initialFullPreview)
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(FlagFilterTab.ALL) }

    val isSplitModified = abs(draftSplitFraction - defaultSplit) >= 0.005f
    val isPreviewModeModified = draftFullPreview != defaultFullPreview

    val splitTitle = stringResource(R.string.flags_editor_split_title)
    val splitDesc = stringResource(R.string.flags_editor_split_desc)
    val splitProgrammatic = "#editor-default-preview-split"

    val previewModeTitle = stringResource(R.string.flags_editor_preview_mode_title)
    val previewModeDesc = stringResource(R.string.flags_editor_preview_mode_desc)
    val previewModeProgrammatic = "#editor-default-preview-mode"

    fun matchesTab(modified: Boolean): Boolean = when (selectedTab) {
        FlagFilterTab.ALL -> true
        FlagFilterTab.MODIFIED -> modified
        FlagFilterTab.UNMODIFIED -> !modified
    }

    val showSplitItem = matchesTab(isSplitModified) &&
        matchesFlagQuery(searchQuery, splitTitle, splitDesc, splitProgrammatic)
    val showPreviewModeItem = matchesTab(isPreviewModeModified) &&
        matchesFlagQuery(searchQuery, previewModeTitle, previewModeDesc, previewModeProgrammatic)

    BackHandler(onBack = onDismiss)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("flags_editor_dialog"),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                            )
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                    ) {
                        // Compact top chrome://flags toolbar: Back + Search flags + Reset all
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, top = 6.dp, end = 12.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("flags_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back)
                                )
                            }

                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 40.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.flags_search_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                            keyboardActions = KeyboardActions(
                                                onSearch = { focusManager.clearFocus() }
                                            ),
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("flags_search_input")
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clickable { searchQuery = "" }
                                                .testTag("flags_search_clear_button"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = stringResource(R.string.flags_search_clear_cd),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    draftSplitFraction = defaultSplit
                                    draftFullPreview = defaultFullPreview
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 40.dp)
                                    .testTag("flags_reset_default_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.flags_reset_default),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Compact description line
                        Text(
                            text = stringResource(R.string.flags_dialog_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }

                    // All / Modified / Unmodified underline tabs
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Tab(
                            selected = selectedTab == FlagFilterTab.ALL,
                            onClick = { selectedTab = FlagFilterTab.ALL },
                            text = {
                                Text(
                                    text = stringResource(R.string.flags_tab_all),
                                    maxLines = 1,
                                    fontWeight = if (selectedTab == FlagFilterTab.ALL) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.testTag("flags_tab_all")
                        )
                        Tab(
                            selected = selectedTab == FlagFilterTab.MODIFIED,
                            onClick = { selectedTab = FlagFilterTab.MODIFIED },
                            text = {
                                Text(
                                    text = stringResource(R.string.flags_tab_modified),
                                    maxLines = 1,
                                    fontWeight = if (selectedTab == FlagFilterTab.MODIFIED) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.testTag("flags_tab_modified")
                        )
                        Tab(
                            selected = selectedTab == FlagFilterTab.UNMODIFIED,
                            onClick = { selectedTab = FlagFilterTab.UNMODIFIED },
                            text = {
                                Text(
                                    text = stringResource(R.string.flags_tab_unmodified),
                                    maxLines = 1,
                                    fontWeight = if (selectedTab == FlagFilterTab.UNMODIFIED) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            modifier = Modifier.testTag("flags_tab_unmodified")
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 840.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.flags_bottom_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("flags_bottom_hint")
                        )
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("editor_cancel_button")
                        ) {
                            Text(
                                text = stringResource(R.string.action_cancel),
                                maxLines = 1
                            )
                        }
                        Button(
                            onClick = { onConfirm(draftSplitFraction, draftFullPreview) },
                            shape = CircleShape,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("editor_confirm_button")
                        ) {
                            Text(
                                text = stringResource(R.string.flags_apply_btn),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("flags_list_container")
            ) {
                if (!showSplitItem && !showPreviewModeItem) {
                    val emptyRes = when {
                        searchQuery.isNotBlank() -> R.string.flags_empty_search
                        selectedTab == FlagFilterTab.MODIFIED -> R.string.flags_empty_modified
                        selectedTab == FlagFilterTab.UNMODIFIED -> R.string.flags_empty_unmodified
                        else -> R.string.flags_empty_search
                    }
                    Text(
                        text = stringResource(emptyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 28.dp)
                            .testTag("flags_empty_state")
                    )
                }

                if (showSplitItem) {
                    var splitPresetMenuExpanded by remember { mutableStateOf(false) }
                    val defaultSplitPct = (defaultSplit * 100f).roundToInt()
                    val draftSplitPct = (draftSplitFraction * 100f).roundToInt()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("flag_item_split"),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isSplitModified) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = CircleShape
                                                )
                                                .testTag("flag_dot_split")
                                        )
                                    }
                                    Text(
                                        text = splitTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        lineHeight = 18.sp
                                    )
                                }
                                Text(
                                    text = splitDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                                Text(
                                    text = splitProgrammatic,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clickable { splitPresetMenuExpanded = true }
                                        .testTag("flag_default_split_value"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSplitModified) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        border = if (isSplitModified) {
                                            null
                                        } else {
                                            BorderStroke(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(
                                                start = 10.dp,
                                                top = 5.dp,
                                                end = 4.dp,
                                                bottom = 5.dp
                                            ),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.percent_value, draftSplitPct),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isSplitModified) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                }
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                tint = if (isSplitModified) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = splitPresetMenuExpanded,
                                        onDismissRequest = { splitPresetMenuExpanded = false }
                                    ) {
                                        listOf(
                                            0.30f to "30%",
                                            defaultSplit to "${stringResource(R.string.flags_state_default)} ($defaultSplitPct%)",
                                            0.50f to "50%",
                                            0.60f to "60%"
                                        ).forEach { (presetVal, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    draftSplitFraction = presetVal
                                                    splitPresetMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { draftSplitFraction = defaultSplit },
                                    enabled = isSplitModified,
                                    modifier = Modifier.testTag("flag_reset_split_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RestartAlt,
                                        contentDescription = stringResource(
                                            R.string.flags_reset_item_cd,
                                            splitTitle
                                        ),
                                        tint = if (isSplitModified) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Slider(
                            value = draftSplitFraction,
                            onValueChange = { draftSplitFraction = it },
                            valueRange = 0.20f..0.70f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp)
                                .testTag("flag_default_split_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "20%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.flags_split_default_caption,
                                    defaultSplitPct
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "70%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }

                if (showPreviewModeItem) {
                    var modeMenuExpanded by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("flag_item_preview_mode"),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isPreviewModeModified) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = CircleShape
                                            )
                                            .testTag("flag_dot_preview_mode")
                                    )
                                }
                                Text(
                                    text = previewModeTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 18.sp
                                )
                            }
                            Text(
                                text = previewModeDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                            Text(
                                text = previewModeProgrammatic,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    textDecoration = TextDecoration.Underline
                                ),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clickable { modeMenuExpanded = true }
                                    .testTag("flag_preview_mode_selector"),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isPreviewModeModified) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    border = if (isPreviewModeModified) {
                                        null
                                    } else {
                                        BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            start = 10.dp,
                                            top = 5.dp,
                                            end = 4.dp,
                                            bottom = 5.dp
                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isPreviewModeModified) {
                                                stringResource(R.string.flags_preview_mode_full)
                                            } else {
                                                stringResource(R.string.flags_preview_mode_default)
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isPreviewModeModified) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = if (isPreviewModeModified) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = modeMenuExpanded,
                                    onDismissRequest = { modeMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${stringResource(R.string.flags_preview_mode_default)} (${stringResource(R.string.flags_preview_mode_single)})"
                                            )
                                        },
                                        onClick = {
                                            draftFullPreview = false
                                            modeMenuExpanded = false
                                        },
                                        modifier = Modifier.testTag("flag_preview_mode_option_default")
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.flags_preview_mode_full)) },
                                        onClick = {
                                            draftFullPreview = true
                                            modeMenuExpanded = false
                                        },
                                        modifier = Modifier.testTag("flag_preview_mode_option_full")
                                    )
                                }
                            }

                            IconButton(
                                onClick = { draftFullPreview = defaultFullPreview },
                                enabled = isPreviewModeModified,
                                modifier = Modifier.testTag("flag_reset_preview_mode_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = stringResource(
                                        R.string.flags_reset_item_cd,
                                        previewModeTitle
                                    ),
                                    tint = if (isPreviewModeModified) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}
