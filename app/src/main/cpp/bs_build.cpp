// BodySlide native build core for Skyrim LE (static Android port).
#include "bs_build.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <set>
#include <sstream>

#include "tinyxml2.h"
#include "Geometry.hpp"
#include "Objects.hpp"
#include "NifFile.hpp"

namespace bs {

namespace {

using namespace tinyxml2;

constexpr float kEpsilon = 1e-8f;

std::string toSlash(std::string s) {
    for (char& c : s)
        if (c == '\\')
            c = '/';
    return s;
}

std::string joinPath(const std::string& a, const std::string& b) {
    if (a.empty())
        return b;
    if (b.empty())
        return a;
    if (a.back() == '/' || a.back() == '\\')
        return a + b;
    return a + "/" + b;
}

std::string dirName(const std::string& path) {
    size_t p = path.find_last_of("/\\");
    return p == std::string::npos ? std::string() : path.substr(0, p);
}

bool fileExists(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return static_cast<bool>(f);
}

bool sameNameCI(const std::string& a, const std::string& b) {
    if (a.size() != b.size())
        return false;
    for (size_t i = 0; i < a.size(); ++i)
        if (std::tolower(static_cast<unsigned char>(a[i])) != std::tolower(static_cast<unsigned char>(b[i])))
            return false;
    return true;
}

// BodySlide projects are authored on Windows, where paths are case-insensitive.
// Android's emulated storage usually matches case too, but internal storage and
// some ROMs do not, so resolve every path component ignoring case.
std::string resolvePathCI(const std::string& path) {
    if (path.empty() || fileExists(path))
        return path;

    std::vector<std::string> parts;
    std::string cur;
    for (size_t i = 0; i <= path.size(); ++i) {
        char c = i < path.size() ? path[i] : '/';
        if (c == '/' || c == '\\') {
            if (!cur.empty())
                parts.push_back(cur);
            cur.clear();
        } else {
            cur += c;
        }
    }
    if (parts.empty())
        return path;

    std::string resolved = (path[0] == '/' || path[0] == '\\') ? "/" : "";
    for (size_t i = 0; i < parts.size(); ++i) {
        const std::string& part = parts[i];
        std::string candidate = resolved.empty() ? part : joinPath(resolved, part);
        std::error_code dirEc;
        if (fileExists(candidate) || std::filesystem::is_directory(candidate, dirEc)) {
            resolved = candidate;
            continue;
        }

        std::error_code ec;
        bool matched = false;
        std::string parent = resolved.empty() ? "." : resolved;
        for (std::filesystem::directory_iterator it(parent, ec), end; !ec && it != end; it.increment(ec)) {
            const std::string name = it->path().filename().string();
            if (!sameNameCI(name, part))
                continue;
            resolved = resolved.empty() ? name : joinPath(resolved, name);
            matched = true;
            break;
        }
        if (!matched)
            return "";
    }
    return resolved;
}

std::string jsonEscape(const std::string& in) {
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
                if (static_cast<unsigned char>(c) >= 0x20)
                    out += c;
        }
    }
    return out;
}

// ---------------------------------------------------------------------------
// Diff data (port of DiffDataSets OSD/BSD loading)
// ---------------------------------------------------------------------------

struct DiffEntry {
    uint16_t index = 0;
    nifly::Vector3 diff;
};

// key (data name) -> list of vertex diffs
using DiffMap = std::map<std::string, std::vector<DiffEntry>>;

#pragma pack(push, 1)
struct OsdDiffStruct {
    uint16_t index;
    float x, y, z;
};
#pragma pack(pop)

