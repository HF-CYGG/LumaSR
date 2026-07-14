// 这个文件负责在原生层执行基于 ncnn 的超分推理，包括模型选择、图像分块、推理拼接与结果输出。
#include "superres_engine.h"
#include "superres_d4_tta_ncnn.h"
#include "superres_execution_profile.h"
#include "superres_pixel_io.h"
#include "superres_png_writer.h"
#include "superres_png_writer_checks.h"
#include "superres_tile_geometry.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <filesystem>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

#include "net.h"
#include "command.h"
#include "cpu.h"
#include "gpu.h"
#include "mat.h"
#include "zlib.h"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

namespace {
constexpr const char* kLogTag = "LumaSRNative";
constexpr int kEngineWaifu2x = 0;
constexpr int kEngineRealCugan = 1;
constexpr int kEngineRealEsrgan = 2;
constexpr int kAccelerationAuto = 0;
constexpr int kAccelerationVulkan = 1;
constexpr int kNativeOutputPng = 0;
constexpr int kNativeOutputRawCroppedRgbTile = 1;

std::mutex cancel_mutex;
std::mutex gpu_mutex;
std::mutex net_cache_mutex;
std::unordered_set<std::string> cancelled_tasks;
SuperResGpuInitState gpu_init_state = SuperResGpuInitState::Uninitialized;

struct ModelFiles {
    std::string paramPath;
    std::string binPath;
    const char* inputBlob;
    const char* outputBlob;
};

struct LoadedImage {
    int width = 0;
    int height = 0;
    std::vector<unsigned char> rgb;
};

struct NetCacheEntry {
    std::string key;
    std::shared_ptr<ncnn::Net> net;
};

struct LoadedNet {
    std::shared_ptr<ncnn::Net> net;
    bool cacheHit = false;
};

class OutputAssembly {
public:
    SuperResNativeCode initialize(
        const std::string& outputPath,
        int width,
        int height,
        bool croppedRaw,
        int maximumBandHeight
    ) {
        width_ = width;
        height_ = height;
        croppedRaw_ = croppedRaw;
        rowBytes_ = streaming_png_rgb_row_bytes(width);
        if (rowBytes_ == 0 || height <= 0) {
            return SuperResNativeCode::InvalidParams;
        }
        const int allocatedRows = croppedRaw ? height : std::min(height, maximumBandHeight);
        if (allocatedRows <= 0 ||
            static_cast<size_t>(allocatedRows) > std::numeric_limits<size_t>::max() / rowBytes_) {
            return SuperResNativeCode::OutOfMemory;
        }
        try {
            bytes_.resize(rowBytes_ * static_cast<size_t>(allocatedRows));
        } catch (...) {
            return SuperResNativeCode::OutOfMemory;
        }
        if (croppedRaw) {
            return SuperResNativeCode::Ok;
        }
        const auto start = std::chrono::steady_clock::now();
        const StreamingPngStatus status = writer_.open(outputPath, width, height);
        encodingDuration_ += std::chrono::steady_clock::now() - start;
        lastFailure_ = native_code(status);
        return lastFailure_;
    }

    bool prepare_window(int destinationY, int windowHeight) {
        if (croppedRaw_) {
            return destinationY >= 0 && windowHeight > 0 && destinationY <= height_ - windowHeight;
        }
        if (destinationY == bandStartY_) {
            return windowHeight == bandHeight_;
        }
        if (bandStartY_ >= 0 && !flush_band()) {
            return false;
        }
        if (destinationY != writtenRows_ || windowHeight <= 0 ||
            destinationY > height_ - windowHeight ||
            static_cast<size_t>(windowHeight) > bytes_.size() / rowBytes_) {
            return false;
        }
        bandStartY_ = destinationY;
        bandHeight_ = windowHeight;
        std::fill(bytes_.begin(), bytes_.begin() + rowBytes_ * static_cast<size_t>(windowHeight), 0);
        return true;
    }

    unsigned char* row(int destinationY, int destinationX) {
        if (destinationX < 0 || destinationX >= width_) {
            return nullptr;
        }
        const int localY = croppedRaw_ ? destinationY : destinationY - bandStartY_;
        const int availableRows = croppedRaw_ ? height_ : bandHeight_;
        if (localY < 0 || localY >= availableRows) {
            return nullptr;
        }
        return bytes_.data() + static_cast<size_t>(localY) * rowBytes_ +
            static_cast<size_t>(destinationX) * 3u;
    }

    SuperResNativeCode finish_png_temporary() {
        if (croppedRaw_) {
            return SuperResNativeCode::InvalidParams;
        }
        if (!flush_band() || writtenRows_ != height_) {
            return lastFailure_ == SuperResNativeCode::Ok
                ? SuperResNativeCode::TileOutputMismatch
                : lastFailure_;
        }
        const auto start = std::chrono::steady_clock::now();
        const StreamingPngStatus status = writer_.finish_temporary();
        encodingDuration_ += std::chrono::steady_clock::now() - start;
        lastFailure_ = native_code(status);
        return lastFailure_;
    }

    SuperResNativeCode publish_png() {
        if (croppedRaw_) {
            return SuperResNativeCode::InvalidParams;
        }
        const auto start = std::chrono::steady_clock::now();
        const StreamingPngStatus status = writer_.publish();
        encodingDuration_ += std::chrono::steady_clock::now() - start;
        lastFailure_ = native_code(status);
        return lastFailure_;
    }

    void abort_png() {
        writer_.abort();
    }

    const std::vector<unsigned char>& bytes() const {
        return bytes_;
    }

    std::chrono::steady_clock::duration encoding_duration() const {
        return encodingDuration_;
    }

    SuperResNativeCode failure_code() const {
        return lastFailure_;
    }

private:
    bool flush_band() {
        if (bandStartY_ < 0) {
            return true;
        }
        const auto start = std::chrono::steady_clock::now();
        const StreamingPngStatus status = writer_.write_rgb_rows(
            bytes_.data(),
            bandHeight_,
            rowBytes_
        );
        encodingDuration_ += std::chrono::steady_clock::now() - start;
        if (status != StreamingPngStatus::Ok) {
            lastFailure_ = native_code(status);
            return false;
        }
        writtenRows_ += bandHeight_;
        bandStartY_ = -1;
        bandHeight_ = 0;
        return true;
    }

    static SuperResNativeCode native_code(StreamingPngStatus status) {
        switch (status) {
            case StreamingPngStatus::Ok:
                return SuperResNativeCode::Ok;
            case StreamingPngStatus::OutOfMemory:
                return SuperResNativeCode::OutOfMemory;
            case StreamingPngStatus::InvalidParams:
            case StreamingPngStatus::InvalidState:
                return SuperResNativeCode::InvalidParams;
            case StreamingPngStatus::IoError:
                return SuperResNativeCode::OutputFailed;
        }
        return SuperResNativeCode::OutputFailed;
    }

    bool croppedRaw_ = false;
    int width_ = 0;
    int height_ = 0;
    int writtenRows_ = 0;
    int bandStartY_ = -1;
    int bandHeight_ = 0;
    size_t rowBytes_ = 0;
    std::vector<unsigned char> bytes_;
    StreamingPngWriter writer_;
    std::chrono::steady_clock::duration encodingDuration_{};
    SuperResNativeCode lastFailure_ = SuperResNativeCode::Ok;
};

class InvocationVulkanAllocators {
public:
    explicit InvocationVulkanAllocators(const ncnn::VulkanDevice* device)
        : device_(device),
          blob_(device == nullptr ? nullptr : device->acquire_blob_allocator()),
          workspace_(device == nullptr ? nullptr : device->acquire_blob_allocator()),
          staging_(device == nullptr ? nullptr : device->acquire_staging_allocator()) {}

    ~InvocationVulkanAllocators() {
        if (device_ == nullptr) {
            return;
        }
        if (staging_ != nullptr) {
            device_->reclaim_staging_allocator(staging_);
        }
        if (workspace_ != nullptr) {
            device_->reclaim_blob_allocator(workspace_);
        }
        if (blob_ != nullptr) {
            device_->reclaim_blob_allocator(blob_);
        }
    }

    InvocationVulkanAllocators(const InvocationVulkanAllocators&) = delete;
    InvocationVulkanAllocators& operator=(const InvocationVulkanAllocators&) = delete;

    bool valid() const {
        return device_ != nullptr && blob_ != nullptr && workspace_ != nullptr && staging_ != nullptr;
    }

    ncnn::VkAllocator* blob() const {
        return blob_;
    }

    ncnn::VkAllocator* workspace() const {
        return workspace_;
    }

    ncnn::VkAllocator* staging() const {
        return staging_;
    }

    ncnn::Option attach_to(const ncnn::Option& base) const {
        ncnn::Option option = base;
        option.blob_vkallocator = blob_;
        option.workspace_vkallocator = workspace_;
        option.staging_vkallocator = staging_;
        return option;
    }

private:
    const ncnn::VulkanDevice* device_;
    ncnn::VkAllocator* blob_;
    ncnn::VkAllocator* workspace_;
    ncnn::VkAllocator* staging_;
};

struct OptimizedVulkanTileWork {
    int tileX;
    int tileY;
    int tileW;
    int tileH;
    TileInputRegion region;
    ncnn::Mat input;
    ncnn::VkMat uploaded;
    ncnn::VkMat inferred;
    ncnn::Mat downloaded;
};

NetCacheEntry net_cache;
std::vector<NetCacheEntry> net_cache_entries;

long long elapsed_ms(const std::chrono::steady_clock::time_point& start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start
    ).count();
}

