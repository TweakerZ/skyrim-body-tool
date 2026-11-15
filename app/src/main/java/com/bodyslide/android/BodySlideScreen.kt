package com.bodyslide.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.skyrimsuite.ui.SuiteBarAction
import com.skyrimsuite.ui.SuiteCard
import com.skyrimsuite.ui.SuiteChip
import com.skyrimsuite.ui.SuiteChipTone
import com.skyrimsuite.ui.SuiteDetailRow
import com.skyrimsuite.ui.SuiteDropdown
import com.skyrimsuite.ui.SuiteEmptyState
import com.skyrimsuite.ui.SuiteLogCard
import com.skyrimsuite.ui.SuitePathRow
import com.skyrimsuite.ui.SuitePermissionNotice
import com.skyrimsuite.ui.SuiteScreen
import com.skyrimsuite.ui.SuiteSettingsSheet
import com.skyrimsuite.ui.SuiteTextField
import com.skyrimsuite.ui.SuiteToggleRow

/** Read-only projection of one slider's applied value. */
data class PresetValueRow(val label: String, val value: String)

@Composable
fun BodySlideScreen(
    projectPath: String?,
    gameDataPath: String?,
    outputPath: String?,
    storageGranted: Boolean,
    statusText: String,
    progressText: String,
    busy: Boolean,
    setCount: Int,
    presetCount: Int,
    groupOptions: List<String>,
    groupIndex: Int,
    onGroupSelect: (Int) -> Unit,
    outfitOptions: List<String>,
    outfitIndex: Int,
    onOutfitSelect: (Int) -> Unit,
    presetOptions: List<String>,
    presetIndex: Int,
    onPresetSelect: (Int) -> Unit,
    presetHeader: String?,
    presetRows: List<PresetValueRow>,
    modName: String,
    onModNameChange: (String) -> Unit,
    packageZip: Boolean,
    onPackageZipChange: (Boolean) -> Unit,
    logText: String,
    logExpanded: Boolean,
    onToggleLog: () -> Unit,
    onPickProject: () -> Unit,
    onPickGameData: () -> Unit,
    onPickOutput: () -> Unit,
    onGrantStorage: () -> Unit,
    onBuild: () -> Unit,
    onBatchBuild: () -> Unit,
    onViewLogs: () -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }

    SuiteScreen(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.app_subtitle),
        chips = listOf(
            SuiteChip(stringResource(R.string.target_skyrim_le_short)),
            SuiteChip(
                label = stringResource(
                    if (storageGranted) R.string.chip_storage_granted
                    else R.string.chip_storage_missing
                ),
                tone = if (storageGranted) SuiteChipTone.Positive else SuiteChipTone.Warning
            ),
            SuiteChip(stringResource(R.string.chip_sets, setCount)),
            SuiteChip(stringResource(R.string.chip_presets, presetCount))
        ),
        onSettings = { showSettings = true },
        primaryAction = SuiteBarAction(
            label = stringResource(R.string.btn_build),
            onClick = onBuild,
            enabled = !busy
        ),
        secondaryActions = listOf(
            SuiteBarAction(
                label = stringResource(R.string.btn_batch_build),
                onClick = onBatchBuild,
                enabled = !busy
            ),
            SuiteBarAction(
                label = stringResource(R.string.btn_view_logs),
                onClick = onViewLogs,
                enabled = !busy
            )
        ),
        busy = busy,
        busyText = progressText
    ) {
        SuitePermissionNotice(
            granted = storageGranted,
            title = stringResource(R.string.storage_permission_title),
            body = stringResource(R.string.storage_missing),
            grantLabel = stringResource(R.string.btn_grant_storage),
            onGrant = onGrantStorage
        )

        SuiteCard {
            Text(
                text = if (busy && progressText.isNotBlank()) progressText else statusText,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    if (projectPath == null || outputPath == null) R.string.summary_empty
                    else R.string.hint_ready
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SuiteCard {
            SuiteDropdown(
                title = stringResource(R.string.label_group_filter),
                options = groupOptions,
                selectedIndex = groupIndex,
                onSelect = onGroupSelect,
                enabled = !busy
            )
            SuiteDropdown(
                title = stringResource(R.string.label_outfit),
                options = outfitOptions,
                selectedIndex = outfitIndex,
                onSelect = onOutfitSelect,
                enabled = !busy
            )
            SuiteDropdown(
                title = stringResource(R.string.label_preset),
                options = presetOptions,
                selectedIndex = presetIndex,
                onSelect = onPresetSelect,
                enabled = !busy
            )
        }

        SuiteCard {
            Text(
                text = stringResource(R.string.label_preset_values),
                style = MaterialTheme.typography.titleMedium
            )
            if (presetRows.isEmpty()) {
                SuiteEmptyState(
                    title = presetHeader ?: stringResource(R.string.preset_values_empty)
                )
            } else {
                if (presetHeader != null) {
                    Text(
                        text = presetHeader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                presetRows.forEach { row ->
                    SuiteDetailRow(label = row.label, value = row.value)
                }
            }
        }

        SuiteCard {
            Text(
                text = stringResource(R.string.label_mod_package),
                style = MaterialTheme.typography.titleMedium
            )
            SuiteTextField(
                value = modName,
                onValueChange = onModNameChange,
                label = stringResource(R.string.hint_mod_name),
                enabled = !busy
            )
            SuiteToggleRow(
                title = stringResource(R.string.label_package_zip),
                checked = packageZip,
                onCheckedChange = onPackageZipChange,
                subtitle = stringResource(R.string.hint_mod_package),
                enabled = !busy
            )
        }

        SuiteLogCard(
            title = stringResource(R.string.dialog_logs_title),
            log = logText,
            expanded = logExpanded,
            onToggle = onToggleLog,
            emptyText = stringResource(R.string.logs_empty)
        )
    }

    SuiteSettingsSheet(
        visible = showSettings,
        onDismiss = { showSettings = false },
        title = stringResource(R.string.settings_title)
    ) {
        SuiteDetailRow(
            label = stringResource(R.string.label_target_game),
            value = stringResource(R.string.target_skyrim_le)
        )
        SuitePathRow(
            title = stringResource(R.string.label_project_path),
            path = projectPath,
            onChange = onPickProject,
            placeholder = stringResource(R.string.msg_not_selected),
            supportingText = stringResource(R.string.hint_project_path)
        )
        SuitePathRow(
            title = stringResource(R.string.label_game_data),
            path = gameDataPath,
            onChange = onPickGameData,
            placeholder = stringResource(R.string.msg_not_selected),
            supportingText = stringResource(R.string.hint_game_data)
        )
        SuitePathRow(
            title = stringResource(R.string.label_output_path),
            path = outputPath,
            onChange = onPickOutput,
            placeholder = stringResource(R.string.msg_not_selected),
            supportingText = stringResource(R.string.hint_output_path)
        )
    }
}
