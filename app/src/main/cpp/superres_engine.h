#pragma once

#include <functional>
#include <string>

enum class SuperResNativeCode {
    Ok = 0,
    InputMissing = 1,
    OutputFailed = 2,
    Cancelled = 3,
    Unsupported = 4,
    ModelMissing = 5,
    InvalidParams = 6,
    OutOfMemory = 7,
    VulkanFailed = 8
};

enum class SuperResNativeStage {
    Preparing = 0,
    Analyzing = 1,
    LoadingModel = 2,
    ProcessingTile = 3,
    Stitching = 4,
    Saving = 5,
    Done = 6,
    Failed = 7,
    Cancelled = 8
};

struct SuperResNativeParams {
    std::string taskId;
    std::string inputPath;
    std::string outputPath;
    int engineType;
    std::string modelDir;
    int scale;
    int noise;
    int tileSize;
    int accelerationMode;
    bool tta;
};

using SuperResProgressCallback = std::function<void(
    SuperResNativeStage stage,
    int currentTile,
    int totalTiles,
    float progress,
    const std::string& message
)>;

SuperResNativeCode process_superres(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress
);
void cancel_superres(const std::string& taskId);
