package com.bodyslide.android

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

data class SliderDef(
    val name: String,
    val invert: Boolean = false,
    val zap: Boolean = false,
    val hidden: Boolean = false,
    val clamp: Boolean = false,
    val uv: Boolean = false,
    val defaultBig: Int = 0,
    val defaultSmall: Int = 0
)

data class PresetValues(
    val name: String,
    val big: MutableMap<String, Int> = mutableMapOf(),
    val small: MutableMap<String, Int> = mutableMapOf()
)

object BodySlideParser {

    fun parseSlidersForSet(file: File, setName: String): List<SliderDef> {
        return runCatching {
            val text = file.readText()
            parseSlidersFromXml(text, setName)
        }.getOrDefault(emptyList())
    }

    private fun parseSlidersFromXml(xml: String, setName: String): List<SliderDef> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val sliders = mutableListOf<SliderDef>()
        var event = parser.eventType
        var inTargetSet = false
        var setDepth = 0

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name ?: ""
                    if (!inTargetSet && (tag == "SliderSet" || tag == "SliderSetAddOn")) {
                        val name = parser.getAttributeValue(null, "name") ?: ""
                        if (name == setName) {
                            inTargetSet = true
                            setDepth = 1
                        }
                    } else if (inTargetSet) {
                        if (tag == "SliderSet" || tag == "SliderSetAddOn") {
                            setDepth++
                        } else if (tag == "Slider" && setDepth == 1) {
                            val sName = parser.getAttributeValue(null, "name")
                            if (!sName.isNullOrBlank()) {
                                sliders += SliderDef(
                                    name = sName,
                                    invert = attrBool(parser, "invert"),
                                    zap = attrBool(parser, "zap"),
                                    hidden = attrBool(parser, "hidden"),
                                    clamp = attrBool(parser, "clamp"),
                                    uv = attrBool(parser, "uv"),
                                    defaultBig = attrInt(parser, "defaultBig", attrInt(parser, "big", 0)),
                                    defaultSmall = attrInt(parser, "defaultSmall", attrInt(parser, "small", 0))
                                )
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (inTargetSet && (tag == "SliderSet" || tag == "SliderSetAddOn")) {
                        setDepth--
                        if (setDepth <= 0) {
                            // finished target set
                            return sliders
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sliders
    }

    fun parsePreset(file: File, presetName: String): PresetValues? {
        return runCatching {
            val text = file.readText()
            parsePresetFromXml(text, presetName)
        }.getOrNull()
    }

    private fun parsePresetFromXml(xml: String, presetName: String): PresetValues? {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        var inTarget = false
        var depth = 0
        var result: PresetValues? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name ?: ""
                    if (!inTarget && tag == "Preset") {
                        val name = parser.getAttributeValue(null, "name") ?: ""
                        if (name == presetName) {
                            inTarget = true
                            depth = 1
                            result = PresetValues(name)
                        }
                    } else if (inTarget) {
                        if (tag == "Preset") {
                            depth++
                        } else if (tag == "SetSlider" && depth == 1 && result != null) {
                            val slider = parser.getAttributeValue(null, "name") ?: ""
                            val size = (parser.getAttributeValue(null, "size") ?: "").lowercase()
                            val value = attrInt(parser, "value", Int.MIN_VALUE)
                            if (slider.isNotBlank() && value != Int.MIN_VALUE) {
                                when (size) {
                                    "small" -> result.small[slider] = value
                                    "big" -> result.big[slider] = value
                                    // BodySlide writes "both" for values that apply to
                                    // both weight variants.
                                    "both" -> {
                                        result.big[slider] = value
                                        result.small[slider] = value
                                    }
                                    else -> {
                                        // some presets may use different schema; try to be tolerant
                                        result.big[slider] = value
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (inTarget && tag == "Preset") {
                        depth--
                        if (depth <= 0) return result
                    }
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun attrBool(parser: XmlPullParser, name: String): Boolean {
        val v = parser.getAttributeValue(null, name)?.lowercase() ?: return false
        return v == "true" || v == "1" || v == "yes"
    }

    private fun attrInt(parser: XmlPullParser, name: String, default: Int): Int {
        val v = parser.getAttributeValue(null, name) ?: return default
        return v.toIntOrNull() ?: v.toFloatOrNull()?.toInt() ?: default
    }
}