bool loadOsdFile(const std::string& path, DiffMap& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return false;

    // BodySlide writes the magic as the big-endian multichar literal "OSD\0"
    // (0x4F534400), which is what a little-endian read of the file yields.
    constexpr uint32_t kOsdHeader = 0x4F534400u;

    uint32_t header = 0, version = 1, dataCount = 0;
    in.read(reinterpret_cast<char*>(&header), 4);
    if (header != kOsdHeader)
        return false;
    in.read(reinterpret_cast<char*>(&version), 4);
    in.read(reinterpret_cast<char*>(&dataCount), 4);

    uint8_t nameLength = 0;
    uint16_t diffSize = 0;
    std::string dataName;
    for (uint32_t i = 0; i < dataCount; ++i) {
        in.read(reinterpret_cast<char*>(&nameLength), 1);
        dataName.assign(nameLength, '\0');
        if (nameLength)
            in.read(&dataName[0], nameLength);
        in.read(reinterpret_cast<char*>(&diffSize), 2);

        std::vector<DiffEntry> diffs;
        diffs.reserve(diffSize);
        OsdDiffStruct entry{};
        for (uint16_t d = 0; d < diffSize; ++d) {
            in.read(reinterpret_cast<char*>(&entry), sizeof(OsdDiffStruct));
            nifly::Vector3 v(entry.x, entry.y, entry.z);
            v.clampEpsilon();
            diffs.push_back({entry.index, v});
        }
        out[dataName] = std::move(diffs);
    }
    return true;
}

bool loadBsdFile(const std::string& path, const std::string& key, DiffMap& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in)
        return false;

    uint32_t count = 0;
    in.read(reinterpret_cast<char*>(&count), 4);
    if (!in)
        return false;

    std::vector<DiffEntry> diffs;
    diffs.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t idx = 0;
        float x = 0, y = 0, z = 0;
        in.read(reinterpret_cast<char*>(&idx), 4);
        in.read(reinterpret_cast<char*>(&x), 4);
        in.read(reinterpret_cast<char*>(&y), 4);
        in.read(reinterpret_cast<char*>(&z), 4);
        nifly::Vector3 v(x, y, z);
        v.clampEpsilon();
        diffs.push_back({static_cast<uint16_t>(idx), v});
    }
    out[key] = std::move(diffs);
    return true;
}

bool endsWithCI(const std::string& s, const std::string& suffix) {
    if (s.size() < suffix.size())
        return false;
    return std::equal(suffix.rbegin(), suffix.rend(), s.rbegin(), [](char a, char b) {
        return std::tolower(static_cast<unsigned char>(a)) == std::tolower(static_cast<unsigned char>(b));
    });
}

// ---------------------------------------------------------------------------
// .osp parsing
// ---------------------------------------------------------------------------

bool isSetElement(const char* name) {
    if (!name)
        return false;
    return std::string(name) == "SliderSet" || std::string(name) == "SliderSetAddOn";
}

XMLElement* findSetElement(XMLElement* root, const std::string& wanted) {
    if (!root)
        return nullptr;

    // Depth-first search for the matching slider set.
    std::vector<XMLElement*> stack;
    stack.push_back(root);
    XMLElement* firstAny = nullptr;
    while (!stack.empty()) {
        XMLElement* e = stack.back();
        stack.pop_back();

        if (isSetElement(e->Name())) {
            const char* n = e->Attribute("name");
            if (!wanted.empty()) {
                if (n && wanted == n)
                    return e;
            } else if (!firstAny) {
                firstAny = e;
            }
        }

        // push children in reverse so document order is preserved for "first" set
        std::vector<XMLElement*> children;
        for (XMLElement* c = e->FirstChildElement(); c; c = c->NextSiblingElement())
            children.push_back(c);
        for (auto it = children.rbegin(); it != children.rend(); ++it)
            stack.push_back(*it);
    }
    return firstAny;
}

std::string attrText(XMLElement* e, const char* name) {
    const char* v = e->Attribute(name);
    return v ? v : "";
}

bool boolAttr(XMLElement* e, const char* name, bool def) {
    return e->BoolAttribute(name, def);
}

float floatAttr(XMLElement* e, const char* name, float def) {
    return e->FloatAttribute(name, def);
}

std::vector<std::string> splitSemicolon(const std::string& s) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : s) {
        if (c == ';') {
            out.push_back(cur);
            cur.clear();
        } else {
            cur += c;
        }
    }
    if (!cur.empty())
        out.push_back(cur);
    return out;
}

}  // namespace