bool is_regular_file(const std::string& path) {
    std::error_code error;
    return std::filesystem::is_regular_file(std::filesystem::path(path), error) && !error;
}

const char* acceleration_name(bool useVulkan) {
    return useVulkan ? "vulkan" : "cpu";
}

SuperResNativeBackend native_backend_from_code(int code) {
    return code == static_cast<int>(SuperResNativeBackend::Optimized)
        ? SuperResNativeBackend::Optimized
        : SuperResNativeBackend::LegacySafe;
}

void record_tile_copy_duration(
    SuperResNativePerformance& performance,
    const std::chrono::steady_clock::time_point& start,
    const OutputAssembly& output,
    std::chrono::steady_clock::duration encodingBefore
) {
    const auto elapsed = std::chrono::steady_clock::now() - start;
    const auto encoding = output.encoding_duration() - encodingBefore;
    const auto copyOnly = elapsed > encoding ? elapsed - encoding : std::chrono::steady_clock::duration{};
    performance.tileCopyMs += std::chrono::duration_cast<std::chrono::milliseconds>(copyOnly).count();
}

SuperResInferencePrecision inference_precision_from_code(int code) {
    return code == static_cast<int>(SuperResInferencePrecision::Fp16)
        ? SuperResInferencePrecision::Fp16
        : SuperResInferencePrecision::Fp32;
}

SuperResExecutionProfile resolve_execution_profile(
    const SuperResNativeParams& params,
    bool useVulkan
) {
    bool gpuSupportsFp16Arithmetic = false;
    if (useVulkan) {
        gpuSupportsFp16Arithmetic = ncnn::get_gpu_info(
            ncnn::get_default_gpu_index()
        ).support_fp16_arithmetic();
    }
    return sanitize_execution_profile(
        SuperResExecutionProfileRequest{
            native_backend_from_code(params.nativeBackend),
            inference_precision_from_code(params.inferencePrecision),
            params.cpuThreads,
            params.openmpBlocktimeMs,
            params.commandBatchSize,
            params.modelCacheCapacity
        },
        useVulkan,
        ncnn::get_big_cpu_count(),
        ncnn::get_physical_cpu_count(),
        gpuSupportsFp16Arithmetic
    );
}

bool ensure_gpu_instance() {
    if (!should_attempt_gpu_initialization(gpu_init_state)) {
        return gpu_init_state == SuperResGpuInitState::Available;
    }

    const int createResult = ncnn::create_gpu_instance();
    const int gpuCount = createResult == 0 ? ncnn::get_gpu_count() : 0;
    if (should_destroy_empty_gpu_instance(createResult, gpuCount)) {
        ncnn::destroy_gpu_instance();
    }
    gpu_init_state = transition_gpu_init_state(gpu_init_state, createResult, gpuCount);
    return gpu_init_state == SuperResGpuInitState::Available;
}

std::string make_net_cache_key(
    const ModelFiles& files,
    bool useVulkan,
    const SuperResExecutionProfile& profile
) {
    return files.paramPath + "|" + files.binPath + "|" +
        serialize_execution_cache_identity(useVulkan, profile);
}

void trim_cached_nets_locked(int requestedCapacity) {
    const std::size_t trimCount = cache_entries_to_trim(net_cache_entries.size(), requestedCapacity);
    if (trimCount > 0) {
        net_cache_entries.erase(
            net_cache_entries.begin(),
            net_cache_entries.begin() + static_cast<std::ptrdiff_t>(trimCount)
        );
    }
}

LoadedNet load_cached_net(
    const ModelFiles& files,
    bool useVulkan,
    const SuperResExecutionProfile& profile
) {
    const std::string key = make_net_cache_key(files, useVulkan, profile);
    {
        std::lock_guard<std::mutex> lock(net_cache_mutex);
        trim_cached_nets_locked(profile.modelCacheCapacity);
        for (auto it = net_cache_entries.begin(); it != net_cache_entries.end(); ++it) {
            if (it->key == key && it->net) {
                auto cached = *it;
                net_cache_entries.erase(it);
                net_cache_entries.push_back(cached);
                return LoadedNet{cached.net, true};
            }
        }
    }

    auto net = std::make_shared<ncnn::Net>();
    net->opt.num_threads = profile.cpuThreads;
    net->opt.openmp_blocktime = profile.openmpBlocktimeMs;
    net->opt.use_packing_layout = true;
    net->opt.use_vulkan_compute = useVulkan;
    if (useVulkan) {
        net->opt.use_fp16_packed = true;
        net->opt.use_fp16_storage = true;
        // Legacy-safe remains on FP32 arithmetic; optimized FP16 is gated by the GPU capability check.
        net->opt.use_fp16_arithmetic =
            profile.effectivePrecision == SuperResInferencePrecision::Fp16;
        net->set_vulkan_device(ncnn::get_default_gpu_index());
    }
    if (net->load_param(files.paramPath.c_str()) != 0 || net->load_model(files.binPath.c_str()) != 0) {
        return LoadedNet{};
    }

    std::lock_guard<std::mutex> lock(net_cache_mutex);
    net_cache_entries.erase(
        std::remove_if(
            net_cache_entries.begin(),
            net_cache_entries.end(),
            [&key](const NetCacheEntry& entry) { return entry.key == key; }
        ),
        net_cache_entries.end()
    );
    trim_cached_nets_locked(profile.modelCacheCapacity);
    if (net_cache_entries.size() >= static_cast<std::size_t>(profile.modelCacheCapacity)) {
        net_cache_entries.erase(net_cache_entries.begin());
    }
    net_cache_entries.push_back(NetCacheEntry{key, net});
    return LoadedNet{net_cache_entries.back().net, false};
}

void clear_cached_net() {
    std::lock_guard<std::mutex> lock(net_cache_mutex);
    net_cache = NetCacheEntry{};
    net_cache_entries.clear();
}

int cached_net_count() {
    std::lock_guard<std::mutex> lock(net_cache_mutex);
    return static_cast<int>(net_cache_entries.size());
}

void sleep_for_gpu_headroom(
    int gpuHeadroomPercent,
    const std::chrono::steady_clock::duration& elapsed
) {
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count();
    const long long sleepMs = calculate_gpu_headroom_sleep_ms(gpuHeadroomPercent, elapsedMs);
    if (sleepMs <= 0) {
        return;
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(sleepMs));
}

bool is_cancelled(const std::string& taskId) {
    std::lock_guard<std::mutex> lock(cancel_mutex);
    const auto found = cancelled_tasks.find(taskId);
    if (found == cancelled_tasks.end()) {
        return false;
    }
    cancelled_tasks.erase(found);
    return true;
}

bool consume_cancellation_locked(const std::string& taskId) {
    const auto found = cancelled_tasks.find(taskId);
    if (found == cancelled_tasks.end()) {
        return false;
    }
    cancelled_tasks.erase(found);
    return true;
}

std::string path_basename(const std::string& path) {
    const size_t separator = path.find_last_of("/\\");
    return separator == std::string::npos ? path : path.substr(separator + 1);
}

std::string performance_model_key(const SuperResNativeParams& params) {
    const std::string raw = path_basename(
        params.modelFileBase.empty() ? params.modelDir : params.modelFileBase
    );
    std::string key;
    key.reserve(std::min<size_t>(raw.size(), 64u));
    for (char value : raw) {
        if (key.size() >= 64u) {
            break;
        }
        const bool safe =
            (value >= 'a' && value <= 'z') ||
            (value >= 'A' && value <= 'Z') ||
            (value >= '0' && value <= '9') ||
            value == '.' || value == '_' || value == '-';
        key.push_back(safe ? value : '_');
    }
    return key.empty() ? "unknown" : key;
}

std::string join_path(const std::string& dir, const std::string& name) {
    if (dir.empty()) {
        return name;
    }
    const char last = dir[dir.size() - 1];
    if (last == '/' || last == '\\') {
        return dir + name;
    }
    return dir + "/" + name;
}

std::string waifu2x_base_name(int scale, int noise) {
    if (noise < 0) {
        return "scale" + std::to_string(scale) + ".0x_model";
    }
    if (scale <= 1) {
        return "noise" + std::to_string(noise) + "_model";
    }
    return "noise" + std::to_string(noise) + "_scale" + std::to_string(scale) + ".0x_model";
}

std::string realcugan_base_name(int scale, int noise) {
    if (noise < 0) {
        return "up" + std::to_string(scale) + "x-conservative";
    }
    if (noise == 0) {
        return "up" + std::to_string(scale) + "x-no-denoise";
    }
    return "up" + std::to_string(scale) + "x-denoise" + std::to_string(noise) + "x";
}

std::vector<std::string> realcugan_base_name_candidates(int scale, int noise) {
    std::vector<std::string> candidates;
    candidates.push_back(realcugan_base_name(scale, noise));
    if (noise > 0 && noise != 3) {
        candidates.push_back(realcugan_base_name(scale, 3));
    }
    if (noise != 0) {
        candidates.push_back(realcugan_base_name(scale, 0));
    }
    if (noise >= 0) {
        candidates.push_back(realcugan_base_name(scale, -1));
    }

    std::vector<std::string> unique;
    for (const std::string& candidate : candidates) {
        if (std::find(unique.begin(), unique.end(), candidate) == unique.end()) {
            unique.push_back(candidate);
        }
    }
    return unique;
}

