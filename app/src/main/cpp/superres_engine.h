#pragma once

#include <functional>
#include <string>
#include <vector>

enum class SuperResNativeCode {
    Ok = 0,
    InputMissing = 1,
    OutputFailed = 2,
    Cancelled = 3,
    Unsupported = 4,
    ModelMissing = 5,
    InvalidParams = 6,
    OutOfMemory = 7,
    VulkanFailed = 8,
    TileOutputMismatch = 9
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
    std::string modelFileBase;
    int scale;
    int noise;
    int tileSize;
    int gpuHeadroomPercent;
    int accelerationMode;
    bool tta;
    int outputMode;
    int outputCropLeft;
    int outputCropTop;
    int outputCropWidth;
    int outputCropHeight;
    int retryCount;
    int regionIndex;
};

struct SuperResRawTile {
    std::string path;
    int x;
    int y;
    int width;
    int height;
};

struct SuperResNativePerformance {
    bool hasValue = false;
    long long decodeMs = 0;
    long long modelLoadMs = 0;
    long long tileInputMs = 0;
    long long tileExtractMs = 0;
    long long tileCopyMs = 0;
    long long saveMs = 0;
    long long totalMs = 0;
    bool cacheHit = false;
    int accelerationMode = 0;
    int tileSize = 0;
    int cacheSize = 0;
    int retryCount = 0;
    int regionIndex = -1;
};

using SuperResProgressCallback = std::function<void(
    SuperResNativeStage stage,
    int currentTile,
    int totalTiles,
    float progress,
    const std::string& message,
    const SuperResNativePerformance& performance
)>;

SuperResNativeCode process_superres(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress
);
void cancel_superres(const std::string& taskId);
void clear_superres_cache();
SuperResNativeCode merge_raw_tiles_to_png(
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    const std::vector<SuperResRawTile>& tiles
);
SuperResNativeCode merge_raw_tiles_to_png_streaming(
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    const std::vector<SuperResRawTile>& tiles
);
