package com.bodyslide.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.skyrimsuite.ui.SuiteAlertDialog
import com.skyrimsuite.ui.SuiteBarAction
import com.skyrimsuite.ui.SuiteCard
import com.skyrimsuite.ui.SuiteChip
import com.skyrimsuite.ui.SuiteChipTone
import com.skyrimsuite.ui.SuiteDetailRow
import com.skyrimsuite.ui.SuiteDropdown
import com.skyrimsuite.ui.SuiteEmptyState
import com.skyrimsuite.ui.SuiteLogCard
import com.skyrimsuite.ui.SuiteLogDialog
import com.skyrimsuite.ui.SuitePathRow
import com.skyrimsuite.ui.SuitePermissionNotice
import com.skyrimsuite.ui.SuiteScreen
import com.skyrimsuite.ui.SuiteSettingsSheet
import com.skyrimsuite.ui.SuiteTextField
import com.skyrimsuite.ui.SuiteTextInputDialog
import com.skyrimsuite.ui.SuiteToggleRow

/** Read-only projection of one slider's applied value. */
data class PresetValueRow(val label: String, val value: String)

/** Simple themed confirm dialog requested by the activity. */
data class ConfirmDialogUi(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val dismissLabel: String? = null
)

/** Conflict resolver input: one output file and the outfits that want it. */
data class ConflictGroupUi(
    val outputKey: String,
    val members: List<String>
)

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
    onViewLogs: () -> Unit,
    confirmDialog: ConfirmDialogUi?,
    onConfirmDialogConfirm: () -> Unit,
    onConfirmDialogDismiss: () -> Unit,
    logDialogText: String?,
    onLogDialogDismiss: () -> Unit,
    pathPromptTitle: String?,
    pathPromptInitial: String,
    onPathPromptConfirm: (String) -> Unit,
    onPathPromptDismiss: () -> Unit,
    conflictGroups: List<ConflictGroupUi>?,
    onConflictConfirm: (Map<String, String>) -> Unit,
    onConflictContinue: () -> Unit,
    onConflictCancel: () -> Unit
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

    confirmDialog?.let { dialog ->
        SuiteAlertDialog(
            title = dialog.title,
            body = dialog.body,
            confirmLabel = dialog.confirmLabel,
            dismissLabel = dialog.dismissLabel,
            onConfirm = onConfirmDialogConfirm,
            onDismiss = onConfirmDialogDismiss
        )
    }

    logDialogText?.let { log ->
        SuiteLogDialog(
            title = stringResource(R.string.dialog_logs_title),
            log = log,
            closeLabel = stringResource(R.string.dialog_close),
            onDismiss = onLogDialogDismiss,
            emptyText = stringResource(R.string.logs_empty)
        )
    }

    if (pathPromptTitle != null) {
        SuiteTextInputDialog(
            title = pathPromptTitle,
            body = "Could not resolve a real filesystem path from the picker. Enter the absolute path.",
            initialValue = pathPromptInitial,
            confirmLabel = stringResource(R.string.dialog_ok),
            dismissLabel = stringResource(R.string.dialog_cancel),
            onConfirm = onPathPromptConfirm,
            onDismiss = onPathPromptDismiss
        )
    }

    conflictGroups?.let { groups ->
        SuiteConflictDialog(
            groups = groups,
            onConfirm = onConflictConfirm,
            onContinue = onConflictContinue,
            onCancel = onConflictCancel
        )
    }
}

@Composable
private fun SuiteConflictDialog(
    groups: List<ConflictGroupUi>,
    onConfirm: (Map<String, String>) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    var choices by remember(groups) {
        mutableStateOf(groups.associate { it.outputKey to it.members.first() })
    }
    var chooser by rememberSaveable { mutableStateOf(false) }
    var pickerKey by remember { mutableStateOf<String?>(null) }

    if (groups.size > 25 && !chooser) {
        // Hundreds of radio groups do not fit a dialog, so offer the two
        // practical choices first and only build the picker on request.
        SuiteAlertDialog(
            title = stringResource(R.string.conflict_dialog_title),
            body = stringResource(
                R.string.conflict_many_message,
                groups.size,
                groups.sumOf { it.members.size }
            ),
            confirmLabel = stringResource(R.string.btn_continue),
            neutralLabel = stringResource(R.string.conflict_btn_choose),
            dismissLabel = stringResource(R.string.dialog_cancel),
            onConfirm = onContinue,
            onNeutral = { chooser = true },
            onDismiss = onCancel
        )
    } else if (!chooser) {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.conflict_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.conflict_dialog_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    groups.forEach { group ->
                        Text(
                            text = group.outputKey,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Text(
                            text = stringResource(R.string.conflict_winner_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        group.members.forEach { member ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        choices = choices + (group.outputKey to member)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = choices[group.outputKey] == member,
                                    onClick = { choices = choices + (group.outputKey to member) }
                                )
                                Text(member, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(choices) }) {
                    Text(stringResource(R.string.conflict_btn_build_chosen))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onContinue) {
                        Text(stringResource(R.string.btn_continue))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    text = stringResource(R.string.conflict_btn_choose),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.conflict_chooser_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(groups, key = { it.outputKey }) { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pickerKey = group.outputKey }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = group.outputKey,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = choices[group.outputKey] ?: group.members.first(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(choices) }) {
                    Text(stringResource(R.string.conflict_btn_build_chosen))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onContinue) {
                        Text(stringResource(R.string.btn_continue))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            }
        )
    }

    pickerKey?.let { key ->
        val group = groups.firstOrNull { it.outputKey == key }
        if (group != null) {
            AlertDialog(
                onDismissRequest = { pickerKey = null },
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = {
                    Text(text = key, style = MaterialTheme.typography.titleSmall)
                },
                text = {
                    Column {
                        group.members.forEach { member ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        choices = choices + (key to member)
                                        pickerKey = null
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = choices[key] == member,
                                    onClick = {
                                        choices = choices + (key to member)
                                        pickerKey = null
                                    }
                                )
                                Text(member, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pickerKey = null }) {
                        Text(stringResource(R.string.dialog_ok))
                    }
                }
            )
        }
    }
}