SliderSetInfo parseSliderSet(const std::string& ospPath, const std::string& setName) {
    SliderSetInfo set;

    XMLDocument doc;
    if (doc.LoadFile(ospPath.c_str()) != XML_SUCCESS) {
        set.error = "Failed to load XML: " + ospPath;
        return set;
    }

    XMLElement* root = doc.RootElement();
    if (!root) {
        set.error = "Missing root element";
        return set;
    }

    XMLElement* setElem = findSetElement(root, setName);
    if (!setElem) {
        set.error = "Slider set not found: " + setName;
        return set;
    }

    set.name = attrText(setElem, "name");
    set.addOn = std::string(setElem->Name()) == "SliderSetAddOn";

    int version = 0;
    if (root->Attribute("version"))
        version = root->IntAttribute("version", 0);
    const std::string shapeTag = version >= 1 ? "Shape" : "BaseShapeName";
    const std::string dataFolderTag = version >= 1 ? "DataFolder" : "SetFolder";

    // Set-level elements.
    for (XMLElement* child = setElem->FirstChildElement(); child; child = child->NextSiblingElement()) {
        const char* nm = child->Name();
        if (!nm)
            continue;
        std::string tag = nm;
        if ((tag == dataFolderTag || tag == "DataFolder" || tag == "SetFolder") && child->GetText())
            set.dataFolder = toSlash(child->GetText());
        else if (tag == "SourceFile" && child->GetText())
            set.inputFile = toSlash(child->GetText());
        else if (tag == "OutputPath" && child->GetText())
            set.outputPath = toSlash(child->GetText());
        else if (tag == "OutputFile" && child->GetText()) {
            set.outputFile = child->GetText();
            set.genWeights = child->BoolAttribute("GenWeights", true);
            set.keepZappedShapes = child->BoolAttribute("KeepZappedShapes", false);
        }
    }

    // Shapes.
    for (XMLElement* child = setElem->FirstChildElement(); child; child = child->NextSiblingElement()) {
        const char* nm = child->Name();
        if (!nm)
            continue;
        std::string tag = nm;
        if (tag != shapeTag && tag != "Shape" && tag != "BaseShapeName")
            continue;
        const char* text = child->GetText();
        if (!text)
            continue;
        ShapeInfo shape;
        shape.shapeName = text;
        shape.targetName = child->Attribute("target") ? child->Attribute("target") : shape.shapeName;
        shape.lockNormals = child->BoolAttribute("LockNormals", false);
        shape.smoothSeamNormals = child->BoolAttribute("SmoothSeamNormals", true);
        shape.smoothSeamNormalsAngle = child->FloatAttribute("SmoothSeamNormalsAngle", 60.0f);
        if (child->Attribute("DataFolder")) {
            shape.dataFolders = splitSemicolon(toSlash(child->Attribute("DataFolder")));
        } else {
            shape.dataFolders.push_back(set.dataFolder);
        }
        set.shapes.push_back(std::move(shape));
    }

    // Sliders.
    for (XMLElement* child = setElem->FirstChildElement(); child; child = child->NextSiblingElement()) {
        const char* nm = child->Name();
        if (!nm || std::string(nm) != "Slider")
            continue;

        SliderInfo slider;
        slider.name = attrText(child, "name");
        if (slider.name.empty())
            continue;
        slider.invert = boolAttr(child, "invert", false);
        slider.zap = boolAttr(child, "zap", false);
        slider.clamp = boolAttr(child, "clamp", false);
        slider.uv = boolAttr(child, "uv", false);
        slider.hidden = boolAttr(child, "hidden", false);
        if (set.genWeights) {
            slider.defBig = floatAttr(child, "big", 0.0f);
            slider.defSmall = floatAttr(child, "small", 0.0f);
        } else {
            slider.defBig = slider.defSmall = floatAttr(child, "default", 0.0f);
        }

        // <Data> children, tolerant of the legacy <datafile> name.
        for (XMLElement* data = child->FirstChildElement(); data; data = data->NextSiblingElement()) {
            const char* dn = data->Name();
            if (!dn)
                continue;
            std::string dtag = dn;
            if (dtag != "Data" && dtag != "datafile")
                continue;
            SliderDataFile df;
            df.targetName = attrText(data, "target");
            df.local = boolAttr(data, "local", false);
            df.dataName = data->Attribute("name") ? data->Attribute("name") : slider.name;
            if (data->GetText())
                df.fileName = toSlash(data->GetText());
            if (!df.fileName.empty())
                slider.dataFiles.push_back(std::move(df));
        }

        // A toggle without data is still shown, but carries no morph of its own.
        set.sliders.push_back(std::move(slider));
    }

    // If the set lists no shapes, derive them from slider targets (target == shape).
    if (set.shapes.empty()) {
        std::set<std::string> seen;
        for (auto& s : set.sliders)
            for (auto& df : s.dataFiles)
                if (!df.targetName.empty() && seen.insert(df.targetName).second) {
                    ShapeInfo shape;
                    shape.shapeName = df.targetName;
                    shape.targetName = df.targetName;
                    shape.dataFolders.push_back(set.dataFolder);
                    set.shapes.push_back(std::move(shape));
                }
    }

    set.ok = true;
    return set;
}

