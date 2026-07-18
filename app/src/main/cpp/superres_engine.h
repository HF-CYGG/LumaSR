#pragma once

#include "superres_capability.h"
#include "superres_rgb_image.h"
#include "superres_runtime_control.h"

#include <functional>
#include <cstddef>
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
    TileOutputMismatch = 9,
    PipelineFallbackRequired = 10
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
    int nativeBackend = 0;
    int inferencePrecision = 0;
    int cpuThreads = 0;
    int openmpBlocktimeMs = 20;
    int postprocessThreads = 1;
    int commandBatchSize = 1;
    int modelCacheCapacity = 1;
};

struct SuperResRawTile {
    std::string path;
    int x;
    int y;
    int width;
    int height;
};

struct SuperResExtremePngTile {
    int index;
    int outputX;
    int outputY;
    int outputWidth;
    int outputHeight;
};

// This snapshot is delivered before final-path publication so JNI callback failure cannot leave a
// successful-looking output. saveMs/encodeMs include complete staging-file creation; totalMs ends
// before the callback and atomic rename. Native post-publish logs carry publishMs/actualTotalMs.
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
    long long preprocessMs = 0;
    long long uploadMs = 0;
    long long inferenceMs = 0;
    long long downloadMs = 0;
    long long postprocessMs = 0;
    long long encodeMs = 0;
    int effectiveBackend = 0;
    int effectivePrecision = 0;
    int fallbackReason = -1;
    int requestedMaxPipelineDepth = 1;
    int effectivePipelineDepth = 1;
    int peakOccupiedPipelineSlots = 0;
    long long producerWaitMs = 0;
    long long consumerWaitMs = 0;
    long long pipelineOverlapMs = 0;
    long long peakPipelineSlotBytes = 0;
    bool pipelineCacheHit = false;
    long long pipelineCacheBytes = 0;
    bool pipelineCacheRebuilt = false;
};

using SuperResProgressCallback = std::function<void(
    SuperResNativeStage stage,
    int currentTile,
    int totalTiles,
    float progress,
    const std::string& message,
    const SuperResNativePerformance& performance
)>;

using SuperResRuntimeControlCallback = std::function<SuperResRuntimeInferenceControl()>;

SuperResNativeCode process_superres(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
);
SuperResNativeCode process_superres_pipeline(
    const std::string& rootTaskId,
    const std::vector<SuperResNativeParams>& passes,
    size_t transportBudgetBytes,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
);

// Returns the current ncnn Vulkan heap budget in MiB, or zero when unavailable.
long long read_superres_gpu_heap_budget_mib();
SuperResNativeCode process_superres_rgb(
    const SuperResNativeParams& params,
    SuperResRgbImage image,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
);
SuperResNativeCode process_superres_rgb(
    const SuperResNativeParams& params,
    int width,
    int height,
    const unsigned char* rgb,
    size_t rgbSize,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
);
void cancel_superres(const std::string& taskId);
void clear_superres_cache();
bool configure_superres_pipeline_cache(const std::string& rootDirectory, bool enabled);
SuperResNativeCapabilitySnapshot read_superres_native_capability_snapshot();
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
uint64_t begin_extreme_png_session(
    const std::string& taskId,
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    int maximumBandHeight,
    const std::vector<SuperResExtremePngTile>& tiles
);
SuperResNativeCode process_extreme_png_session_rgb(
    uint64_t handle,
    const SuperResNativeParams& params,
    SuperResRgbImage image,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
);
SuperResNativeCode finish_extreme_png_session(uint64_t handle);
void abort_extreme_png_session(uint64_t handle);