ModelFiles select_model_files(const SuperResNativeParams& params) {
    if (params.engineType == kEngineRealEsrgan) {
        return ModelFiles{
            join_path(params.modelDir, params.modelFileBase + ".param"),
            join_path(params.modelDir, params.modelFileBase + ".bin"),
            "data",
            "output"
        };
    }

    const bool realcugan = params.engineType == kEngineRealCugan;
    if (realcugan) {
        const std::vector<std::string> candidates = realcugan_base_name_candidates(params.scale, params.noise);
        for (const std::string& candidate : candidates) {
            ModelFiles files{
                join_path(params.modelDir, candidate + ".param"),
                join_path(params.modelDir, candidate + ".bin"),
                "in0",
                "out0"
            };
            if (is_regular_file(files.paramPath) && is_regular_file(files.binPath)) {
                return files;
            }
        }
        const std::string fallback = candidates.empty() ? realcugan_base_name(params.scale, params.noise) : candidates.front();
        return ModelFiles{
            join_path(params.modelDir, fallback + ".param"),
            join_path(params.modelDir, fallback + ".bin"),
            "in0",
            "out0"
        };
    }

    const std::string baseName = waifu2x_base_name(params.scale, params.noise);
    return ModelFiles{
        join_path(params.modelDir, baseName + ".param"),
        join_path(params.modelDir, baseName + ".bin"),
        "Input1",
        "Eltwise4"
    };
}

LoadedImage load_rgb_image(const std::string& path) {
    LoadedImage image;
    int channels = 0;
    unsigned char* pixels = stbi_load(path.c_str(), &image.width, &image.height, &channels, 3);
    if (pixels == nullptr || image.width <= 0 || image.height <= 0) {
        if (pixels != nullptr) {
            stbi_image_free(pixels);
        }
        return image;
    }

    const size_t byteCount = static_cast<size_t>(image.width) * image.height * 3;
    image.rgb.assign(pixels, pixels + byteCount);
    stbi_image_free(pixels);
    return image;
}

void emit(
    const SuperResProgressCallback& onProgress,
    SuperResNativeStage stage,
    int currentTile,
    int totalTiles,
    float progress,
    const std::string& message,
    const SuperResNativePerformance& performance = SuperResNativePerformance{}
) {
    if (onProgress) {
        onProgress(stage, currentTile, totalTiles, progress, message, performance);
    }
}

int waifu2x_prepadding(const SuperResNativeParams& params) {
    if (params.engineType != kEngineWaifu2x) {
        return 0;
    }
    if (is_waifu2x_cunet_dir(params.modelDir.c_str())) {
        if (params.noise < 0) {
            return 18;
        }
        if (params.scale == 1) {
            return 28;
        }
        if (params.scale == 2) {
            return 18;
        }
        return 18;
    }
    if (is_waifu2x_upconv_dir(params.modelDir.c_str())) {
        return 7;
    }
    return 0;
}

int realcugan_prepadding(const SuperResNativeParams& params) {
    if (params.engineType != kEngineRealCugan) {
        return 0;
    }
    if (params.scale == 2) {
        return 18;
    }
    if (params.scale == 3) {
        return 14;
    }
    if (params.scale == 4) {
        return 19;
    }
    return 0;
}

TileInputRegion make_waifu2x_tile_region(
    const SuperResNativeParams& params,
    int imageW,
    int imageH,
    int tileX,
    int tileY,
    int tileW,
    int tileH
) {
    return ::make_waifu2x_tile_region(
        params.scale,
        waifu2x_prepadding(params),
        imageW,
        imageH,
        tileX,
        tileY,
        tileW,
        tileH
    );
}

TileInputRegion make_realcugan_tile_region(
    const SuperResNativeParams& params,
    int imageW,
    int imageH,
    int tileX,
    int tileY,
    int tileW,
    int tileH
) {
    return ::make_realcugan_tile_region(
        params.scale,
        realcugan_prepadding(params),
        imageW,
        imageH,
        tileX,
        tileY,
        tileW,
        tileH
    );
}

TileInputRegion make_overlapped_tile_region(
    int imageW,
    int imageH,
    int tileX,
    int tileY,
    int tileW,
    int tileH,
    int tileSize,
    int tileOverlap,
    int scale
) {
    const int sampleX = std::max(0, tileX - tileOverlap);
    const int sampleY = std::max(0, tileY - tileOverlap);
    const int sampleRight = std::min(imageW, tileX + tileW + tileOverlap);
    const int sampleBottom = std::min(imageH, tileY + tileH + tileOverlap);
    const int sampleW = sampleRight - sampleX;
    const int sampleH = sampleBottom - sampleY;
    return TileInputRegion{
        sampleX,
        sampleY,
        std::max(tileSize, sampleW),
        std::max(tileSize, sampleH),
        (tileX - sampleX) * scale,
        (tileY - sampleY) * scale,
        tileX * scale,
        tileY * scale,
        tileW * scale,
        tileH * scale
    };
}

ncnn::Mat make_input_tile(
    const LoadedImage& image,
    int tileX,
    int tileY,
    int inputW,
    int inputH
) {
    ncnn::Mat tile(inputW, inputH, 3, sizeof(float));
    if (tile.empty()) {
        return tile;
    }
    ncnn::Mat redChannel = tile.channel(0);
    ncnn::Mat greenChannel = tile.channel(1);
    ncnn::Mat blueChannel = tile.channel(2);
    for (int y = 0; y < inputH; ++y) {
        const int sourceY = std::min(image.height - 1, std::max(0, tileY + y));
        const unsigned char* const sourceRow =
            image.rgb.data() + static_cast<size_t>(sourceY) * image.width * 3;
        if (!copy_clamped_rgb8_row_to_planar(
                sourceRow,
                image.width,
                tileX,
                inputW,
                redChannel.row(y),
                greenChannel.row(y),
                blueChannel.row(y)
            )) {
            return ncnn::Mat{};
        }
    }
    return tile;
}

bool copy_output_tile(
    const ncnn::Mat& tile,
    OutputAssembly& output,
    long long& coveredPixels,
    const LoadedImage* residualBaseImage,
    int bufferW,
    int bufferH,
    int fullOutputW,
    int fullOutputH,
    int srcX,
    int srcY,
    int dstX,
    int dstY,
    int requestedCopyW,
    int requestedCopyH,
    bool useCroppedOutput,
    int cropLeft,
    int cropTop,
    int cropWidth,
    int cropHeight,
    bool useOriginOutputWindow,
    bool addRealCugan4xResidualBase
) {
    if (tile.empty() || tile.elempack != 1 || tile.elembits() != 32 ||
        !tile_output_has_rgb_channels(tile.c, tile.elempack)) {
        return false;
    }

    if (useOriginOutputWindow) {
        srcX = resolve_waifu2x_output_crop(tile.w, requestedCopyW);
        srcY = resolve_waifu2x_output_crop(tile.h, requestedCopyH);
        if (srcX < 0 || srcY < 0) {
            return false;
        }
    }

    if (srcX < 0 || srcY < 0 || dstX < 0 || dstY < 0 ||
        requestedCopyW <= 0 || requestedCopyH <= 0 ||
        srcX + requestedCopyW > tile.w ||
        srcY + requestedCopyH > tile.h ||
        dstX + requestedCopyW > fullOutputW ||
        dstY + requestedCopyH > fullOutputH) {
        return false;
    }

    CroppedCopyWindow window{
        true,
        srcX,
        srcY,
        dstX,
        dstY,
        requestedCopyW,
        requestedCopyH
    };
    if (useCroppedOutput) {
        window = resolve_cropped_copy_window(
            srcX,
            srcY,
            dstX,
            dstY,
            requestedCopyW,
            requestedCopyH,
            cropLeft,
            cropTop,
            cropWidth,
            cropHeight
        );
        if (!window.valid) {
            return true;
        }
    }
    if (window.dstX < 0 || window.dstY < 0 ||
        window.copyW <= 0 || window.copyH <= 0 ||
        window.dstX + window.copyW > bufferW ||
        window.dstY + window.copyH > bufferH) {
        return false;
    }
    if (addRealCugan4xResidualBase &&
        (residualBaseImage == nullptr || residualBaseImage->rgb.empty() ||
            residualBaseImage->width <= 0 || residualBaseImage->height <= 0)) {
        return false;
    }

    const int fullDstBaseX = useCroppedOutput ? cropLeft + window.dstX : window.dstX;
    const int fullDstBaseY = useCroppedOutput ? cropTop + window.dstY : window.dstY;

    const ncnn::Mat redChannel = tile.channel(0);
    const ncnn::Mat greenChannel = tile.channel(1);
    const ncnn::Mat blueChannel = tile.channel(2);
    if (!output.prepare_window(window.dstY, window.copyH)) {
        return false;
    }
    for (int y = 0; y < window.copyH; ++y) {
        unsigned char* const destinationRow = output.row(window.dstY + y, window.dstX);
        if (destinationRow == nullptr) {
            return false;
        }
        const unsigned char* residualRow = nullptr;
        int residualWidth = 0;
        if (addRealCugan4xResidualBase) {
            const int baseY = realcugan_4x_residual_source_coord(
                fullDstBaseY + y,
                residualBaseImage->height
            );
            residualWidth = residualBaseImage->width;
            residualRow = residualBaseImage->rgb.data() +
                static_cast<size_t>(baseY) * residualWidth * 3;
        }
        if (!copy_planar_float_row_to_rgb8(
                redChannel.row(window.srcY + y) + window.srcX,
                greenChannel.row(window.srcY + y) + window.srcX,
                blueChannel.row(window.srcY + y) + window.srcX,
                destinationRow,
                window.copyW,
                residualRow,
                residualWidth,
                fullDstBaseX
            )) {
            return false;
        }
    }
    coveredPixels += static_cast<long long>(window.copyW) * window.copyH;
    return true;
}