namespace {

// ---------------------------------------------------------------------------
// Diff resolution for a slider data file
// ---------------------------------------------------------------------------

struct ResolvedDataFile {
    bool ok = false;
    bool isBsd = false;
    std::string path;      // absolute file path
    std::string key;       // in-file data name (OSD) or data name (BSD)
    std::string alias;     // df.dataName from the .osp
};

ResolvedDataFile resolveDataFile(const SliderDataFile& df,
                                 const SliderSetInfo& set,
                                 const std::string& dataRoot,
                                 const std::map<std::string, const ShapeInfo*>& shapeByTarget) {
    ResolvedDataFile res;
    if (df.fileName.size() <= 4)
        return res;

    std::vector<std::string> folders;
    if (df.local) {
        folders.push_back(set.dataFolder);
    } else {
        auto it = shapeByTarget.find(df.targetName);
        if (it != shapeByTarget.end() && !it->second->dataFolders.empty())
            folders = it->second->dataFolders;
        else
            folders.push_back(set.dataFolder);
    }

    const bool isBsd = endsWithCI(df.fileName, ".bsd") || endsWithCI(df.fileName, ".bsa");
    std::string filePart = df.fileName;
    std::string dataNameInFile = df.dataName;

    if (!isBsd) {
        size_t split = df.fileName.find_last_of('/');
        if (split == std::string::npos)
            return res;  // OSD paths always contain a folder/name separator
        filePart = df.fileName.substr(0, split);
        dataNameInFile = df.fileName.substr(split + 1);
    }

    for (const std::string& folder : folders) {
        std::string candidate = isBsd ? joinPath(joinPath(dataRoot, folder), df.fileName)
                                      : joinPath(joinPath(dataRoot, folder), filePart);
        const std::string actual = resolvePathCI(candidate);
        if (!actual.empty() && fileExists(actual)) {
            res.ok = true;
            res.isBsd = isBsd;
            res.path = actual;
            res.key = isBsd ? df.dataName : dataNameInFile;
            res.alias = df.dataName;
            return res;
        }
    }

    return res;
}

// ---------------------------------------------------------------------------
// Slider application (port of BodySlideApp::ApplySliders)
// ---------------------------------------------------------------------------

const SliderDataFile* findDataFileForTarget(const SliderInfo& slider, const std::string& target) {
    for (const auto& df : slider.dataFiles)
        if (df.targetName == target)
            return &df;
    return nullptr;
}

float sliderValuePercent(const SliderInfo& slider,
                         const std::map<std::string, float>& values,
                         float fallbackDef) {
    auto it = values.find(slider.name);
    if (it != values.end())
        return it->second;
    return fallbackDef;
}

const std::vector<DiffEntry>* lookupDiffs(const DiffMap& map,
                                          const std::map<std::string, std::string>& aliases,
                                          const std::string& key) {
    auto it = map.find(key);
    if (it != map.end())
        return &it->second;
    auto aliasIt = aliases.find(key);
    if (aliasIt != aliases.end()) {
        auto it2 = map.find(aliasIt->second);
        if (it2 != map.end())
            return &it2->second;
    }
    return nullptr;
}

void applySlidersToNif(nifly::NifFile& nif,
                       const SliderSetInfo& set,
                       const DiffMap& diffs,
                       const std::map<std::string, std::string>& aliases,
                       const std::map<std::string, float>& values,
                       std::vector<std::string>& log) {
    for (const ShapeInfo& shapeInfo : set.shapes) {
        nifly::NiShape* shape = nif.FindBlockByName<nifly::NiShape>(shapeInfo.shapeName);
        if (!shape) {
            log.push_back("Shape not found in NIF: " + shapeInfo.shapeName);
            continue;
        }

        std::vector<nifly::Vector3> verts;
        if (!nif.GetVertsForShape(shape, verts))
            continue;
        std::vector<nifly::Vector2> uvs;
        const bool hasUvs = nif.GetUvsForShape(shape, uvs);

        std::vector<uint16_t> zapIdx;

        // Phase 1: diffs and zaps.
        for (const SliderInfo& slider : set.sliders) {
            const SliderDataFile* df = findDataFileForTarget(slider, shapeInfo.targetName);
            if (!df)
                continue;
            const std::vector<DiffEntry>* entries = lookupDiffs(diffs, aliases, df->dataName);
            if (!entries)
                continue;

            float v = sliderValuePercent(slider, values, slider.defBig) / 100.0f;

            if (slider.zap && !slider.uv) {
                if (v > 0.0f)
                    for (const DiffEntry& e : *entries)
                        if (std::fabs(e.diff.x) > kEpsilon || std::fabs(e.diff.y) > kEpsilon || std::fabs(e.diff.z) > kEpsilon)
                            zapIdx.push_back(e.index);
                continue;
            }

            if (slider.invert)
                v = 1.0f - v;
            if (v == 0.0f)
                continue;

            if (slider.uv) {
                for (const DiffEntry& e : *entries) {
                    if (e.index >= uvs.size())
                        continue;
                    uvs[e.index].u += e.diff.x * v;
                    uvs[e.index].v += e.diff.y * v;
                }
            } else {
                for (const DiffEntry& e : *entries) {
                    if (e.index >= verts.size())
                        continue;
                    verts[e.index].x += e.diff.x * v;
                    verts[e.index].y += e.diff.y * v;
                    verts[e.index].z += e.diff.z * v;
                }
            }
        }

        // Phase 2: clamps.
        for (const SliderInfo& slider : set.sliders) {
            if (!slider.clamp)
                continue;
            float v = sliderValuePercent(slider, values, slider.defBig) / 100.0f;
            if (v <= 0.0f)
                continue;
            const SliderDataFile* df = findDataFileForTarget(slider, shapeInfo.targetName);
            if (!df)
                continue;
            const std::vector<DiffEntry>* entries = lookupDiffs(diffs, aliases, df->dataName);
            if (!entries)
                continue;
            for (const DiffEntry& e : *entries) {
                if (e.index >= verts.size())
                    continue;
                verts[e.index].x = e.diff.x;
                verts[e.index].y = e.diff.y;
                verts[e.index].z = e.diff.z;
            }
        }

        nif.SetVertsForShape(shape, verts);
        if (hasUvs)
            nif.SetUvsForShape(shape, uvs);

        // Match BodySlide: normals are recalculated unless the shape locks them,
        // and force-recalculation stays off (the desktop default).
        if (!shapeInfo.lockNormals)
            nif.CalcNormalsForShape(shape, false, shapeInfo.smoothSeamNormals,
                                    shapeInfo.smoothSeamNormalsAngle);
        nif.CalcTangentsForShape(shape);

        if (!zapIdx.empty()) {
            std::sort(zapIdx.begin(), zapIdx.end());
            zapIdx.erase(std::unique(zapIdx.begin(), zapIdx.end()), zapIdx.end());
            if (set.keepZappedShapes && zapIdx.size() == verts.size()) {
                shape->flags |= 1u;  // hide fully zapped shape
                log.push_back("Hid fully zapped shape: " + shapeInfo.shapeName);
            } else if (nif.DeleteVertsForShape(shape, zapIdx)) {
                nif.DeleteShape(shape);
                log.push_back("Removed fully zapped shape: " + shapeInfo.shapeName);
            } else {
                log.push_back("Zapped " + std::to_string(zapIdx.size()) + " vertices on " + shapeInfo.shapeName);
            }
        }
    }
}

}  // namespace

