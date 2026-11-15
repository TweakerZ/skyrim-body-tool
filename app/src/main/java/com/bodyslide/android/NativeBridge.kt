package com.bodyslide.android

object NativeBridge {
    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("bodyslidecore")
            loaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun coreInfo(): String {
        return if (ensureLoaded()) nativeCoreInfo() else "Native core not loaded."
    }

    fun buildPlan(
        projectPath: String,
        outputPath: String,
        outfitName: String,
        presetName: String,
        sliderValuesJson: String
    ): String {
        return if (ensureLoaded()) {
            nativeBuildPlan(projectPath, outputPath, outfitName, presetName, sliderValuesJson)
        } else {
            "Native core not loaded; cannot generate native build plan."
        }
    }

    fun parseSliderSet(filePath: String, setName: String): String {
        return if (ensureLoaded()) nativeParseSliderSet(filePath, setName) else "{\"ok\":false,\"error\":\"Native core not loaded\"}"
    }

    fun inspectNif(filePath: String): String {
        return if (ensureLoaded()) nativeInspectNif(filePath) else "{\"ok\":false,\"error\":\"Native core not loaded\"}"
    }

    fun buildOutfit(
        dataRoot: String,
        ospPath: String,
        setName: String,
        outputDir: String,
        genWeights: Boolean,
        sliderNames: Array<String>,
        bigValues: FloatArray,
        smallValues: FloatArray
    ): String {
        return if (ensureLoaded()) {
            nativeBuildOutfit(dataRoot, ospPath, setName, outputDir, genWeights, sliderNames, bigValues, smallValues)
        } else {
            "{\"ok\":false,\"error\":\"Native core not loaded\"}"
        }
    }

    @JvmStatic
    private external fun nativeCoreInfo(): String

    @JvmStatic
    private external fun nativeParseSliderSet(filePath: String, setName: String): String

    @JvmStatic
    private external fun nativeInspectNif(filePath: String): String

    @JvmStatic
    private external fun nativeBuildOutfit(
        dataRoot: String,
        ospPath: String,
        setName: String,
        outputDir: String,
        genWeights: Boolean,
        sliderNames: Array<String>,
        bigValues: FloatArray,
        smallValues: FloatArray
    ): String

    @JvmStatic
    private external fun nativeBuildPlan(
        projectPath: String,
        outputPath: String,
        outfitName: String,
        presetName: String,
        sliderValuesJson: String
    ): String
}