bool write_cropped_raw_rgb_tile(
    const std::string& outputPath,
    const std::vector<unsigned char>& output,
    int cropWidth,
    int cropHeight
) {
    if (outputPath.empty() || cropWidth <= 0 || cropHeight <= 0) {
        return false;
    }
    const size_t expectedBytes = static_cast<size_t>(cropWidth) * cropHeight * 3;
    if (output.size() < expectedBytes) {
        return false;
    }

    FILE* file = std::fopen(outputPath.c_str(), "wb");
    if (file == nullptr) {
        return false;
    }
    bool ok = std::fwrite(output.data(), 1, expectedBytes, file) == expectedBytes;
    ok = std::fclose(file) == 0 && ok;
    return ok;
}

bool normalize_output_tile_for_copy(const ncnn::Mat& source, ncnn::Mat& normalized, long long& tileCopyMs) {
    const auto normalizeStart = std::chrono::steady_clock::now();
    if (source.empty()) {
        normalized = source;
        tileCopyMs += elapsed_ms(normalizeStart);
        return false;
    }

    ncnn::Option opt;
    opt.num_threads = 1;

    ncnn::Mat current = source;
    ncnn::Mat fp32;
    if (tile_output_needs_fp32_conversion(static_cast<int>(current.elemsize), current.elempack)) {
        ncnn::cast_float16_to_float32(current, fp32, opt);
        if (fp32.empty()) {
            tileCopyMs += elapsed_ms(normalizeStart);
            return false;
        }
        current = fp32;
    } else if (current.elembits() != 32) {
        return false;
    }

    ncnn::Mat unpacked;
    if (tile_output_needs_unpacking(current.c, current.elempack)) {
        ncnn::convert_packing(current, unpacked, 1, opt);
        if (unpacked.empty()) {
            tileCopyMs += elapsed_ms(normalizeStart);
            return false;
        }
        current = unpacked;
    }

    normalized = current;
    tileCopyMs += elapsed_ms(normalizeStart);
    return !normalized.empty() &&
        normalized.elempack == 1 &&
        normalized.elembits() == 32 &&
        tile_output_has_rgb_channels(normalized.c, normalized.elempack);
}

void log_tile_output_mismatch(
    const SuperResNativeParams& params,
    bool useVulkan,
    const char* reason,
    int tileX,
    int tileY,
    int tileW,
    int tileH,
    const TileInputRegion& region,
    const ncnn::Mat& raw,
    const ncnn::Mat& normalized
) {
    __android_log_print(
        ANDROID_LOG_WARN,
        kLogTag,
        "TileOutputMismatch reason=%s engine=%d scale=%d noise=%d accel=%s tile=%d,%d %dx%d inputRegion=%d,%d %dx%d copySrc=%d,%d dst=%d,%d copy=%dx%d raw=%dx%d c=%d pack=%d bits=%d norm=%dx%d c=%d pack=%d bits=%d",
        reason,
        params.engineType,
        params.scale,
        params.noise,
        acceleration_name(useVulkan),
        tileX,
        tileY,
        tileW,
        tileH,
        region.inputX,
        region.inputY,
        region.inputW,
        region.inputH,
        region.cropX,
        region.cropY,
        region.dstX,
        region.dstY,
        region.copyW,
        region.copyH,
        raw.w,
        raw.h,
        raw.c,
        raw.elempack,
        raw.elembits(),
        normalized.w,
        normalized.h,
        normalized.c,
        normalized.elempack,
        normalized.elembits()
    );
}

SuperResNativeCode run_optimized_vulkan_tiles(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const LoadedImage& image,
    const ModelFiles& files,
    ncnn::Net& net,
    const SuperResExecutionProfile& executionProfile,
    int tileSize,
    int tileOverlap,
    int tilesX,
    int tilesY,
    int bufferW,
    int bufferH,
    int outputW,
    int outputH,
    bool useCroppedOutput,
    OutputAssembly& output,
    long long& coveredPixels,
    int& completed,
    SuperResNativePerformance& performance
) {
    const ncnn::VulkanDevice* device = net.vulkan_device();
    InvocationVulkanAllocators allocators(device);
    if (!allocators.valid()) {
        return SuperResNativeCode::VulkanFailed;
    }
    const ncnn::Option executionOption = allocators.attach_to(net.opt);
    const int totalTiles = tilesX * tilesY;
    std::chrono::steady_clock::duration uploadDuration{};
    std::chrono::steady_clock::duration inferenceDuration{};
    std::chrono::steady_clock::duration downloadDuration{};
    int firstTile = 0;

    while (firstTile < totalTiles) {
        const bool cancellationRequested = is_cancelled(params.taskId);
        const SuperResCommandBatchPlan plan = plan_command_batch(
            totalTiles,
            executionProfile.commandBatchSize,
            firstTile,
            cancellationRequested
        );
        if (!plan.shouldRecord) {
            return cancellationRequested ? SuperResNativeCode::Cancelled : SuperResNativeCode::Ok;
        }

        const auto batchStart = std::chrono::steady_clock::now();
        std::vector<OptimizedVulkanTileWork> batch;
        std::vector<std::unique_ptr<ncnn::Extractor>> extractors;
        batch.reserve(static_cast<size_t>(plan.tileCount));
        extractors.reserve(static_cast<size_t>(plan.tileCount));
        ncnn::VkCompute command(device);

        for (int offset = 0; offset < plan.tileCount; ++offset) {
            const int tileIndex = plan.firstTile + offset;
            const int tx = tileIndex % tilesX;
            const int ty = tileIndex / tilesX;
            const int tileX = tx * tileSize;
            const int tileY = ty * tileSize;
            const int tileW = std::min(tileSize, image.width - tileX);
            const int tileH = std::min(tileSize, image.height - tileY);
            const TileInputRegion region = params.engineType == kEngineWaifu2x
                ? make_waifu2x_tile_region(params, image.width, image.height, tileX, tileY, tileW, tileH)
                : params.engineType == kEngineRealCugan
                    ? make_realcugan_tile_region(params, image.width, image.height, tileX, tileY, tileW, tileH)
                    : make_overlapped_tile_region(
                        image.width,
                        image.height,
                        tileX,
                        tileY,
                        tileW,
                        tileH,
                        tileSize,
                        tileOverlap,
                        params.scale
                    );

            const auto inputStart = std::chrono::steady_clock::now();
            ncnn::Mat input = make_input_tile(
                image,
                region.inputX,
                region.inputY,
                region.inputW,
                region.inputH
            );
            performance.tileInputMs += elapsed_ms(inputStart);
            batch.push_back(OptimizedVulkanTileWork{
                tileX,
                tileY,
                tileW,
                tileH,
                region,
                input,
                ncnn::VkMat{},
                ncnn::VkMat{},
                ncnn::Mat{}
            });
            OptimizedVulkanTileWork& work = batch.back();

            const auto uploadStart = std::chrono::steady_clock::now();
            command.record_upload(work.input, work.uploaded, executionOption);
            uploadDuration += std::chrono::steady_clock::now() - uploadStart;
            if (work.uploaded.empty()) {
                return SuperResNativeCode::VulkanFailed;
            }

            auto extractor = std::make_unique<ncnn::Extractor>(net.create_extractor());
            extractor->set_blob_vkallocator(allocators.blob());
            extractor->set_workspace_vkallocator(allocators.workspace());
            extractor->set_staging_vkallocator(allocators.staging());
            const auto inferenceStart = std::chrono::steady_clock::now();
            const bool inferenceFailed =
                extractor->input(files.inputBlob, work.uploaded) != 0 ||
                extractor->extract(files.outputBlob, work.inferred, command) != 0;
            inferenceDuration += std::chrono::steady_clock::now() - inferenceStart;
            if (inferenceFailed) {
                return SuperResNativeCode::VulkanFailed;
            }
            if (work.inferred.empty()) {
                return SuperResNativeCode::VulkanFailed;
            }
            extractors.push_back(std::move(extractor));

            const auto downloadStart = std::chrono::steady_clock::now();
            command.record_download(work.inferred, work.downloaded, executionOption);
            downloadDuration += std::chrono::steady_clock::now() - downloadStart;
        }

        // ncnn exposes one synchronization result for the complete command buffer; keep it in inference time.
        const auto submitStart = std::chrono::steady_clock::now();
        const int submitResult = command.submit_and_wait();
        inferenceDuration += std::chrono::steady_clock::now() - submitStart;
        if (submitResult != 0) {
            return SuperResNativeCode::VulkanFailed;
        }

        if (is_cancelled(params.taskId)) {
            return SuperResNativeCode::Cancelled;
        }

        for (size_t index = 0; index < batch.size(); ++index) {
            if (index > 0 && is_cancelled(params.taskId)) {
                return SuperResNativeCode::Cancelled;
            }
            OptimizedVulkanTileWork& work = batch[index];
            if (work.downloaded.empty()) {
                return SuperResNativeCode::VulkanFailed;
            }

            ncnn::Mat copySource;
            if (!normalize_output_tile_for_copy(work.downloaded, copySource, performance.tileCopyMs)) {
                log_tile_output_mismatch(
                    params,
                    true,
                    "optimized-vulkan-normalize",
                    work.tileX,
                    work.tileY,
                    work.tileW,
                    work.tileH,
                    work.region,
                    work.downloaded,
                    copySource
                );
                return SuperResNativeCode::TileOutputMismatch;
            }

            const auto copyStart = std::chrono::steady_clock::now();
            const auto encodingBefore = output.encoding_duration();
            if (!copy_output_tile(
                    copySource,
                    output,
                    coveredPixels,
                    params.engineType == kEngineRealCugan && params.scale == 4 ? &image : nullptr,
                    bufferW,
                    bufferH,
                    outputW,
                    outputH,
                    work.region.cropX,
                    work.region.cropY,
                    work.region.dstX,
                    work.region.dstY,
                    work.region.copyW,
                    work.region.copyH,
                    useCroppedOutput,
                    params.outputCropLeft,
                    params.outputCropTop,
                    params.outputCropWidth,
                    params.outputCropHeight,
                    params.engineType == kEngineWaifu2x || params.engineType == kEngineRealCugan,
                    params.engineType == kEngineRealCugan && params.scale == 4
                )) {
                record_tile_copy_duration(performance, copyStart, output, encodingBefore);
                if (output.failure_code() != SuperResNativeCode::Ok) {
                    return output.failure_code();
                }
                log_tile_output_mismatch(
                    params,
                    true,
                    "optimized-vulkan-copy",
                    work.tileX,
                    work.tileY,
                    work.tileW,
                    work.tileH,
                    work.region,
                    work.downloaded,
                    copySource
                );
                return SuperResNativeCode::TileOutputMismatch;
            }
            record_tile_copy_duration(performance, copyStart, output, encodingBefore);

            completed += 1;
            const float progress = 0.12f + 0.78f * (static_cast<float>(completed) / totalTiles);
            emit(
                onProgress,
                SuperResNativeStage::ProcessingTile,
                completed,
                totalTiles,
                progress,
                "Processing tile " + std::to_string(completed) + "/" + std::to_string(totalTiles)
            );
        }

        firstTile += plan.tileCount;
        sleep_for_gpu_headroom(
            params.gpuHeadroomPercent,
            std::chrono::steady_clock::now() - batchStart
        );
        if (is_cancelled(params.taskId)) {
            return SuperResNativeCode::Cancelled;
        }
    }

    // Convert once after all batches so sub-millisecond record calls accumulate instead of truncating per tile.
    performance.uploadMs = std::chrono::duration_cast<std::chrono::milliseconds>(uploadDuration).count();
    performance.inferenceMs = std::chrono::duration_cast<std::chrono::milliseconds>(inferenceDuration).count();
    performance.downloadMs = std::chrono::duration_cast<std::chrono::milliseconds>(downloadDuration).count();
    return SuperResNativeCode::Ok;
}