BuildResult buildOutfit(const BuildRequest& request) {
    BuildResult result;

    SliderSetInfo set = parseSliderSet(request.ospPath, request.setName);
    if (!set.ok) {
        result.error = set.error;
        return result;
    }

    const std::string dataRoot = request.dataRoot.empty() ? dirName(request.ospPath) : request.dataRoot;

    // Map target -> shape (for non-local data folder resolution).
    std::map<std::string, const ShapeInfo*> shapeByTarget;
    for (const ShapeInfo& s : set.shapes)
        shapeByTarget.emplace(s.targetName, &s);

    // Load all referenced diff data.
    DiffMap diffs;
    std::map<std::string, std::string> aliases;
    std::set<std::string> loadedPaths;
    int missingData = 0;

    for (const SliderInfo& slider : set.sliders) {
        for (const SliderDataFile& df : slider.dataFiles) {
            ResolvedDataFile res = resolveDataFile(df, set, dataRoot, shapeByTarget);
            if (!res.ok) {
                ++missingData;
                result.log.push_back("Missing morph data: " + df.fileName + " (target " + df.targetName + ")");
                continue;
            }

            if (res.isBsd) {
                std::string token = res.path + "#" + res.key;
                if (loadedPaths.insert(token).second && !loadBsdFile(res.path, res.key, diffs))
                    result.log.push_back("Failed to read BSD data: " + res.path);
            } else {
                if (loadedPaths.insert(res.path).second && !loadOsdFile(res.path, diffs))
                    result.log.push_back("Failed to read OSD data: " + res.path);
                if (!res.alias.empty() && res.alias != res.key)
                    aliases[res.alias] = res.key;
            }
        }
    }

    // Load the input NIF.
    const std::string inputPath = resolvePathCI(joinPath(joinPath(dataRoot, set.dataFolder), set.inputFile));
    if (inputPath.empty() || !fileExists(inputPath)) {
        result.error = "Input NIF not found: " + joinPath(joinPath(dataRoot, set.dataFolder), set.inputFile);
        return result;
    }

    nifly::NifFile nifBig;
    if (nifBig.Load(inputPath) != 0 || !nifBig.IsValid()) {
        result.error = "Failed to load NIF: " + inputPath;
        return result;
    }

    const bool genWeights = request.genWeights && set.genWeights;

    // Match BodySlide: save without optimizing so unreferenced blocks (nodes,
    // bones, extra data) an outfit relies on are preserved.
    nifly::NifSaveOptions saveOptions;
    saveOptions.optimize = false;

    std::error_code ec;
    std::filesystem::create_directories(request.outputDir, ec);

    std::string outBase = set.outputFile.empty() ? set.name : set.outputFile;
    std::string gameDir = set.outputPath;

    auto sanitizeGamePath = [](const std::string& path) {
        std::string p = path;
        while (!p.empty() && (p.front() == '/' || p.front() == '\\'))
            p.erase(p.begin());
        return p;
    };

    if (genWeights) {
        nifly::NifFile nifSmall = nifBig;

        applySlidersToNif(nifBig, set, diffs, aliases, request.bigValues, result.log);
        applySlidersToNif(nifSmall, set, diffs, aliases, request.smallValues, result.log);

        const std::string bigName = outBase + "_1.nif";    // high weight
        const std::string smallName = outBase + "_0.nif";  // low weight
        const std::string bigPath = joinPath(request.outputDir, bigName);
        const std::string smallPath = joinPath(request.outputDir, smallName);

        if (nifBig.Save(bigPath, saveOptions) != 0) {
            result.error = "Failed to save: " + bigPath;
            return result;
        }
        if (nifSmall.Save(smallPath, saveOptions) != 0) {
            result.error = "Failed to save: " + smallPath;
            return result;
        }

        result.files.push_back({bigPath, sanitizeGamePath(joinPath(gameDir, bigName))});
        result.files.push_back({smallPath, sanitizeGamePath(joinPath(gameDir, smallName))});
    } else {
        applySlidersToNif(nifBig, set, diffs, aliases, request.bigValues, result.log);

        const std::string outName = outBase + ".nif";
        const std::string outPath = joinPath(request.outputDir, outName);
        if (nifBig.Save(outPath, saveOptions) != 0) {
            result.error = "Failed to save: " + outPath;
            return result;
        }
        result.files.push_back({outPath, sanitizeGamePath(joinPath(gameDir, outName))});
    }

    result.shapesProcessed = static_cast<int>(set.shapes.size());
    result.log.insert(result.log.begin(),
                      "Built '" + set.name + "' -> " + std::to_string(result.files.size()) +
                          " NIF(s); " + std::to_string(set.shapes.size()) + " shapes, " +
                          std::to_string(set.sliders.size()) + " sliders.");
    if (missingData > 0)
        result.log.push_back("Warning: " + std::to_string(missingData) + " morph data file(s) not found.");
    if (set.addOn)
        result.log.push_back("Note: this is a SliderSetAddOn; base-project inheritance is not applied.");

    result.ok = true;
    return result;
}

