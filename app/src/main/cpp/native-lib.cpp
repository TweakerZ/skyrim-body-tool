#include <jni.h>
#include <string>
#include <sstream>
#include <vector>
#include <map>
#include <algorithm>
#include <fstream>
#include <android/log.h>
#include "tinyxml2.h"
#include "NifFile.hpp"
#include "Geometry.hpp"
#include "bs_build.hpp"

#define LOG_TAG "BodySlideCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace tinyxml2;

namespace {
std::string jstringToStd(JNIEnv* env, jstring jstr) {
    if (!jstr) return {};
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

std::string escapeJson(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    // skip other control chars
                } else {
                    out += c;
                }
        }
    }
    return out;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bodyslide_android_NativeBridge_nativeCoreInfo(JNIEnv* env, jclass /*clazz*/) {
    std::string info = "BodySlide native core (Skyrim LE): tinyxml2 + nifly; .osp/OSD/BSD build engine ready.";
    LOGI("%s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bodyslide_android_NativeBridge_nativeInspectNif(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring filePath) {
    std::string path = jstringToStd(env, filePath);
    try {
        std::ifstream in(path, std::ios::binary);
        if (!in) {
            std::ostringstream out;
            out << "{\"ok\":false,\"error\":\"Cannot open file: " << escapeJson(path) << "\"}";
            return env->NewStringUTF(out.str().c_str());
        }

        nifly::NifFile nif(in);
        if (!nif.IsValid()) {
            std::ostringstream out;
            out << "{\"ok\":false,\"error\":\"Invalid or unsupported NIF: " << escapeJson(path) << "\"}";
            return env->NewStringUTF(out.str().c_str());
        }

        auto shapes = nif.GetShapes();
        std::ostringstream out;
        out << "{";
        out << "\"ok\":true,";
        out << "\"path\":\"" << escapeJson(path) << "\",";
        out << "\"shapeCount\":" << shapes.size() << ",";
        out << "\"shapes\":[";
        for (size_t i = 0; i < shapes.size(); ++i) {
            if (i) out << ",";
            nifly::NiShape* shape = shapes[i];
            std::string name = shape ? shape->name.get() : "";
            size_t verts = 0;
            size_t tris = 0;
            if (shape) {
                const std::vector<nifly::Vector3>* v = nif.GetVertsForShape(shape);
                if (v) verts = v->size();
                std::vector<nifly::Triangle> t;
                shape->GetTriangles(t);
                tris = t.size();
            }
            out << "{\"name\":\"" << escapeJson(name) << "\"";
            out << ",\"vertices\":" << verts;
            out << ",\"triangles\":" << tris;
            out << "}";
        }
        out << "]}";

        LOGI("Inspected NIF '%s': %zu shapes", path.c_str(), shapes.size());
        return env->NewStringUTF(out.str().c_str());
    } catch (const std::exception& e) {
        std::ostringstream out;
        out << "{\"ok\":false,\"error\":\"Exception: " << escapeJson(e.what()) << "\"}";
        LOGE("nativeInspectNif exception: %s", e.what());
        return env->NewStringUTF(out.str().c_str());
    } catch (...) {
        LOGE("nativeInspectNif unknown exception");
        return env->NewStringUTF("{\"ok\":false,\"error\":\"Unknown exception\"}");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bodyslide_android_NativeBridge_nativeParseSliderSet(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring filePath,
        jstring targetSetName) {
    std::string path = jstringToStd(env, filePath);
    std::string setName = jstringToStd(env, targetSetName);

    bs::SliderSetInfo set = bs::parseSliderSet(path, setName);
    std::string json = bs::sliderSetToJson(set);
    if (set.ok)
        LOGI("Parsed slider set '%s': %zu sliders, %zu shapes", set.name.c_str(), set.sliders.size(), set.shapes.size());
    else
        LOGW("Parse slider set failed: %s", set.error.c_str());
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bodyslide_android_NativeBridge_nativeBuildOutfit(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring dataRoot,
        jstring ospPath,
        jstring setName,
        jstring outputDir,
        jboolean genWeights,
        jobjectArray sliderNames,
        jfloatArray bigValues,
        jfloatArray smallValues) {
    bs::BuildRequest request;
    request.dataRoot = jstringToStd(env, dataRoot);
    request.ospPath = jstringToStd(env, ospPath);
    request.setName = jstringToStd(env, setName);
    request.outputDir = jstringToStd(env, outputDir);
    request.genWeights = genWeights == JNI_TRUE;

    const jsize count = sliderNames ? env->GetArrayLength(sliderNames) : 0;
    std::vector<std::string> names(count);
    for (jsize i = 0; i < count; ++i) {
        jstring js = static_cast<jstring>(env->GetObjectArrayElement(sliderNames, i));
        names[i] = jstringToStd(env, js);
        if (js) env->DeleteLocalRef(js);
    }

    auto readFloats = [&](jfloatArray arr, std::map<std::string, float>& dest) {
        if (!arr) return;
        jsize n = env->GetArrayLength(arr);
        jfloat* data = env->GetFloatArrayElements(arr, nullptr);
        if (!data) return;
        for (jsize i = 0; i < n && i < count; ++i)
            dest[names[i]] = data[i];
        env->ReleaseFloatArrayElements(arr, data, JNI_ABORT);
    };
    readFloats(bigValues, request.bigValues);
    readFloats(smallValues, request.smallValues);

    bs::BuildResult result;
    try {
        result = bs::buildOutfit(request);
    } catch (const std::exception& e) {
        result.ok = false;
        result.error = std::string("Exception: ") + e.what();
        LOGE("nativeBuildOutfit exception: %s", e.what());
    } catch (...) {
        result.ok = false;
        result.error = "Unknown exception";
        LOGE("nativeBuildOutfit unknown exception");
    }

    for (const auto& line : result.log)
        LOGI("build: %s", line.c_str());
    if (!result.ok)
        LOGE("Build failed: %s", result.error.c_str());
    else
        LOGI("Build produced %zu file(s)", result.files.size());

    return env->NewStringUTF(bs::buildResultToJson(result).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bodyslide_android_NativeBridge_nativeBuildPlan(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring projectPath,
        jstring outputPath,
        jstring outfitName,
        jstring presetName,
        jstring sliderValuesJson) {
    std::string project = jstringToStd(env, projectPath);
    std::string output = jstringToStd(env, outputPath);
    std::string outfit = jstringToStd(env, outfitName);
    std::string preset = jstringToStd(env, presetName);
    std::string values = jstringToStd(env, sliderValuesJson);

    std::ostringstream out;
    out << "Build plan (native core, Skyrim LE):\n";
    out << "- Target: Skyrim Legendary Edition (static Android port)\n";
    out << "- Project: " << project << "\n";
    out << "- Output: " << output << "\n";
    out << "- Outfit: " << outfit << "\n";
    out << "- Preset: " << (preset.empty() ? "(none)" : preset) << "\n";
    out << "- Values: " << values << "\n";
    out << "Status: .osp/OSD/BSD build engine wired via nativeBuildOutfit.";

    std::string result = out.str();
    LOGI("Build plan requested for outfit '%s'", outfit.c_str());
    return env->NewStringUTF(result.c_str());
}