SuperResNativeCode run_ncnn_image(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    bool useVulkan,
    const LoadedImage& image,
    long long decodeMs
) {
    const auto totalStart = std::chrono::steady_clock::now();
    static const bool executionProfileChecksPass = run_execution_profile_runtime_checks();
    if (!executionProfileChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Execution profile runtime checks failed");
        return SuperResNativeCode::InvalidParams;
    }
    static const bool pixelIoChecksPass = run_pixel_io_runtime_checks();
    if (!pixelIoChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Pixel I/O runtime checks failed");
        return SuperResNativeCode::Unsupported;
    }
#ifndef NDEBUG
    static const bool streamingPngChecksPass = run_streaming_png_writer_runtime_checks(
        params.outputPath.c_str()
    );
    if (!streamingPngChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Streaming PNG writer runtime checks failed");
        return SuperResNativeCode::Unsupported;
    }
#endif
    SuperResNativePerformance performance;
    performance.accelerationMode = useVulkan ? kAccelerationVulkan : 2;
    performance.decodeMs = decodeMs;

    const ModelFiles files = select_model_files(params);
    if (!is_regular_file(files.paramPath) || !is_regular_file(files.binPath)) {
        return SuperResNativeCode::ModelMissing;
    }
    if (!useVulkan &&
        params.engineType == kEngineRealEsrgan &&
        is_large_realesrgan_model_base(params.modelFileBase.c_str())) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Rejecting CPU load for large Real-ESRGAN model to avoid process OOM model=%s scale=%d",
            params.modelFileBase.c_str(),
            params.scale
        );
        return SuperResNativeCode::OutOfMemory;
    }

    if (image.rgb.empty()) {
        return SuperResNativeCode::Unsupported;
    }

    if (params.tta) {
        static const bool d4RuntimeChecksPass = run_d4_tta_runtime_checks();
        if (!d4RuntimeChecksPass) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "D4 TTA runtime checks failed");
            return SuperResNativeCode::TileOutputMismatch;
        }
    }

    if (is_cancelled(params.taskId)) {
        return SuperResNativeCode::Cancelled;
    }

    if (!output_pixels_within_budget(image.width, image.height, params.scale)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Rejecting output allocation image=%dx%d scale=%d maxPixels=%lld",
            image.width,
            image.height,
            params.scale,
            kMaxOutputPixels
        );
        return SuperResNativeCode::OutOfMemory;
    }

    std::unique_lock<std::mutex> gpuLock(gpu_mutex, std::defer_lock);
    if (useVulkan) {
        gpuLock.lock();
        // ncnn owns a process-wide Vulkan instance, so GPU inference is serialized and the instance is reused.
        if (!ensure_gpu_instance()) {
            return SuperResNativeCode::VulkanFailed;
        }
    }

    emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "Loading ncnn model");
    const SuperResExecutionProfile executionProfile = resolve_execution_profile(params, useVulkan);
    performance.effectiveBackend = static_cast<int>(executionProfile.effectiveBackend);
    performance.effectivePrecision = static_cast<int>(executionProfile.effectivePrecision);
    performance.fallbackReason = static_cast<int>(executionProfile.fallbackReason);
    const auto modelLoadStart = std::chrono::steady_clock::now();
    LoadedNet loadedNet = load_cached_net(files, useVulkan, executionProfile);
    performance.modelLoadMs = elapsed_ms(modelLoadStart);
    performance.cacheHit = loadedNet.cacheHit;
    performance.cacheSize = cached_net_count();
    performance.retryCount = params.retryCount;
    performance.regionIndex = params.regionIndex;
    std::shared_ptr<ncnn::Net> net = loadedNet.net;
    if (!net) {
        return SuperResNativeCode::ModelMissing;
    }

    const int tileSize = std::max(32, params.tileSize);
    if (!safe_mul_leq(tileSize, params.scale, kMaxIntDimension)) {
        return SuperResNativeCode::InvalidParams;
    }
    const int maximumBandHeight = static_cast<int>(
        static_cast<long long>(tileSize) * params.scale
    );
    performance.tileSize = tileSize;
    const int tileOverlap = std::min(32, std::max(8, tileSize / 4));
    const int tilesX = 1 + (image.width - 1) / tileSize;
    const int tilesY = 1 + (image.height - 1) / tileSize;
    const int totalTiles = tilesX * tilesY;
    const int outputW = image.width * params.scale;
    const int outputH = image.height * params.scale;
    const bool useCroppedOutput = params.outputMode == kNativeOutputRawCroppedRgbTile;
    if (useCroppedOutput &&
        (params.outputCropLeft < 0 || params.outputCropTop < 0 ||
            params.outputCropWidth <= 0 || params.outputCropHeight <= 0 ||
            params.outputCropLeft > outputW || params.outputCropTop > outputH ||
            params.outputCropWidth > outputW - params.outputCropLeft ||
            params.outputCropHeight > outputH - params.outputCropTop)) {
        return SuperResNativeCode::InvalidParams;
    }
    const int bufferW = useCroppedOutput ? params.outputCropWidth : outputW;
    const int bufferH = useCroppedOutput ? params.outputCropHeight : outputH;

    OutputAssembly output;
    const SuperResNativeCode outputInitialization = output.initialize(
            params.outputPath,
            bufferW,
            bufferH,
            useCroppedOutput,
            maximumBandHeight
        );
    if (outputInitialization != SuperResNativeCode::Ok) {
        return outputInitialization;
    }

    int completed = 0;
    long long coveredPixels = 0;
    const bool useExplicitVulkanPath =
        should_use_optimized_vulkan_path(executionProfile.effectiveBackend, useVulkan) &&
        !params.tta;
    if (useExplicitVulkanPath) {
        const SuperResNativeCode optimizedCode = run_optimized_vulkan_tiles(
            params,
            onProgress,
            image,
            files,
            *net,
            executionProfile,
            tileSize,
            tileOverlap,
            tilesX,
            tilesY,
            bufferW,
            bufferH,
            outputW,
            outputH,
            useCroppedOutput,
            output,
            coveredPixels,
            completed,
            performance
        );
        if (optimizedCode != SuperResNativeCode::Ok) {
            return optimizedCode;
        }
        performance.tileExtractMs = performance.inferenceMs;
    } else {
    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            if (is_cancelled(params.taskId)) {
                return SuperResNativeCode::Cancelled;
            }

            const int tileX = tx * tileSize;
            const int tileY = ty * tileSize;
            const int tileW = std::min(tileSize, image.width - tileX);
            const int tileH = std::min(tileSize, image.height - tileY);
            const TileInputRegion region = params.engineType == kEngineWaifu2x
                ? make_waifu2x_tile_region(params, image.width, image.height, tileX, tileY, tileW, tileH)
                : params.engineType == kEngineRealCugan
                    ? make_realcugan_tile_region(params, image.width, image.height, tileX, tileY, tileW, tileH)
                    : make_overlapped_tile_region(
                        image.width,
                        image.height,
                        tileX,
                        tileY,
                        tileW,
                        tileH,
                        tileSize,
                        tileOverlap,
                        params.scale
                    );
            const auto inputStart = std::chrono::steady_clock::now();
            ncnn::Mat input = make_input_tile(image, region.inputX, region.inputY, region.inputW, region.inputH);
            performance.tileInputMs += elapsed_ms(inputStart);
            ncnn::Mat result;
            ncnn::Mat copySource;
            const auto tileStart = std::chrono::steady_clock::now();

            if (!params.tta) {
                ncnn::Extractor extractor = net->create_extractor();
                const auto extractStart = std::chrono::steady_clock::now();
                if (extractor.input(files.inputBlob, input) != 0 ||
                    extractor.extract(files.outputBlob, result) != 0) {
                    return useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Unsupported;
                }
                performance.tileExtractMs += elapsed_ms(extractStart);
                if (!normalize_output_tile_for_copy(result, copySource, performance.tileCopyMs)) {
                    log_tile_output_mismatch(
                        params,
                        useVulkan,
                        "normalize",
                        tileX,
                        tileY,
                        tileW,
                        tileH,
                        region,
                        result,
                        copySource
                    );
                    return SuperResNativeCode::TileOutputMismatch;
                }
            } else {
                // Fusion stays in canonical float space; crop/copy and the RealCUGAN x4 residual run only after all passes succeed.
                D4FloatFusion fusion;
                for (size_t pass = 0; pass < kD4Transforms.size(); ++pass) {
                    const D4Transform transform = kD4Transforms[pass];
                    ncnn::Mat transformedInput;
                    if (!d4_transform_ncnn_mat(input, transform, transformedInput)) {
                        log_tile_output_mismatch(
                            params,
                            useVulkan,
                            "tta-input-transform",
                            tileX,
                            tileY,
                            tileW,
                            tileH,
                            region,
                            transformedInput,
                            ncnn::Mat{}
                        );
                        return SuperResNativeCode::TileOutputMismatch;
                    }

                    ncnn::Extractor extractor = net->create_extractor();
                    ncnn::Mat passResult;
                    const auto extractStart = std::chrono::steady_clock::now();
                    if (extractor.input(files.inputBlob, transformedInput) != 0 ||
                        extractor.extract(files.outputBlob, passResult) != 0) {
                        performance.tileExtractMs += elapsed_ms(extractStart);
                        if (is_cancelled(params.taskId)) {
                            return SuperResNativeCode::Cancelled;
                        }
                        log_tile_output_mismatch(
                            params,
                            useVulkan,
                            "tta-extract",
                            tileX,
                            tileY,
                            tileW,
                            tileH,
                            region,
                            passResult,
                            ncnn::Mat{}
                        );
                        return SuperResNativeCode::TileOutputMismatch;
                    }
                    performance.tileExtractMs += elapsed_ms(extractStart);

                    ncnn::Mat normalizedPass;
                    if (!normalize_output_tile_for_copy(passResult, normalizedPass, performance.tileCopyMs)) {
                        log_tile_output_mismatch(
                            params,
                            useVulkan,
                            "tta-normalize",
                            tileX,
                            tileY,
                            tileW,
                            tileH,
                            region,
                            passResult,
                            normalizedPass
                        );
                        return SuperResNativeCode::TileOutputMismatch;
                    }
                    if (!d4_add_ncnn_to_fusion(normalizedPass, transform, fusion)) {
                        log_tile_output_mismatch(
                            params,
                            useVulkan,
                            "tta-inverse-shape",
                            tileX,
                            tileY,
                            tileW,
                            tileH,
                            region,
                            passResult,
                            normalizedPass
                        );
                        return SuperResNativeCode::TileOutputMismatch;
                    }

                    normalizedPass.release();
                    passResult.release();
                    transformedInput.release();
                    // Progress remains tile-based, while cancellation can stop before the next expensive TTA inference.
                    if (pass + 1 < kD4Transforms.size() && is_cancelled(params.taskId)) {
                        return SuperResNativeCode::Cancelled;
                    }
                }
                if (!d4_finish_ncnn_fusion(fusion, copySource)) {
                    log_tile_output_mismatch(
                        params,
                        useVulkan,
                        "tta-fusion",
                        tileX,
                        tileY,
                        tileW,
                        tileH,
                        region,
                        result,
                        copySource
                    );
                    return SuperResNativeCode::TileOutputMismatch;
                }
                if (is_cancelled(params.taskId)) {
                    return SuperResNativeCode::Cancelled;
                }
            }

            const auto copyStart = std::chrono::steady_clock::now();
            const auto encodingBefore = output.encoding_duration();
            if (!copy_output_tile(
                    copySource,
                    output,
                    coveredPixels,
                    params.engineType == kEngineRealCugan && params.scale == 4 ? &image : nullptr,
                    bufferW,
                    bufferH,
                    outputW,
                    outputH,
                    region.cropX,
                    region.cropY,
                    region.dstX,
                    region.dstY,
                    region.copyW,
                    region.copyH,
                    useCroppedOutput,
                    params.outputCropLeft,
                    params.outputCropTop,
                    params.outputCropWidth,
                    params.outputCropHeight,
                    params.engineType == kEngineWaifu2x || params.engineType == kEngineRealCugan,
                    params.engineType == kEngineRealCugan && params.scale == 4
                )) {
                record_tile_copy_duration(performance, copyStart, output, encodingBefore);
                if (output.failure_code() != SuperResNativeCode::Ok) {
                    return output.failure_code();
                }
                log_tile_output_mismatch(
                    params,
                    useVulkan,
                    "copy",
                    tileX,
                    tileY,
                    tileW,
                    tileH,
                    region,
                    result,
                    copySource
                );
                return SuperResNativeCode::TileOutputMismatch;
            }
            record_tile_copy_duration(performance, copyStart, output, encodingBefore);
            copySource.release();
            result.release();
            input.release();

            if (useVulkan) {
                sleep_for_gpu_headroom(
                    params.gpuHeadroomPercent,
                    std::chrono::steady_clock::now() - tileStart
                );
                if (is_cancelled(params.taskId)) {
                    return SuperResNativeCode::Cancelled;
                }
            }

            completed += 1;
            const float progress = 0.12f + 0.78f * (static_cast<float>(completed) / totalTiles);
            emit(
                onProgress,
                SuperResNativeStage::ProcessingTile,
                completed,
                totalTiles,
                progress,
                "Processing tile " + std::to_string(completed) + "/" + std::to_string(totalTiles)
            );
        }
    }
    }

    // The last tile can complete while cancellation is being delivered. Consume it before
    // spending time finalizing the temporary PNG, then arbitrate once more at publication.
    if (is_cancelled(params.taskId)) {
        return SuperResNativeCode::Cancelled;
    }
    emit(onProgress, SuperResNativeStage::Stitching, totalTiles, totalTiles, 0.92f, "Stitching tiles");
    const long long expectedCoveredPixels = static_cast<long long>(bufferW) * bufferH;
    if (coveredPixels != expectedCoveredPixels) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "TileOutputMismatch reason=coverage engine=%d scale=%d noise=%d accel=%s output=%dx%d buffer=%dx%d covered=%lld expected=%lld",
            params.engineType,
            params.scale,
            params.noise,
            acceleration_name(useVulkan),
            outputW,
            outputH,
            bufferW,
            bufferH,
            coveredPixels,
            expectedCoveredPixels
        );
        return SuperResNativeCode::TileOutputMismatch;
    }
    emit(onProgress, SuperResNativeStage::Saving, totalTiles, totalTiles, 0.96f, "Saving output");
    const auto saveStart = std::chrono::steady_clock::now();
    if (params.outputMode == kNativeOutputRawCroppedRgbTile) {
        if (!write_cropped_raw_rgb_tile(
                params.outputPath,
                output.bytes(),
                params.outputCropWidth,
                params.outputCropHeight
            )) {
            return SuperResNativeCode::OutputFailed;
        }
        performance.saveMs = elapsed_ms(saveStart);
    } else {
        const SuperResNativeCode finishCode = output.finish_png_temporary();
        if (finishCode != SuperResNativeCode::Ok) {
            return finishCode;
        }
        SuperResNativeCode publishCode = SuperResNativeCode::Ok;
        {
            // Publication and cancellation share one lock as their linearization point:
            // cancellation that wins keeps the old destination and deletes .part; publication
            // that wins commits atomically and this invocation must return Ok.
            std::lock_guard<std::mutex> cancelLock(cancel_mutex);
            if (consume_cancellation_locked(params.taskId)) {
                output.abort_png();
                return SuperResNativeCode::Cancelled;
            }
            publishCode = output.publish_png();
        }
        if (publishCode != SuperResNativeCode::Ok) {
            return publishCode;
        }
        performance.saveMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            output.encoding_duration()
        ).count();
    }
    performance.preprocessMs = performance.tileInputMs;
    if (!useExplicitVulkanPath) {
        performance.inferenceMs = performance.tileExtractMs;
    }
    performance.postprocessMs = performance.tileCopyMs;
    performance.encodeMs = performance.saveMs;
    performance.totalMs = elapsed_ms(totalStart);
    performance.hasValue = true;
    const std::string modelKey = performance_model_key(params);
    unsigned int driverId = 0u;
    unsigned int driverVersion = 0u;
    if (useVulkan) {
        const int gpuIndex = ncnn::get_default_gpu_index();
        const int gpuCount = ncnn::get_gpu_count();
        if (gpuIndex >= 0 && gpuIndex < gpuCount) {
            const ncnn::GpuInfo& gpuInfo = ncnn::get_gpu_info(gpuIndex);
            driverId = static_cast<unsigned int>(gpuInfo.driver_id());
            driverVersion = static_cast<unsigned int>(gpuInfo.driver_version());
        }
    }
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Performance task=%s model=%s modelKey=%s driverId=0x%08x driverVersion=0x%08x accel=%s backend=%d precision=%d fallback=%d threads=%d blocktimeMs=%d batch=%d cacheCapacity=%d cacheHit=%d cacheSize=%d retry=%d region=%d tile=%d decodeMs=%lld modelLoadMs=%lld tileInputMs=%lld tileExtractMs=%lld tileCopyMs=%lld saveMs=%lld preprocessMs=%lld uploadMs=%lld inferenceMs=%lld downloadMs=%lld postprocessMs=%lld encodeMs=%lld totalMs=%lld",
        params.taskId.c_str(),
        modelKey.c_str(),
        modelKey.c_str(),
        driverId,
        driverVersion,
        acceleration_name(useVulkan),
        performance.effectiveBackend,
        performance.effectivePrecision,
        performance.fallbackReason,
        executionProfile.cpuThreads,
        executionProfile.openmpBlocktimeMs,
        executionProfile.commandBatchSize,
        executionProfile.modelCacheCapacity,
        performance.cacheHit ? 1 : 0,
        performance.cacheSize,
        performance.retryCount,
        performance.regionIndex,
        performance.tileSize,
        performance.decodeMs,
        performance.modelLoadMs,
        performance.tileInputMs,
        performance.tileExtractMs,
        performance.tileCopyMs,
        performance.saveMs,
        performance.preprocessMs,
        performance.uploadMs,
        performance.inferenceMs,
        performance.downloadMs,
        performance.postprocessMs,
        performance.encodeMs,
        performance.totalMs
    );
    emit(onProgress, SuperResNativeStage::Saving, totalTiles, totalTiles, 0.99f, "Native performance sample", performance);

    return SuperResNativeCode::Ok;
}