// ---------------------------------------------------------------------------
// JSON serialization
// ---------------------------------------------------------------------------

std::string sliderSetToJson(const SliderSetInfo& set) {
    std::ostringstream out;
    out << "{";
    out << "\"ok\":" << (set.ok ? "true" : "false");
    if (!set.ok) {
        out << ",\"error\":\"" << jsonEscape(set.error) << "\"";
        out << "}";
        return out.str();
    }
    out << ",\"name\":\"" << jsonEscape(set.name) << "\"";
    out << ",\"inputFile\":\"" << jsonEscape(set.inputFile) << "\"";
    out << ",\"outputFile\":\"" << jsonEscape(set.outputFile) << "\"";
    out << ",\"outputPath\":\"" << jsonEscape(set.outputPath) << "\"";
    out << ",\"dataFolder\":\"" << jsonEscape(set.dataFolder) << "\"";
    out << ",\"genWeights\":" << (set.genWeights ? "true" : "false");
    out << ",\"addOn\":" << (set.addOn ? "true" : "false");

    out << ",\"shapes\":[";
    for (size_t i = 0; i < set.shapes.size(); ++i) {
        if (i) out << ",";
        out << "{\"shape\":\"" << jsonEscape(set.shapes[i].shapeName) << "\"";
        out << ",\"target\":\"" << jsonEscape(set.shapes[i].targetName) << "\"";
        out << "}";
    }
    out << "]";

    out << ",\"sliderCount\":" << set.sliders.size();
    out << ",\"sliders\":[";
    for (size_t i = 0; i < set.sliders.size(); ++i) {
        const SliderInfo& s = set.sliders[i];
        if (i) out << ",";
        out << "{\"name\":\"" << jsonEscape(s.name) << "\"";
        out << ",\"zap\":" << (s.zap ? "true" : "false");
        out << ",\"invert\":" << (s.invert ? "true" : "false");
        out << ",\"clamp\":" << (s.clamp ? "true" : "false");
        out << ",\"uv\":" << (s.uv ? "true" : "false");
        out << ",\"hidden\":" << (s.hidden ? "true" : "false");
        out << ",\"dataCount\":" << s.dataFiles.size();
        out << "}";
    }
    out << "]";
    out << "}";
    return out.str();
}

std::string buildResultToJson(const BuildResult& result) {
    std::ostringstream out;
    out << "{";
    out << "\"ok\":" << (result.ok ? "true" : "false");
    if (!result.ok)
        out << ",\"error\":\"" << jsonEscape(result.error) << "\"";
    out << ",\"shapesProcessed\":" << result.shapesProcessed;

    out << ",\"files\":[";
    for (size_t i = 0; i < result.files.size(); ++i) {
        if (i) out << ",";
        out << "{\"stagingPath\":\"" << jsonEscape(result.files[i].stagingPath) << "\"";
        out << ",\"gamePath\":\"" << jsonEscape(result.files[i].gamePath) << "\"}";
    }
    out << "]";

    out << ",\"log\":[";
    for (size_t i = 0; i < result.log.size(); ++i) {
        if (i) out << ",";
        out << "\"" << jsonEscape(result.log[i]) << "\"";
    }
    out << "]";
    out << "}";
    return out.str();
}

}  // namespace bs
