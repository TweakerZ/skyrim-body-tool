package com.bodyslide.android

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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
        showProgress(0, targets.size, getString(R.string.progress_preparing))

        lifecycleScope.launch {
            val conflicts = withContext(Dispatchers.IO) { findConflicts(targets) }
            if (conflicts.isEmpty()) {
                log(getString(R.string.conflict_summary_free, targets.size))
                startBuild(targets, emptyMap())
            } else {
                hideProgress()
                statusText = getString(R.string.status_ready)
                log(getString(R.string.conflict_summary, conflicts.size, targets.size - countLosers(conflicts)))
                log("Output conflicts: " + conflicts.joinToString { g -> "${g.outputKey} <- ${g.members.joinToString("/") { it.name }}" })
                showConflictDialog(conflicts) { choices -> startBuild(targets, choices) }
            }
        }
    }

    private fun startBuild(targets: List<SliderSetEntry>, choices: Map<String, String>) {
        val resolved = applyConflictChoices(targets, choices)
        if (resolved.isEmpty()) {
            toast("Nothing to build after conflict resolution")
            return
        }

        val project = projectPath!!
        val output = outputPath!!
        val buildModName = modName.trim().ifBlank { resolved.first().name }
        val zipWanted = packageZip
        val dataRoot = dataRootFor(project)
        val presetEntry = selectedPresetEntry()

        log("Data root (ShapeData): $dataRoot")
        log("Mod output: $output")
        log("Mod name: $buildModName  |  Package as zip: $zipWanted")
        log("Preset: ${presetEntry?.name ?: "(none — using set defaults)"}")
        log("Building ${resolved.size} set(s): ${resolved.joinToString(", ") { it.name }}")

        updateBusy(true)
        showProgress(0, resolved.size, getString(R.string.progress_preparing))

        lifecycleScope.launch {
            try {
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
                        onProgress = { done, total, name -> runOnUiThread { showProgress(done, total, name) } },
                        onPackaging = { runOnUiThread { showPackaging(resolved.size) } }
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

    private fun outputKeyFor(entry: SliderSetEntry): String? {
        val json = runCatching {
            JSONObject(NativeBridge.parseSliderSet(entry.file.absolutePath, entry.name))
        }.getOrNull() ?: return null
        if (!json.optBoolean("ok", false)) return null

        val outPath = json.optString("outputPath").trim('/', '\\')
        val outFile = json.optString("outputFile").ifBlank { entry.name }
        val genWeights = json.optBoolean("genWeights", false)
        val full = if (outPath.isEmpty()) outFile else "$outPath/$outFile"
        return full.lowercase(Locale.ROOT) + if (genWeights) "|gw" else ""
    }

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
            .setPositiveButton(R.string.btn_continue) { _, _ ->
                val choices = mutableMapOf<String, String>()
                for ((key, rg) in radioGroups) {
                    val rb = rg.findViewById<RadioButton>(rg.checkedRadioButtonId)
                    if (rb != null) choices[key] = rb.text.toString()
                }
                onResolved(choices)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
        onProgress: (done: Int, total: Int, name: String) -> Unit,
        onPackaging: (total: Int) -> Unit
    ): String {
        val staging = File(cacheDir, "bs-staging")
        staging.deleteRecursively()
        staging.mkdirs()

        val built = mutableListOf<Pair<File, String>>()
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
        val total = targets.size

        targets.forEachIndexed { index, entry ->
            onProgress(index + 1, total, entry.name)
            val setStaging = File(staging, "set$index")
            setStaging.mkdirs()

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
                return@forEachIndexed
            }
            val files = obj.optJSONArray("files")
            var count = 0
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val stagingPath = f.optString("stagingPath")
                    val gamePath = f.optString("gamePath")
                    val src = File(stagingPath)
                    if (src.isFile && gamePath.isNotBlank()) {
                        built += src to gamePath
                        count++
                    }
                }
            }
            builtSets++
            report.appendLine("• ${entry.name}: $count NIF(s)")
            obj.optJSONArray("log")?.let { arr ->
                for (i in 0 until arr.length()) log("  ${entry.name}: ${arr.optString(i)}")
            }
        }

        if (built.isEmpty()) {
            report.appendLine("No meshes were produced. Check that ShapeData and the set's morph data exist.")
            return report.toString()
        }

        onPackaging(total)
        if (packageZip) {
            val zipFile = File(outputDir, "${sanitizeFileName(modName)}.zip")
            zipFile.parentFile?.mkdirs()
            writeModZip(built, zipFile)
            report.appendLine()
            report.appendLine(getString(R.string.mod_zip_created, zipFile.absolutePath))
            report.appendLine("(${built.size} files from $builtSets set(s)${if (failedSets > 0) ", $failedSets failed" else ""})")
        } else {
            val outRoot = File(outputDir)
            for ((src, gamePath) in built) {
                val dest = File(outRoot, gamePath)
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
            report.appendLine()
            report.appendLine(getString(R.string.mod_files_written, built.size, outRoot.absolutePath))
        }

        staging.deleteRecursively()
        return report.toString()
    }

    /**
     * Slider definitions for a set.
     *
     * The native parser is authoritative: it is the same parser the build uses,
     * so the names sent back in the value arrays always match the sliders the
     * build looks up. The Kotlin XML parser is only a fallback for the case
     * where the native core could not read the file.
     */
    private fun parseDefs(entry: SliderSetEntry): List<SliderDef> {
        val native = nativeSliderDefs(entry)
        if (native.isNotEmpty()) return native
        return BodySlideParser.parseSlidersForSet(entry.file, entry.name)
    }

    private fun nativeSliderDefs(entry: SliderSetEntry): List<SliderDef> {
        val json = NativeBridge.parseSliderSet(entry.file.absolutePath, entry.name)
        return runCatching {
            val root = JSONObject(json)
            if (!root.optBoolean("ok")) return emptyList()
            val arr = root.optJSONArray("sliders") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
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
        }.getOrDefault(emptyList())
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

    private fun writeModZip(files: List<Pair<File, String>>, zipFile: File) {
        // De-duplicate by archive entry name (last build wins).
        val entries = linkedMapOf<String, File>()
        for ((src, gamePath) in files) {
            val entryName = gamePath.replace('\\', '/').trimStart('/')
            if (entryName.isNotEmpty()) entries[entryName] = src
        }

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // Game-data-relative entries at the archive root so the mod loader
            // recognises the layout and deploys into the game's Data folder.
            val dirs = linkedSetOf<String>()
            for (entryName in entries.keys) {
                var idx = entryName.indexOf('/')
                while (idx > 0) {
                    dirs += entryName.substring(0, idx + 1)
                    idx = entryName.indexOf('/', idx + 1)
                }
            }
            for (dir in dirs) {
                zos.putNextEntry(ZipEntry(dir))
                zos.closeEntry()
            }
            for ((entryName, src) in entries) {
                zos.putNextEntry(ZipEntry(entryName))
                src.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
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

    private fun showPackaging(total: Int) {
        progressText = getString(R.string.progress_packaging)
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

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        sessionLog.appendLine("[$ts] $message")
        logText = sessionLog.toString()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS = "bodyslide_android"
        private const val PREF_PROJECT_PATH = "project_path"
        private const val PREF_GAME_DATA_PATH = "game_data_path"
        private const val PREF_OUTPUT_PATH = "output_path"
        private const val PREF_MOD_NAME = "mod_name"
        private const val PREF_PACKAGE_ZIP = "package_zip"
    }
}