SuperResNativeCode run_ncnn(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    bool useVulkan
) {
    emit(onProgress, SuperResNativeStage::Analyzing, 0, 0, 0.04f, "Decoding input image");
    const auto decodeStart = std::chrono::steady_clock::now();
    LoadedImage image = load_rgb_image(params.inputPath);
    const long long decodeMs = elapsed_ms(decodeStart);
    return run_ncnn_image(params, onProgress, useVulkan, image, decodeMs);
}

SuperResNativeCode run_ncnn_rgb(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    bool useVulkan,
    int width,
    int height,
    const unsigned char* rgb,
    size_t rgbSize
) {
    emit(onProgress, SuperResNativeStage::Analyzing, 0, 0, 0.04f, "Reading bitmap region");
    if (width <= 0 || height <= 0 || rgb == nullptr ||
        rgbSize != static_cast<size_t>(width) * height * 3) {
        return SuperResNativeCode::InvalidParams;
    }
    LoadedImage image;
    image.width = width;
    image.height = height;
    try {
        image.rgb.assign(rgb, rgb + rgbSize);
    } catch (...) {
        return SuperResNativeCode::OutOfMemory;
    }
    return run_ncnn_image(params, onProgress, useVulkan, image, 0);
}

bool write_all(FILE* file, const void* data, size_t size) {
    return size == 0 || std::fwrite(data, 1, size, file) == size;
}

