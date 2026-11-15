// BodySlide native build core for Skyrim LE (static Android port).
// Ports the .osp / OSD / BSD reading and diff application logic from
// BodySlide-and-Outfit-Studio (DiffData.cpp, SliderSet.cpp, SliderData.cpp,
// BodySlideApp::BuildBodies / ApplySliders) so meshes can be built on device.
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace bs {

// A <Data> record of a <Slider>: which .osd/.bsd file holds the morph and
// which shape (target) it applies to.
struct SliderDataFile {
    std::string dataName;    // data name / alias (attribute "name", defaults to slider name)
    std::string targetName;  // shape the data applies to (attribute "target")
    std::string fileName;    // text of <Data>, e.g. "Future.osd\\BodyBreasts"
    bool local = false;      // attribute "local"
};

struct SliderInfo {
    std::string name;
    bool invert = false;
    bool zap = false;
    bool clamp = false;
    bool uv = false;
    bool hidden = false;
    float defBig = 0.0f;    // attribute "big" (genWeights) or "default"
    float defSmall = 0.0f;  // attribute "small" (genWeights) or "default"
    std::vector<SliderDataFile> dataFiles;
};

// A <Shape> element: NIF shape name -> slider target name + data folders.
struct ShapeInfo {
    std::string shapeName;   // text of <Shape>, the block name in the NIF
    std::string targetName;  // attribute "target", defaults to shapeName
    std::vector<std::string> dataFolders;
    bool lockNormals = false;           // attribute "LockNormals"
    bool smoothSeamNormals = true;      // attribute "SmoothSeamNormals"
    float smoothSeamNormalsAngle = 60.0f;  // attribute "SmoothSeamNormalsAngle"
};

struct SliderSetInfo {
    bool ok = false;
    std::string error;
    std::string name;
    std::string dataFolder;   // <DataFolder>
    std::string inputFile;    // <SourceFile>
    std::string outputPath;   // <OutputPath>
    std::string outputFile;   // <OutputFile>
    bool genWeights = true;   // <OutputFile GenWeights="...">
    bool keepZappedShapes = false;
    bool addOn = false;
    std::vector<ShapeInfo> shapes;
    std::vector<SliderInfo> sliders;
};

struct BuildRequest {
    std::string dataRoot;    // <project>/ShapeData  (baseDataPath)
    std::string ospPath;     // absolute path to the .osp/.xml
    std::string setName;     // SliderSet name
    std::string outputDir;   // staging directory for built NIFs
    bool genWeights = true;
    bool createMorphTri = false;
    // Slider values by name, in percent (0..100).
    std::map<std::string, float> bigValues;
    std::map<std::string, float> smallValues;
};

struct BuiltFile {
    std::string stagingPath;  // absolute path of the built NIF on disk
    std::string gamePath;     // game-data-relative path with '/' separators, e.g. meshes/...
};

struct BuildResult {
    bool ok = false;
    std::string error;
    std::vector<BuiltFile> files;
    std::vector<std::string> log;
    int shapesProcessed = 0;
};

// Parses a slider set from an .osp/.xml file. Robustly finds SliderSet and
// SliderSetAddOn elements at any depth.
SliderSetInfo parseSliderSet(const std::string& ospPath, const std::string& setName);

// Builds the outfit: loads the input NIF, applies all slider diffs, saves the
// resulting NIF(s) into request.outputDir.
BuildResult buildOutfit(const BuildRequest& request);

// Serializes a parsed slider set to JSON (used by nativeParseSliderSet).
std::string sliderSetToJson(const SliderSetInfo& set);

// Serializes a build result to JSON (used by nativeBuildOutfit).
std::string buildResultToJson(const BuildResult& result);

}  // namespace bs
