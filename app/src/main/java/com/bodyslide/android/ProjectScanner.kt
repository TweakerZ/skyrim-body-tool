package com.bodyslide.android

import java.io.File

data class SliderSetEntry(
    val name: String,
    val file: File
)

data class PresetEntry(
    val name: String,
    val file: File
)

object ProjectScanner {

    // Folder names as shipped by BodySlide (lowercased here; matching is case-insensitive
    // because Android external storage may present them in any case).
    private const val DIR_SLIDERSETS = "slidersets"
    private const val DIR_SLIDERPRESETS = "sliderpresets"
    private const val DIR_SLIDERGROUPS = "slidergroups"
    private const val DIR_SHAPEDATA = "shapedata"

    /** Finds a direct child directory by name, ignoring case. */
    fun childDir(parent: File, vararg names: String): File? {
        val children = parent.listFiles() ?: return null
        return children.firstOrNull { child ->
            child.isDirectory && names.any { it.equals(child.name, ignoreCase = true) }
        }
    }

    fun sliderSetsDir(projectRoot: File): File? = childDir(projectRoot, DIR_SLIDERSETS)

    fun presetsDir(projectRoot: File): File? = childDir(projectRoot, DIR_SLIDERPRESETS)

    fun groupsDir(projectRoot: File): File? = childDir(projectRoot, DIR_SLIDERGROUPS)

    fun shapeDataDir(projectRoot: File): File? = childDir(projectRoot, DIR_SHAPEDATA)

    fun findSliderSets(projectRoot: File): List<SliderSetEntry> {
        if (!projectRoot.isDirectory) return emptyList()
        val dir = sliderSetsDir(projectRoot)

        // Preferred: the SliderSets folder. Fallback: scan the project root so
        // simple layouts (everything in one folder) still work.
        val roots = if (dir != null) listOf(dir) else listOf(projectRoot)

        val entries = mutableListOf<SliderSetEntry>()
        val seenFiles = linkedSetOf<File>()
        roots.forEach { root ->
            collectFiles(root, listOf("osp", "xml")) { file ->
                if (!seenFiles.add(file)) return@collectFiles
                val names = parseSliderSetNames(file)
                if (names.isNotEmpty()) {
                    names.forEach { n -> entries += SliderSetEntry(n, file) }
                } else if (dir != null && file.extension.equals("osp", ignoreCase = true)) {
                    // An .osp in the SliderSets folder is a slider set even if the
                    // name attribute could not be parsed.
                    entries += SliderSetEntry(file.nameWithoutExtension, file)
                }
            }
        }
        return entries.sortedBy { it.name.lowercase() }
    }

    fun findPresets(projectRoot: File): List<PresetEntry> {
        if (!projectRoot.isDirectory) return emptyList()
        val dir = presetsDir(projectRoot) ?: return emptyList()

        val entries = mutableListOf<PresetEntry>()
        collectFiles(dir, listOf("xml")) { file ->
            val names = parsePresetNames(file)
            if (names.isEmpty()) entries += PresetEntry(file.nameWithoutExtension, file)
            else names.forEach { n -> entries += PresetEntry(n, file) }
        }
        return entries.sortedBy { it.name.lowercase() }
    }

    fun findGroups(projectRoot: File): List<String> {
        val dir = groupsDir(projectRoot) ?: return emptyList()
        val groups = linkedSetOf<String>()
        collectFiles(dir, listOf("xml")) { file ->
            runCatching {
                val text = file.readText()
                Regex("<Group\\s+name=\"([^\"]+)\"").findAll(text).forEach { groups += it.groupValues[1] }
            }
        }
        return groups.toList().sortedBy { it.lowercase() }
    }

    /**
     * Maps group name -> member slider-set names, from SliderGroups XML files:
     * <SliderGroups><Group name="X"><Member name="SetName"/>...</Group></SliderGroups>
     */
    fun findGroupMembers(projectRoot: File): Map<String, Set<String>> {
        val dir = groupsDir(projectRoot) ?: return emptyMap()

        val result = linkedMapOf<String, MutableSet<String>>()
        collectFiles(dir, listOf("xml")) { file ->
            runCatching {
                val text = file.readText()
                val groupRegex = Regex(
                    "<Group\\s+name=\"([^\"]+)\"[^>]*>(.*?)</Group>",
                    setOf(RegexOption.DOT_MATCHES_ALL)
                )
                groupRegex.findAll(text).forEach { m ->
                    val groupName = m.groupValues[1]
                    val members = Regex("<Member\\s+name=\"([^\"]+)\"")
                        .findAll(m.groupValues[2]).map { it.groupValues[1] }.toSet()
                    if (members.isNotEmpty()) {
                        result.getOrPut(groupName) { linkedSetOf() }.addAll(members)
                    }
                }
            }
        }
        return result
    }

    private fun collectFiles(root: File, exts: List<String>, maxDepth: Int = 4, depth: Int = 0, onFile: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = root.listFiles() ?: return
        children.forEach { child ->
            if (child.isDirectory) collectFiles(child, exts, maxDepth, depth + 1, onFile)
            else if (child.isFile && exts.any { child.extension.equals(it, ignoreCase = true) }) onFile(child)
        }
    }

    private fun parseSliderSetNames(file: File): List<String> {
        return runCatching {
            val text = file.readText()
            Regex("<SliderSet(?:AddOn)?\\s+[^>]*name=\"([^\"]+)\"")
                .findAll(text).map { it.groupValues[1] }.distinct().toList()
        }.getOrDefault(emptyList())
    }

    private fun parsePresetNames(file: File): List<String> {
        return runCatching {
            val text = file.readText()
            Regex("<Preset\\s+[^>]*name=\"([^\"]+)\"")
                .findAll(text).map { it.groupValues[1] }.distinct().toList()
        }.getOrDefault(emptyList())
    }
}