void put_be32(unsigned char* output, uint32_t value) {
    output[0] = static_cast<unsigned char>((value >> 24) & 0xff);
    output[1] = static_cast<unsigned char>((value >> 16) & 0xff);
    output[2] = static_cast<unsigned char>((value >> 8) & 0xff);
    output[3] = static_cast<unsigned char>(value & 0xff);
}

bool write_png_chunk(FILE* file, const char type[4], const unsigned char* data, size_t size) {
    if (size > 0xffffffffu) {
        return false;
    }
    unsigned char length[4];
    put_be32(length, static_cast<uint32_t>(size));
    if (!write_all(file, length, sizeof(length)) || !write_all(file, type, 4) || !write_all(file, data, size)) {
        return false;
    }
    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const Bytef*>(type), 4);
    if (size > 0) {
        crc = crc32(crc, reinterpret_cast<const Bytef*>(data), static_cast<uInt>(size));
    }
    unsigned char crcBytes[4];
    put_be32(crcBytes, static_cast<uint32_t>(crc));
    return write_all(file, crcBytes, sizeof(crcBytes));
}

bool write_png_signature_and_header(FILE* file, int width, int height) {
    static const unsigned char signature[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    unsigned char ihdr[13] = {};
    put_be32(ihdr, static_cast<uint32_t>(width));
    put_be32(ihdr + 4, static_cast<uint32_t>(height));
    ihdr[8] = 8;
    ihdr[9] = 2;
    return write_all(file, signature, sizeof(signature)) && write_png_chunk(file, "IHDR", ihdr, sizeof(ihdr));
}

bool deflate_png_bytes(
    FILE* file,
    z_stream& stream,
    const unsigned char* data,
    size_t size,
    int flush,
    std::vector<unsigned char>& compressed,
    bool& finished
) {
    stream.next_in = const_cast<Bytef*>(reinterpret_cast<const Bytef*>(data));
    stream.avail_in = static_cast<uInt>(size);
    do {
        stream.next_out = compressed.data();
        stream.avail_out = static_cast<uInt>(compressed.size());
        const int result = deflate(&stream, flush);
        if (result == Z_STREAM_END) {
            finished = true;
        } else if (result != Z_OK) {
            return false;
        }
        const size_t produced = compressed.size() - stream.avail_out;
        if (produced > 0 && !write_png_chunk(file, "IDAT", compressed.data(), produced)) {
            return false;
        }
    } while (stream.avail_out == 0);
    return stream.avail_in == 0;
}
}

SuperResNativeCapabilitySnapshot read_superres_native_capability_snapshot() {
    if (!run_capability_normalization_runtime_checks()) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            kLogTag,
            "Capability normalization runtime checks failed"
        );
        return SuperResNativeCapabilitySnapshot{};
    }

    const SuperResCpuTopology topology = normalize_cpu_topology(
        ncnn::get_physical_cpu_count(),
        ncnn::get_physical_big_cpu_count(),
        ncnn::get_physical_little_cpu_count()
    );
    SuperResNativeCapabilitySnapshot snapshot;
    snapshot.physicalCoreCount = topology.physicalCoreCount;
    snapshot.bigCoreCount = topology.bigCoreCount;
    snapshot.littleCoreCount = topology.littleCoreCount;

    std::lock_guard<std::mutex> gpuLock(gpu_mutex);
    if (!ensure_gpu_instance()) {
        return snapshot;
    }

    const int gpuIndex = ncnn::get_default_gpu_index();
    const int gpuCount = ncnn::get_gpu_count();
    if (gpuIndex < 0 || gpuIndex >= gpuCount) {
        return snapshot;
    }

    const ncnn::GpuInfo& gpuInfo = ncnn::get_gpu_info(gpuIndex);
    char vendorIdentity[32] = {};
    std::snprintf(
        vendorIdentity,
        sizeof(vendorIdentity),
        "vendor=0x%08x",
        static_cast<unsigned int>(gpuInfo.vendor_id())
    );
    char driverIdentity[96] = {};
    const std::string driverName = sanitize_capability_text(gpuInfo.driver_name(), 48);
    std::snprintf(
        driverIdentity,
        sizeof(driverIdentity),
        "%s%sid=0x%08x version=0x%08x",
        driverName.c_str(),
        driverName.empty() ? "" : " ",
        static_cast<unsigned int>(gpuInfo.driver_id()),
        static_cast<unsigned int>(gpuInfo.driver_version())
    );

    snapshot.gpuVendor = sanitize_capability_text(vendorIdentity);
    snapshot.gpuDevice = sanitize_capability_text(gpuInfo.device_name());
    snapshot.gpuDriverVersion = sanitize_capability_text(driverIdentity);
    snapshot.gpuRoughScore = normalize_gpu_rough_score(gpuInfo.rough_score());
    snapshot.supportsVulkan = true;
    snapshot.supportsFp16Storage = gpuInfo.support_fp16_storage();
    snapshot.supportsFp16Arithmetic = gpuInfo.support_fp16_arithmetic();
    return snapshot;
}

