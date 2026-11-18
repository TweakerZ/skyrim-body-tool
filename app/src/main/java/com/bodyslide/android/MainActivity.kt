package com.bodyslide.android

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.skyrimsuite.ui.theme.SkyrimSuiteTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private enum class PickTarget { PROJECT, GAME_DATA, OUTPUT }

    private var pendingPick: PickTarget? = null

    private val sessionLog = StringBuilder()

    private var sliderSets: List<SliderSetEntry> = emptyList()
    private var filteredSets: List<SliderSetEntry> = emptyList()
    private var presets: List<PresetEntry> = emptyList()
    private var groups: List<String> = emptyList()
    private var groupMembers: Map<String, Set<String>> = emptyMap()

    // Preset-driven: the selected set's slider definitions (read-only) and the
    // selected preset's values. No manual slider editing.
    private var currentDefs: List<SliderDef> = emptyList()
    private var currentPreset: PresetValues? = null

    /** Parsed slider definitions and output key, keyed by file|set name. */
    private data class SetCacheEntry(val outputKey: String?, val defs: List<SliderDef>)
    private val setCache = mutableMapOf<String, SetCacheEntry>()

    /** Last thing the build was doing, written into a crash report if it dies. */
    @Volatile
    private var buildContext: String = ""

    /* Compose-observed state */
    private var projectPath by mutableStateOf<String?>(null)
    private var gameDataPath by mutableStateOf<String?>(null)
    private var outputPath by mutableStateOf<String?>(null)
    private var storageGranted by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var progressText by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var setCount by mutableStateOf(0)
    private var presetCount by mutableStateOf(0)
    private var groupOptions by mutableStateOf<List<String>>(emptyList())
    private var outfitOptions by mutableStateOf<List<String>>(emptyList())
    private var presetOptions by mutableStateOf<List<String>>(emptyList())
    private var selectedGroup by mutableStateOf(0)
    private var selectedOutfit by mutableStateOf(0)
    private var selectedPreset by mutableStateOf(0)
    private var presetHeader by mutableStateOf<String?>(null)
    private var presetRows by mutableStateOf<List<PresetValueRow>>(emptyList())
    private var modName by mutableStateOf("")
    private var packageZip by mutableStateOf(true)
    private var logText by mutableStateOf("")
    private var logExpanded by mutableStateOf(false)

    private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = pendingPick ?: return@registerForActivityResult
        pendingPick = null
        if (uri != null) handlePickedDirectory(target, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()

        statusText = getString(R.string.status_ready)
        groupOptions = listOf(getString(R.string.filter_all))
        outfitOptions = listOf(getString(R.string.no_items))
        presetOptions = listOf(getString(R.string.preset_none))
        packageZip = prefs.getBoolean(PREF_PACKAGE_ZIP, true)
        modName = prefs.getString(PREF_MOD_NAME, null).orEmpty()

        setContent {
            SkyrimSuiteTheme {
                BodySlideScreen(
                    projectPath = projectPath,
                    gameDataPath = gameDataPath,
                    outputPath = outputPath,
                    storageGranted = storageGranted,
                    statusText = statusText,
                    progressText = progressText,
                    busy = busy,
                    setCount = setCount,
                    presetCount = presetCount,
                    groupOptions = groupOptions,
                    groupIndex = selectedGroup,
                    onGroupSelect = { index ->
                        selectedGroup = index
                        populateOutfitList()
                    },
                    outfitOptions = outfitOptions,
                    outfitIndex = selectedOutfit,
                    onOutfitSelect = { index ->
                        selectedOutfit = index
                        loadSelectedSet()
                    },
                    presetOptions = presetOptions,
                    presetIndex = selectedPreset,
                    onPresetSelect = { index ->
                        selectedPreset = index
                        loadSelectedPreset()
                    },
                    presetHeader = presetHeader,
                    presetRows = presetRows,
                    modName = modName,
                    onModNameChange = { modName = it },
                    packageZip = packageZip,
                    onPackageZipChange = { packageZip = it },
                    logText = logText,
                    logExpanded = logExpanded,
                    onToggleLog = { logExpanded = !logExpanded },
                    onPickProject = { pickDirectory(PickTarget.PROJECT) },
                    onPickGameData = { pickDirectory(PickTarget.GAME_DATA) },
                    onPickOutput = { pickDirectory(PickTarget.OUTPUT) },
                    onGrantStorage = { StoragePaths.requestAllFilesAccess(this) },
                    onBuild = { runBuild(batch = false) },
                    onBatchBuild = { runBuild(batch = true) },
                    onViewLogs = { showLogsDialog() }
                )
            }
        }

        updatePathViews()
        refreshStorageStatus()
        File(filesDir, PROGRESS_FILE).takeIf { it.isFile }?.let { progress ->
            val where = runCatching { progress.readText() }.getOrNull().orEmpty()
            if (where.isNotBlank()) log("Previous build stopped at $where")
            progress.delete()
        }
        File(filesDir, CRASH_FILE).takeIf { it.isFile }?.let { crash ->
            log("Previous session crashed. Saved report:")
            runCatching { crash.readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                sessionLog.appendLine(it.takeLast(4000))
                logText = sessionLog.toString()
            }
            crash.delete()
        }
        log("App started. ${getString(R.string.app_subtitle)}")
        log("Core: ${NativeBridge.coreInfo()}")
        refreshSummary()
        maybeScanProject()
    }

    override fun onResume() {
        super.onResume()
        refreshStorageStatus()
    }

    // -----------------------------------------------------------------------
    // Path selection
    // -----------------------------------------------------------------------

    private fun pickDirectory(target: PickTarget) {
        pendingPick = target
        directoryPicker.launch(null)
    }

    private fun handlePickedDirectory(target: PickTarget, uri: Uri) {
        StoragePaths.persistPermission(this, uri)
        val resolved = StoragePaths.resolve(this, uri)
        if (resolved != null && resolved.isDirectory) {
            applyPath(target, resolved.absolutePath)
        } else {
            promptForManualPath(target)
        }
    }

    private fun promptForManualPath(target: PickTarget) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "/storage/emulated/0/..."
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.btn_select))
            .setMessage("Could not resolve a real filesystem path from the picker. Enter the absolute path.")
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val path = input.text?.toString()?.trim().orEmpty()
                if (path.isNotEmpty()) applyPath(target, path)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun applyPath(target: PickTarget, path: String) {
        var file = File(path)
        if (!file.exists() || !file.isDirectory) {
            toast("Folder does not exist: $path")
            log("Invalid folder: $path")
            return
        }
        // Skyrim LE (static port) convenience: if the user picks the port root, use its Data folder.
        if (target == PickTarget.GAME_DATA && !file.name.equals("Data", ignoreCase = true)) {
            val data = ProjectScanner.childDir(file, "Data")
            if (data != null) {
                file = data
                log("Using Data subfolder: ${file.absolutePath}")
            }
        }
        val key = when (target) {
            PickTarget.PROJECT -> PREF_PROJECT_PATH
            PickTarget.GAME_DATA -> PREF_GAME_DATA_PATH
            PickTarget.OUTPUT -> PREF_OUTPUT_PATH
        }
        prefs.edit().putString(key, file.absolutePath).apply()
        log("${labelFor(target)}: ${file.absolutePath}")
        updatePathViews()
        refreshSummary()
        if (target == PickTarget.PROJECT) maybeScanProject()
    }

    private fun labelFor(target: PickTarget): String = when (target) {
        PickTarget.PROJECT -> getString(R.string.label_project_path)
        PickTarget.GAME_DATA -> getString(R.string.label_game_data)
        PickTarget.OUTPUT -> getString(R.string.label_output_path)
    }

    private fun updatePathViews() {
        projectPath = prefs.getString(PREF_PROJECT_PATH, null)
        gameDataPath = prefs.getString(PREF_GAME_DATA_PATH, null)
        outputPath = prefs.getString(PREF_OUTPUT_PATH, null)
    }

    private fun saveModPrefs() {
        prefs.edit()
            .putString(PREF_MOD_NAME, modName.trim())
            .putBoolean(PREF_PACKAGE_ZIP, packageZip)
            .apply()
    }

    private fun refreshStorageStatus() {
        storageGranted = StoragePaths.hasAllFilesAccess(this)
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    private fun maybeScanProject() {
        outputPath?.let { out ->
            runCatching {
                File(out).listFiles { f -> f.isFile && f.name.endsWith(".zip.part") }
                    ?.forEach { it.delete() }
            }
        }
        val current = projectPath ?: return
        val root = File(current)
        if (!root.isDirectory) return

        statusText = getString(R.string.status_scanning)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ScanResult(
                    sets = ProjectScanner.findSliderSets(root),
                    presets = ProjectScanner.findPresets(root),
                    groups = ProjectScanner.findGroups(root),
                    groupMembers = ProjectScanner.findGroupMembers(root)
                )
            }
            setCache.clear()
            sliderSets = result.sets
            presets = result.presets
            groups = result.groups
            groupMembers = result.groupMembers

            groupOptions = mutableListOf(getString(R.string.filter_all)).apply { addAll(groups) }
            presetOptions = mutableListOf(getString(R.string.preset_none)).apply {
                addAll(presets.map { it.name })
            }
            selectedGroup = 0
            selectedPreset = 0

            log("Scan: ${sliderSets.size} slider sets, ${presets.size} presets, ${groups.size} groups in ${root.absolutePath}")
            statusText = getString(R.string.status_ready)
            refreshSummary()
            populateOutfitList()
        }
    }

    private data class ScanResult(
        val sets: List<SliderSetEntry>,
        val presets: List<PresetEntry>,
        val groups: List<String>,
        val groupMembers: Map<String, Set<String>>
    )

    private fun populateOutfitList() {
        val groupName = if (selectedGroup > 0) groups.getOrNull(selectedGroup - 1) else null
        val members = groupName?.let { groupMembers[it] }

        filteredSets = if (members != null) {
            val filtered = sliderSets.filter { it.name in members }
            if (filtered.isEmpty()) sliderSets else filtered
        } else {
            sliderSets
        }

        outfitOptions = if (filteredSets.isEmpty()) {
            listOf(getString(R.string.no_items))
        } else {
            filteredSets.map { it.name }
        }
        selectedOutfit = 0
        loadSelectedSet()
    }

    // -----------------------------------------------------------------------
    // Selection loading (preset-only, no slider editing)
    // -----------------------------------------------------------------------

    private fun selectedOutfitEntry(): SliderSetEntry? {
        if (filteredSets.isEmpty()) return null
        return filteredSets.getOrNull(selectedOutfit)
    }

    private fun selectedPresetEntry(): PresetEntry? {
        val idx = selectedPreset - 1
        if (idx < 0) return null
        return presets.getOrNull(idx)
    }

    private fun loadSelectedSet() {
        val outfit = selectedOutfitEntry()
        if (outfit == null) {
            currentDefs = emptyList()
            renderPresetValues()
            refreshSummary()
            return
        }

        lifecycleScope.launch {
            val defs = withContext(Dispatchers.IO) { parseDefs(outfit) }
            currentDefs = defs
            renderPresetValues()
            refreshSummary()

            if (modName.isBlank()) modName = outfit.name
            log("Loaded set '${outfit.name}' (${defs.size} sliders) from ${outfit.file.name}")
            if (defs.isEmpty()) {
                log("WARNING: no slider definitions could be read from ${outfit.file.name}; builds will use only set defaults.")
            }

            val nativeInfo = withContext(Dispatchers.IO) {
                NativeBridge.parseSliderSet(outfit.file.absolutePath, outfit.name)
            }
            log("Native parse: $nativeInfo")
        }
    }

    private fun loadSelectedPreset() {
        val preset = selectedPresetEntry()
        if (preset == null) {
            currentPreset = null
            renderPresetValues()
            refreshSummary()
            return
        }
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) { BodySlideParser.parsePreset(preset.file, preset.name) }
            currentPreset = parsed
            renderPresetValues()
            refreshSummary()
            log("Applied preset '${preset.name}' (${(parsed?.big?.size ?: 0) + (parsed?.small?.size ?: 0)} values)")
        }
    }

    /**
     * Composes native build values for a set from its definitions plus a preset.
     *
     * The preset is passed explicitly (not read from [currentPreset]) so a build
     * always uses exactly the preset the user selected, even if the preview parse
     * was still running when Build was tapped.
     */
    private fun composeValues(
        defs: List<SliderDef>,
        preset: PresetValues?
    ): Triple<Array<String>, FloatArray, FloatArray> {
        val names = Array(defs.size) { defs[it].name }
        // Mirrors BodySlide's GetPresetValues: a slider missing from the preset uses
        // its default for that weight, and zaps default to zero so zapped geometry
        // doesn't reappear.
        val big = FloatArray(defs.size) { i ->
            val d = defs[i]
            (preset?.big?.get(d.name) ?: if (d.zap) 0 else d.defaultBig).toFloat()
        }
        val small = FloatArray(defs.size) { i ->
            val d = defs[i]
            (preset?.small?.get(d.name) ?: if (d.zap) 0 else d.defaultSmall).toFloat()
        }
        return Triple(names, big, small)
    }

    private fun renderPresetValues() {
        val outfit = selectedOutfitEntry()

        if (outfit == null || currentDefs.isEmpty()) {
            presetHeader = null
            presetRows = emptyList()
            return
        }

        val presetName = selectedPresetEntry()?.name ?: getString(R.string.preset_none)
        presetHeader = "${outfit.name}  ·  preset: $presetName"

        val values = composeValues(currentDefs, currentPreset)
        val visible = currentDefs.filter { !it.hidden }.ifEmpty { currentDefs }
        presetRows = visible.map { def ->
            val index = currentDefs.indexOf(def)
            val big = values.second.getOrElse(index) { 0f }.toInt()
            val small = values.third.getOrElse(index) { 0f }.toInt()

            val labelText = buildString {
                append(def.name)
                if (def.zap) append("  [zap]")
                if (def.invert) append("  [inv]")
                if (def.clamp) append("  [clamp]")
                if (def.uv) append("  [uv]")
            }
            PresetValueRow(
                label = labelText,
                value = if (big == small) "$big" else "$big / $small"
            )
        }
    }

    private fun refreshSummary() {
        setCount = sliderSets.size
        presetCount = presets.size
    }

    // -----------------------------------------------------------------------
    // Build with conflict resolution + progress
    // -----------------------------------------------------------------------

    private data class ConflictGroup(val outputKey: String, val members: List<SliderSetEntry>)

    private fun runBuild(batch: Boolean) {
        val project = projectPath
        val output = outputPath
        val outfit = selectedOutfitEntry()

        if (project.isNullOrBlank()) { toast("Select the BodySlide project folder first"); return }
        if (output.isNullOrBlank()) { toast("Select a mod output folder first"); return }
        if (!batch && outfit == null) { toast("Select an outfit/slider set first"); return }

        if (busy) return
        saveModPrefs()

        val targets = if (batch) filteredSets else listOfNotNull(outfit)
        if (targets.isEmpty()) { toast("Nothing to build"); return }

        val presetName = selectedPresetEntry()?.name
        if (presetName == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.label_preset)
                .setMessage("No preset selected. The build will use each set's default values. Continue?")
                .setPositiveButton(R.string.btn_continue) { _, _ -> prepareBuild(targets) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        } else {
            prepareBuild(targets)
        }
    }

    private fun prepareBuild(targets: List<SliderSetEntry>) {
        statusText = getString(R.string.status_building)
        log(if (targets.size > 1) "Batch build requested (${targets.size} sets)" else "Build requested: ${targets.first().name}")
        updateBusy(true)
        showProgress(0, targets.size, getString(R.string.progress_preparing))

        lifecycleScope.launch {
            try {
                // Parsing every target is I/O + XML work; keep it off the UI
                // thread or a 400+ set batch trips the input-dispatch ANR.
                val conflicts = withContext(Dispatchers.IO) { findConflicts(targets) }
                updateBusy(false)
                if (conflicts.isEmpty()) {
                    log(getString(R.string.conflict_summary_free, targets.size))
                    startBuild(targets, emptyMap())
                } else {
                    statusText = getString(R.string.status_ready)
                    log(getString(R.string.conflict_summary, conflicts.size, targets.size - countLosers(conflicts)))
                    val preview = conflicts.take(20).joinToString("; ") { g ->
                        "${g.outputKey} <- ${g.members.joinToString("/") { it.name }}"
                    } + if (conflicts.size > 20) "; … (${conflicts.size} total)" else ""
                    log("Output conflicts: $preview")
                    showConflictDialog(conflicts) { choices -> startBuild(targets, choices) }
                }
            } catch (t: Throwable) {
                updateBusy(false)
                statusText = getString(R.string.status_failed)
                log("Conflict check FAILED: ${t.message}")
                toast("Conflict check failed: ${t.message}")
            }
        }
    }

    private fun startBuild(targets: List<SliderSetEntry>, choices: Map<String, String>) {
        val project = projectPath!!
        val output = outputPath!!
        val zipWanted = packageZip
        val dataRoot = dataRootFor(project)
        val presetEntry = selectedPresetEntry()

        updateBusy(true)
        showProgress(0, targets.size, getString(R.string.progress_preparing))

        lifecycleScope.launch {
            try {
                // Resolving conflicts means parsing every target's slider set.
                // Doing that on the UI thread blocked input for long enough to
                // ANR on 400+ set batches.
                val resolved = withContext(Dispatchers.IO) { applyConflictChoices(targets, choices) }
                if (resolved.isEmpty()) {
                    toast("Nothing to build after conflict resolution")
                    return@launch
                }

                val buildModName = modName.trim().ifBlank { resolved.first().name }
                val preview = if (resolved.size <= 20) {
                    resolved.joinToString(", ") { it.name }
                } else {
                    resolved.take(20).joinToString(", ") { it.name } + ", … (${resolved.size} total)"
                }

                log("Data root (ShapeData): $dataRoot")
                log("Mod output: $output")
                log("Mod name: $buildModName  |  Package as zip: $zipWanted")
                log("Preset: ${presetEntry?.name ?: "(none — using set defaults)"}")
                log("Building ${resolved.size} set(s): $preview")

                showProgress(0, resolved.size, getString(R.string.progress_preparing))

                val summary = withContext(Dispatchers.IO) {
                    // Parse the selected preset here, on the build thread, so the
                    // values used for every target are guaranteed to be loaded.
                    val preset = presetEntry?.let { BodySlideParser.parsePreset(it.file, it.name) }
                    buildAndPackage(
                        targets = resolved,
                        dataRoot = dataRoot,
                        outputDir = output,
                        modName = buildModName,
                        packageZip = zipWanted,
                        preset = preset,
                        presetName = presetEntry?.name,
                        onProgress = { done, total, name -> runOnUiThread { showProgress(done, total, name) } }
                    )
                }
                statusText = getString(R.string.status_done)
                log("RESULT:\n$summary")
                toast("Build complete")
            } catch (t: Throwable) {
                statusText = getString(R.string.status_failed)
                log("Build FAILED: ${t.message}")
                toast("Build failed: ${t.message}")
            } finally {
                updateBusy(false)
                logExpanded = true
                refreshSummary()
            }
        }
    }

    private fun countLosers(conflicts: List<ConflictGroup>): Int =
        conflicts.sumOf { it.members.size - 1 }

    /** Sets that write the same output file are grouped so the user can pick a winner. */
    private fun findConflicts(targets: List<SliderSetEntry>): List<ConflictGroup> {
        val byKey = linkedMapOf<String, MutableList<SliderSetEntry>>()
        for (t in targets) {
            val key = outputKeyFor(t) ?: continue
            byKey.getOrPut(key) { mutableListOf() } += t
        }
        return byKey.entries
            .filter { it.value.size > 1 }
            .map { ConflictGroup(it.key, it.value) }
    }

    private fun applyConflictChoices(targets: List<SliderSetEntry>, choices: Map<String, String>): List<SliderSetEntry> {
        val byKey = linkedMapOf<String, MutableList<SliderSetEntry>>()
        for (t in targets) {
            val key = outputKeyFor(t) ?: t.name
            byKey.getOrPut(key) { mutableListOf() } += t
        }
        val result = mutableListOf<SliderSetEntry>()
        for ((key, members) in byKey) {
            if (members.size == 1) {
                result += members[0]
            } else {
                val chosen = choices[key]
                result += members.firstOrNull { it.name == chosen } ?: members[0]
            }
        }
        return result
    }

    private fun outputKeyFor(entry: SliderSetEntry): String? = cachedSet(entry).outputKey

    /**
     * Scroll host for dialog content. AlertDialog re-measures custom views with
     * WRAP_CONTENT, so a long list would push the dialog buttons off-screen.
     */
    private fun dialogScrollView(): MaxHeightScrollView {
        val screenHeight = resources.displayMetrics.heightPixels
        return MaxHeightScrollView(this).apply {
            maxHeightPx = (screenHeight * 0.55f).toInt()
            isFillViewport = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun showConflictDialog(conflicts: List<ConflictGroup>, onResolved: (Map<String, String>) -> Unit) {
        // A radio group per conflict does not scale: hundreds of them take
        // seconds to inflate on the UI thread. Large batches get the same two
        // choices with a lazy list for picking winners.
        if (conflicts.size > 25) {
            AlertDialog.Builder(this)
                .setTitle(R.string.conflict_dialog_title)
                .setMessage(
                    getString(
                        R.string.conflict_many_message,
                        conflicts.size,
                        conflicts.sumOf { it.members.size }
                    )
                )
                .setPositiveButton(R.string.btn_continue) { _, _ -> onResolved(emptyMap()) }
                .setNeutralButton(R.string.conflict_btn_choose) { _, _ ->
                    showConflictChooser(conflicts, onResolved)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val radioGroups = mutableListOf<Pair<String, RadioGroup>>()

        conflicts.forEach { group ->
            container.addView(
                TextView(this).apply {
                    text = group.outputKey
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bs_primary))
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 16, 0, 4)
                }
            )
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.conflict_winner_hint)
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bs_textDim))
                    textSize = 11f
                }
            )
            val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            group.members.forEachIndexed { i, member ->
                rg.addView(
                    RadioButton(this).apply {
                        id = View.generateViewId()
                        text = member.name
                        isChecked = i == 0
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bs_text))
                    }
                )
            }
            container.addView(rg)
            radioGroups += group.outputKey to rg
        }

        val scroll = dialogScrollView().apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle(R.string.conflict_dialog_title)
            .setMessage(R.string.conflict_dialog_message)
            .setView(scroll)
            .setPositiveButton(R.string.conflict_btn_build_chosen) { _, _ ->
                onResolved(collectChoices(radioGroups))
            }
            .setNeutralButton(R.string.btn_continue) { _, _ -> onResolved(emptyMap()) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun collectChoices(radioGroups: List<Pair<String, RadioGroup>>): Map<String, String> {
        val choices = mutableMapOf<String, String>()
        for ((key, rg) in radioGroups) {
            val rb = rg.findViewById<RadioButton>(rg.checkedRadioButtonId)
            if (rb != null) choices[key] = rb.text.toString()
        }
        return choices
    }

    /**
     * Winner picker for large batches. One row per conflicting output file, and
     * a tap opens that file's candidates, so the dialog stays cheap to open no
     * matter how many conflicts there are.
     */
    private fun showConflictChooser(
        conflicts: List<ConflictGroup>,
        onResolved: (Map<String, String>) -> Unit
    ) {
        val choices = linkedMapOf<String, String>()
        conflicts.forEach { choices[it.outputKey] = it.members.first().name }

        val rows = object : BaseAdapter() {
            override fun getCount() = conflicts.size
            override fun getItem(position: Int) = conflicts[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 20, 48, 20)
                    addView(TextView(this@MainActivity).apply {
                        id = OUTPUT_KEY_VIEW
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bs_primary))
                    })
                    addView(TextView(this@MainActivity).apply {
                        id = WINNER_VIEW
                        textSize = 12f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bs_textDim))
                    })
                }
                val group = conflicts[position]
                row.findViewById<TextView>(OUTPUT_KEY_VIEW).text = group.outputKey
                row.findViewById<TextView>(WINNER_VIEW).text =
                    choices[group.outputKey] ?: group.members.first().name
                return row
            }
        }

        val list = ListView(this).apply { adapter = rows }
        val screenHeight = resources.displayMetrics.heightPixels
        val rowHeight = (72 * resources.displayMetrics.density).toInt()
        val host = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf((screenHeight * 0.55f).toInt(), rowHeight * conflicts.size)
            )
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val chooser = AlertDialog.Builder(this)
            .setTitle(R.string.conflict_btn_choose)
            .setMessage(R.string.conflict_chooser_hint)
            .setView(host)
            .setPositiveButton(R.string.conflict_btn_build_chosen) { _, _ -> onResolved(choices) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            val group = conflicts[position]
            AlertDialog.Builder(this)
                .setTitle(group.outputKey)
                .setItems(group.members.map { it.name }.toTypedArray()) { _, which ->
                    choices[group.outputKey] = group.members[which].name
                    rows.notifyDataSetChanged()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        chooser.show()
    }

    // -----------------------------------------------------------------------
    // Packaging
    // -----------------------------------------------------------------------

    private fun dataRootFor(project: String): String {
        val root = File(project)
        val shapeData = ProjectScanner.shapeDataDir(root)
        return (shapeData ?: root).absolutePath
    }

    private fun buildAndPackage(
        targets: List<SliderSetEntry>,
        dataRoot: String,
        outputDir: String,
        modName: String,
        packageZip: Boolean,
        preset: PresetValues?,
        presetName: String?,
        onProgress: (done: Int, total: Int, name: String) -> Unit
    ): String {
        val staging = File(cacheDir, "bs-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        val report = StringBuilder()
        report.appendLine(
            "Preset: " + (presetName ?: "(none)") +
                if (preset == null) "" else " (${preset.big.size} big / ${preset.small.size} small values)"
        )
        if (presetName != null && preset == null) {
            report.appendLine("WARNING: preset '$presetName' could not be parsed; using set defaults.")
        }

        var builtSets = 0
        var failedSets = 0
        var fileCount = 0
        var aborted: String? = null
        val total = targets.size
        // Per-shape native logs are useful for one-off builds but turn a 400-set
        // batch into thousands of lines; only warnings survive at that size.
        val verbose = total <= 10

        val finalZip = if (packageZip) File(outputDir, "${sanitizeFileName(modName)}.zip") else null
        // Write to .part and rename at the end: a process kill mid-batch must
        // not truncate a previously good mod zip.
        val partZip = finalZip?.let { File(it.absolutePath + ".part") }
        partZip?.parentFile?.mkdirs()
        val outRoot = File(outputDir)
        val zos = partZip?.let { ZipOutputStream(BufferedOutputStream(FileOutputStream(it))) }
        val writtenDirs = HashSet<String>()

        try {
            for ((index, entry) in targets.withIndex()) {
                buildContext = "set ${index + 1}/$total: ${entry.name}"
                if (index % 20 == 0) saveBuildProgress("set ${index + 1}/$total: ${entry.name}")
                if (index % 4 == 0 || index == total - 1) {
                    onProgress(index + 1, total, entry.name)
                }
                val setStaging = File(staging, "set$index")
                setStaging.mkdirs()
                try {
                    val values = buildValuesFor(entry, preset)
                    val resultJson = NativeBridge.buildOutfit(
                        dataRoot = dataRoot,
                        ospPath = entry.file.absolutePath,
                        setName = entry.name,
                        outputDir = setStaging.absolutePath,
                        genWeights = true,
                        sliderNames = values.first,
                        bigValues = values.second,
                        smallValues = values.third
                    )
                    val obj = runCatching { JSONObject(resultJson) }.getOrNull()
                    if (obj == null || !obj.optBoolean("ok")) {
                        failedSets++
                        val err = obj?.optString("error")?.takeIf { it.isNotBlank() } ?: "unknown error"
                        report.appendLine("• ${entry.name}: FAILED ($err)")
                        continue
                    }

                    var count = 0
                    val files = obj.optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            val gamePath = f.optString("gamePath").replace('\\', '/').trimStart('/')
                            val src = File(f.optString("stagingPath"))
                            if (!src.isFile || gamePath.isEmpty()) continue

                            if (zos != null) {
                                addZipDirs(zos, writtenDirs, gamePath)
                                zos.putNextEntry(ZipEntry(gamePath))
                                src.inputStream().use { it.copyTo(zos) }
                                zos.closeEntry()
                            } else {
                                val dest = File(outRoot, gamePath)
                                dest.parentFile?.mkdirs()
                                src.copyTo(dest, overwrite = true)
                            }
                            count++
                            fileCount++
                        }
                    }

                    builtSets++
                    report.appendLine("• ${entry.name}: $count NIF(s)")
                    obj.optJSONArray("log")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val line = arr.optString(i)
                            if (verbose || line.startsWith("Warning:") || line.startsWith("Failed")) {
                                report.appendLine("  ${entry.name}: $line")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // One bad set must not throw away the whole batch: keep the
                    // meshes already written and report the failure.
                    failedSets++
                    val detail = t.message?.let { ": $it" } ?: ""
                    report.appendLine("• ${entry.name}: FAILED (${t::class.java.simpleName}$detail)")
                    if (t is OutOfMemoryError) aborted = "out of memory"
                } finally {
                    // Free this set's meshes right away; a 400+ set batch would
                    // otherwise fill the cache partition before packaging starts.
                    setStaging.deleteRecursively()
                }
                if (aborted != null) break
            }
        } catch (t: Throwable) {
            if (fileCount == 0) partZip?.delete()
            throw t
        } finally {
            buildContext = ""
            if (zos != null) runCatching { zos.close() }
            staging.deleteRecursively()
        }

        if (fileCount == 0) {
            partZip?.delete()
            report.appendLine()
            report.appendLine("No meshes were produced. Check that ShapeData and the set's morph data exist.")
            if (aborted != null) report.appendLine("ABORTED: $aborted.")
            clearBuildProgress()
            return report.toString()
        }

        if (partZip != null && finalZip != null) {
            finalZip.delete()
            if (!partZip.renameTo(finalZip)) {
                report.appendLine("Could not move ${partZip.name} to ${finalZip.name}; the finished zip is at ${partZip.absolutePath}")
            }
        }

        report.appendLine()
        if (finalZip != null) {
            report.appendLine(getString(R.string.mod_zip_created, finalZip.absolutePath))
            report.appendLine("($fileCount files from $builtSets set(s)${if (failedSets > 0) ", $failedSets failed" else ""})")
        } else {
            report.appendLine(getString(R.string.mod_files_written, fileCount, outRoot.absolutePath))
        }
        if (aborted != null) {
            report.appendLine("ABORTED: $aborted after $builtSets set(s); the output contains every completed mesh.")
        }
        clearBuildProgress()
        return report.toString()
    }

    /** Adds missing parent directory entries before a file entry. */
    private fun addZipDirs(zos: ZipOutputStream, written: MutableSet<String>, entryName: String) {
        var idx = entryName.indexOf('/')
        while (idx > 0) {
            val dir = entryName.substring(0, idx + 1)
            if (written.add(dir)) {
                zos.putNextEntry(ZipEntry(dir))
                zos.closeEntry()
            }
            idx = entryName.indexOf('/', idx + 1)
        }
    }

    /**
     * Slider definitions for a set.
     *
     * The native parser is authoritative: it is the same parser the build uses,
     * so the names sent back in the value arrays always match the sliders the
     * build looks up. The Kotlin XML parser is only a fallback for the case
     * where the native core could not read the file.
     *
     * Results are cached per set: a batch build asks for the same set's
     * definitions when resolving conflicts and again when building.
     */
    private fun parseDefs(entry: SliderSetEntry): List<SliderDef> = cachedSet(entry).defs

    private fun cachedSet(entry: SliderSetEntry): SetCacheEntry {
        val key = entry.file.absolutePath + "|" + entry.name
        setCache[key]?.let { return it }

        val json = runCatching {
            JSONObject(NativeBridge.parseSliderSet(entry.file.absolutePath, entry.name))
        }.getOrNull()
        val parsed = json?.optBoolean("ok") == true

        val defs = when {
            parsed -> defsFromJson(json!!)
            else -> emptyList()
        }.ifEmpty {
            BodySlideParser.parseSlidersForSet(entry.file, entry.name)
        }
        val outputKey = if (parsed) outputKeyFromJson(json!!, entry) else null

        val cached = SetCacheEntry(outputKey, defs)
        if (setCache.size > 1500) setCache.clear()
        setCache[key] = cached
        return cached
    }

    private fun defsFromJson(root: JSONObject): List<SliderDef> {
        val arr = root.optJSONArray("sliders") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            if (name.isBlank()) return@mapNotNull null
            SliderDef(
                name = name,
                invert = o.optBoolean("invert"),
                zap = o.optBoolean("zap"),
                hidden = o.optBoolean("hidden"),
                clamp = o.optBoolean("clamp"),
                uv = o.optBoolean("uv"),
                defaultBig = o.optDouble("defaultBig", 0.0).toInt(),
                defaultSmall = o.optDouble("defaultSmall", 0.0).toInt()
            )
        }
    }

    private fun outputKeyFromJson(json: JSONObject, entry: SliderSetEntry): String? {
        val outPath = json.optString("outputPath").trim('/', '\\')
        val outFile = json.optString("outputFile").ifBlank { entry.name }
        val genWeights = json.optBoolean("genWeights", false)
        val full = if (outPath.isEmpty()) outFile else "$outPath/$outFile"
        return full.lowercase(Locale.ROOT) + if (genWeights) "|gw" else ""
    }

    /** Build values for a set: its definitions plus the given preset, no manual edits. */
    private fun buildValuesFor(
        entry: SliderSetEntry,
        preset: PresetValues?
    ): Triple<Array<String>, FloatArray, FloatArray> {
        val selected = selectedOutfitEntry()
        val defs = if (selected != null && selected.file == entry.file && selected.name == entry.name && currentDefs.isNotEmpty()) {
            currentDefs
        } else {
            parseDefs(entry)
        }
        return composeValues(defs, preset)
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "BodySlide Build" }
    }

    // -----------------------------------------------------------------------
    // Progress / busy
    // -----------------------------------------------------------------------

    private fun showProgress(done: Int, total: Int, name: String) {
        progressText = getString(R.string.progress_text, done.coerceAtMost(total), total, name)
    }

    private fun hideProgress() {
        progressText = ""
    }

    private fun updateBusy(value: Boolean) {
        busy = value
        if (!value) hideProgress()
    }

    // -----------------------------------------------------------------------
    // Logs / utils
    // -----------------------------------------------------------------------

    private fun showLogsDialog() {
        val text = sessionLog.toString().ifBlank { getString(R.string.logs_empty) }
        logExpanded = true
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.bs_text))
            textSize = 12f
            setPadding(32, 24, 32, 8)
            setTextIsSelectable(true)
        }
        val scroll = dialogScrollView().apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_logs_title)
            .setView(scroll)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    /**
     * Persists the last uncaught exception plus the build step that was running,
     * so a native/Java crash in a long batch leaves something to inspect after
     * the process dies.
     */
    private fun saveBuildProgress(text: String) {
        runCatching { File(filesDir, PROGRESS_FILE).writeText(text) }
    }

    private fun clearBuildProgress() {
        runCatching { File(filesDir, PROGRESS_FILE).delete() }
    }

    private fun installCrashLogger() {
        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                val rt = Runtime.getRuntime()
                val heap = "heap ${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)}" +
                    "/${rt.maxMemory() / (1024 * 1024)} MB"
                File(filesDir, CRASH_FILE).writeText(
                    buildString {
                        appendLine("${Date()}  thread=${thread.name}  $heap")
                        if (buildContext.isNotBlank()) appendLine("last build step: $buildContext")
                        appendLine(trace.toString())
                    }
                )
            }
            fallback?.uncaughtException(thread, error)
        }
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sessionLog.appendLine("[$ts] $message")
        // Keep the in-memory log bounded: a long 400+ set batch can otherwise
        // copy megabytes into Compose state on every line.
        if (sessionLog.length > LOG_MAX_CHARS) {
            sessionLog.delete(0, sessionLog.length - LOG_KEEP_CHARS)
        }
        logText = sessionLog.toString()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CRASH_FILE = "crash-last.txt"
        private const val PROGRESS_FILE = "build-progress.txt"
        private const val LOG_MAX_CHARS = 200_000
        private const val LOG_KEEP_CHARS = 150_000
        private const val PREFS = "bodyslide_android"
        private const val PREF_PROJECT_PATH = "project_path"
        private const val PREF_GAME_DATA_PATH = "game_data_path"
        private const val PREF_OUTPUT_PATH = "output_path"
        private const val PREF_MOD_NAME = "mod_name"
        private const val PREF_PACKAGE_ZIP = "package_zip"
        private const val OUTPUT_KEY_VIEW = 1
        private const val WINNER_VIEW = 2
    }
}