using RunNcnnFunction = std::function<SuperResNativeCode(
    const SuperResNativeParams&,
    const SuperResProgressCallback&,
    bool
)>;

SuperResNativeCode process_superres_internal(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    bool requireInputPath,
    const RunNcnnFunction& runNcnn
) {
    {
        std::lock_guard<std::mutex> lock(cancel_mutex);
        cancelled_tasks.erase(params.taskId);
    }

    if (params.taskId.empty() || params.outputPath.empty() ||
        params.modelDir.empty() || params.scale <= 0 || params.tileSize <= 0) {
        return SuperResNativeCode::InvalidParams;
    }
    if (requireInputPath && params.inputPath.empty()) {
        return SuperResNativeCode::InvalidParams;
    }
    if (params.outputMode != kNativeOutputPng && params.outputMode != kNativeOutputRawCroppedRgbTile) {
        return SuperResNativeCode::InvalidParams;
    }
    if (params.outputMode == kNativeOutputRawCroppedRgbTile &&
        (params.outputCropWidth <= 0 || params.outputCropHeight <= 0)) {
        return SuperResNativeCode::InvalidParams;
    }

    if (params.engineType != kEngineWaifu2x &&
        params.engineType != kEngineRealCugan &&
        params.engineType != kEngineRealEsrgan) {
        return SuperResNativeCode::Unsupported;
    }

    if (params.engineType == kEngineRealEsrgan && params.modelFileBase.empty()) {
        return SuperResNativeCode::Unsupported;
    }

    if (requireInputPath && !is_regular_file(params.inputPath)) {
        return SuperResNativeCode::InputMissing;
    }

    emit(onProgress, SuperResNativeStage::Preparing, 0, 0, 0.0f, "Preparing native inference");

    if (params.accelerationMode == kAccelerationVulkan || params.accelerationMode == kAccelerationAuto) {
        if (should_force_cpu_before_vulkan_model_load(params.engineType)) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Skipping Vulkan model load to avoid native GPU loader crash engine=%d scale=%d noise=%d mode=%d",
                params.engineType,
                params.scale,
                params.noise,
                params.accelerationMode
            );
            emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "Using CPU for this model");
            return runNcnn(params, onProgress, false);
        }
        SuperResNativeCode code = runNcnn(params, onProgress, true);
        const bool canRetryCpu = should_retry_cpu_after_gpu_code(
            params.accelerationMode,
            static_cast<int>(code)
        );
        if (!canRetryCpu || params.accelerationMode == kAccelerationVulkan) {
            return code;
        }
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "AUTO retrying CPU after GPU code=%d engine=%d scale=%d noise=%d",
            static_cast<int>(code),
            params.engineType,
            params.scale,
            params.noise
        );
        emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "GPU failed; retrying on CPU");
    }

    return runNcnn(params, onProgress, false);
}

SuperResNativeCode process_superres(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress
) {
    return process_superres_internal(
        params,
        onProgress,
        true,
        [](const SuperResNativeParams& request, const SuperResProgressCallback& progress, bool useVulkan) {
            return run_ncnn(request, progress, useVulkan);
        }
    );
}

SuperResNativeCode process_superres_rgb(
    const SuperResNativeParams& params,
    int width,
    int height,
    const unsigned char* rgb,
    size_t rgbSize,
    const SuperResProgressCallback& onProgress
) {
    if (width <= 0 || height <= 0 || rgb == nullptr ||
        rgbSize != static_cast<size_t>(width) * height * 3) {
        return SuperResNativeCode::InvalidParams;
    }
    return process_superres_internal(
        params,
        onProgress,
        false,
        [width, height, rgb, rgbSize](
            const SuperResNativeParams& request,
            const SuperResProgressCallback& progress,
            bool useVulkan
        ) {
            return run_ncnn_rgb(request, progress, useVulkan, width, height, rgb, rgbSize);
        }
    );
}

void cancel_superres(const std::string& taskId) {
    std::lock_guard<std::mutex> lock(cancel_mutex);
    cancelled_tasks.insert(taskId);
}

void clear_superres_cache() {
    std::lock_guard<std::mutex> gpuLock(gpu_mutex);
    clear_cached_net();
}

SuperResNativeCode merge_raw_tiles_to_png(
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    const std::vector<SuperResRawTile>& tiles
) {
    if (outputPath.empty() || outputWidth <= 0 || outputHeight <= 0 || tiles.empty()) {
        return SuperResNativeCode::InvalidParams;
    }

    struct OpenTile {
        SuperResRawTile spec;
        FILE* file = nullptr;
    };

    std::vector<OpenTile> openTiles;
    openTiles.reserve(tiles.size());
    for (const SuperResRawTile& tile : tiles) {
        if (tile.path.empty() || tile.x < 0 || tile.y < 0 || tile.width <= 0 || tile.height <= 0 ||
            tile.x > outputWidth || tile.y > outputHeight ||
            tile.width > outputWidth - tile.x || tile.height > outputHeight - tile.y) {
            for (OpenTile& openTile : openTiles) {
                std::fclose(openTile.file);
            }
            return SuperResNativeCode::InvalidParams;
        }

        std::error_code error;
        const uintmax_t actualSize = std::filesystem::file_size(tile.path, error);
        const uintmax_t expectedSize = static_cast<uintmax_t>(tile.width) * tile.height * 3;
        if (error || actualSize != expectedSize) {
            for (OpenTile& openTile : openTiles) {
                std::fclose(openTile.file);
            }
            return SuperResNativeCode::OutputFailed;
        }

        FILE* file = std::fopen(tile.path.c_str(), "rb");
        if (file == nullptr) {
            for (OpenTile& openTile : openTiles) {
                std::fclose(openTile.file);
            }
            return SuperResNativeCode::OutputFailed;
        }
        openTiles.push_back(OpenTile{tile, file});
    }

    FILE* output = std::fopen(outputPath.c_str(), "wb");
    if (output == nullptr) {
        for (OpenTile& openTile : openTiles) {
            std::fclose(openTile.file);
        }
        return SuperResNativeCode::OutputFailed;
    }

    z_stream stream{};
    if (deflateInit(&stream, Z_BEST_SPEED) != Z_OK) {
        std::fclose(output);
        for (OpenTile& openTile : openTiles) {
            std::fclose(openTile.file);
        }
        return SuperResNativeCode::OutputFailed;
    }

    bool ok = write_png_signature_and_header(output, outputWidth, outputHeight);
    bool finished = false;
    std::vector<unsigned char> row(static_cast<size_t>(outputWidth) * 3 + 1, 0);
    std::vector<unsigned char> coverage(static_cast<size_t>(outputWidth), 0);
    std::vector<unsigned char> tileRow;
    std::vector<unsigned char> compressed(64 * 1024);

    for (int y = 0; ok && y < outputHeight; ++y) {
        std::fill(row.begin() + 1, row.end(), 0);
        std::fill(coverage.begin(), coverage.end(), 0);
        for (OpenTile& tile : openTiles) {
            if (y < tile.spec.y || y >= tile.spec.y + tile.spec.height) {
                continue;
            }
            const int localY = y - tile.spec.y;
            const size_t rowBytes = static_cast<size_t>(tile.spec.width) * 3;
            tileRow.resize(rowBytes);
            const long offset = static_cast<long>(static_cast<long long>(localY) * tile.spec.width * 3);
            if (std::fseek(tile.file, offset, SEEK_SET) != 0 ||
                std::fread(tileRow.data(), 1, rowBytes, tile.file) != rowBytes) {
                ok = false;
                break;
            }
            for (int x = 0; x < tile.spec.width; ++x) {
                const int outputX = tile.spec.x + x;
                if (coverage[outputX] != 0) {
                    ok = false;
                    break;
                }
                coverage[outputX] = 1;
            }
            if (!ok) {
                break;
            }
            std::copy(tileRow.begin(), tileRow.end(), row.begin() + 1 + static_cast<size_t>(tile.spec.x) * 3);
        }
        if (!ok || std::any_of(coverage.begin(), coverage.end(), [](unsigned char value) { return value == 0; })) {
            ok = false;
            break;
        }
        row[0] = 0;
        ok = deflate_png_bytes(output, stream, row.data(), row.size(), Z_NO_FLUSH, compressed, finished);
    }

    while (ok && !finished) {
        ok = deflate_png_bytes(output, stream, nullptr, 0, Z_FINISH, compressed, finished);
    }
    ok = deflateEnd(&stream) == Z_OK && ok;
    ok = ok && write_png_chunk(output, "IEND", nullptr, 0);
    ok = std::fclose(output) == 0 && ok;
    for (OpenTile& openTile : openTiles) {
        std::fclose(openTile.file);
    }

    if (!ok) {
        std::error_code error;
        std::filesystem::remove(outputPath, error);
        return SuperResNativeCode::OutputFailed;
    }
    return SuperResNativeCode::Ok;
}
