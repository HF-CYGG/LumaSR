// 这个文件负责在原生层执行基于 ncnn 的超分推理，包括模型选择、图像分块、推理拼接与结果输出。
#include "superres_engine.h"
#include "superres_d4_tta_ncnn.h"
#include "superres_execution_profile.h"
#include "superres_extreme_session.h"
#include "superres_atomic_publish.h"
#include "superres_output_finalizer.h"
#include "superres_pixel_io.h"
#include "superres_pipeline_internal.h"
#include "superres_pipeline_cache_store.h"
#include "superres_process_internal.h"
#include "superres_rgb_image_checks.h"
#include "superres_rgb_output_target.h"
#include "superres_ncnn_stage_contract.h"
#include "superres_ordered_pipeline.h"
#include "superres_stbi_decode.h"
#include "superres_png_writer_checks.h"
#include "superres_atomic_publish_checks.h"
#include "superres_task_registry.h"
#include "superres_task_registry_checks.h"
#include "superres_tile_geometry.h"
#include "superres_tta_micro_batch.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "net.h"
#include "pipelinecache.h"
#include "command.h"
#include "cpu.h"
#include "gpu.h"
#include "mat.h"
#include "zlib.h"
#include <unistd.h>

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

std::mutex gpu_mutex;
std::mutex net_cache_mutex;
std::mutex pipeline_cache_store_mutex;
SuperResTaskRegistry task_registry;
SuperResExtremeSessionHandleRegistry extreme_session_registry;
SuperResGpuInitState gpu_init_state = SuperResGpuInitState::Uninitialized;

struct ModelFiles {
    std::string paramPath;
    std::string binPath;
    const char* inputBlob;
    const char* outputBlob;
};

struct NetCacheEntry {
    std::string key;
    std::shared_ptr<ncnn::PipelineCache> pipelineCache;
    std::shared_ptr<ncnn::Net> net;
    bool pipelineCacheHit = false;
    std::size_t pipelineCacheBytes = 0u;
};

struct LoadedNet {
    std::shared_ptr<ncnn::PipelineCache> pipelineCache;
    std::shared_ptr<ncnn::Net> net;
    bool cacheHit = false;
    bool pipelineCacheHit = false;
    std::size_t pipelineCacheBytes = 0u;
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

struct OptimizedVulkanTtaPassWork {
    size_t pass;
    ncnn::Mat transformedInput;
    ncnn::VkMat uploaded;
    ncnn::VkMat inferred;
    ncnn::Mat downloaded;
};

class OptimizedVulkanTtaContext {
public:
    explicit OptimizedVulkanTtaContext(ncnn::Net& net)
        : device_(net.vulkan_device()),
          allocators_(device_),
          executionOption_(allocators_.attach_to(net.opt)) {}

    bool valid() const {
        return device_ != nullptr && allocators_.valid();
    }

    const ncnn::VulkanDevice* device() const {
        return device_;
    }

    InvocationVulkanAllocators& allocators() {
        return allocators_;
    }

    const ncnn::Option& execution_option() const {
        return executionOption_;
    }

    std::chrono::steady_clock::duration uploadDuration{};
    std::chrono::steady_clock::duration inferenceDuration{};
    std::chrono::steady_clock::duration downloadDuration{};

private:
    const ncnn::VulkanDevice* device_;
    InvocationVulkanAllocators allocators_;
    ncnn::Option executionOption_;
};

struct OptimizedVulkanPipelineSlot {
    explicit OptimizedVulkanPipelineSlot(const ncnn::VulkanDevice* device)
        : allocators(std::make_unique<InvocationVulkanAllocators>(device)) {}

    std::unique_ptr<InvocationVulkanAllocators> allocators;
    std::vector<OptimizedVulkanTileWork> batch;
    std::vector<std::unique_ptr<ncnn::Extractor>> extractors;
    SuperResRuntimeInferenceControl control;
    std::chrono::steady_clock::time_point batchStart;
    std::chrono::steady_clock::duration uploadDuration{};
    std::chrono::steady_clock::duration inferenceDuration{};
    std::chrono::steady_clock::duration downloadDuration{};
    long long tileInputMs = 0;
    long long tileCopyMs = 0;
    int copiedTiles = 0;
    size_t peakBytes = 0;

    void clear_payload() {
        batch.clear();
        extractors.clear();
        uploadDuration = {};
        inferenceDuration = {};
        downloadDuration = {};
        tileInputMs = 0;
        tileCopyMs = 0;
        copiedTiles = 0;
    }
};

NetCacheEntry net_cache;
std::vector<NetCacheEntry> net_cache_entries;
std::shared_ptr<SuperResPipelineCacheStore> pipeline_cache_store;

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
    const RgbOutputTarget& output,
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
            params.postprocessThreads,
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

std::string make_pipeline_cache_key(
    const ModelFiles& files,
    bool useVulkan,
    const SuperResExecutionProfile& profile,
    const ncnn::GpuInfo& gpuInfo
) {
    std::error_code error;
    const auto paramBytes = std::filesystem::file_size(files.paramPath, error);
    const auto safeParamBytes = error ? 0u : paramBytes;
    error.clear();
    const auto modelBytes = std::filesystem::file_size(files.binPath, error);
    const auto safeModelBytes = error ? 0u : modelBytes;
    return superres_sha256_hex(
        std::string(NCNN_VERSION_STRING) + "\n" + files.paramPath + "\n" + files.binPath +
        "\n" + std::to_string(safeParamBytes) + "\n" + std::to_string(safeModelBytes) +
        "\n" + std::to_string(gpuInfo.vendor_id()) + "\n" +
        std::to_string(gpuInfo.device_id()) + "\n" + std::to_string(gpuInfo.driver_id()) +
        "\n" + std::to_string(gpuInfo.driver_version()) + "\n" +
        pipeline_cache_uuid_hex(gpuInfo.pipeline_cache_uuid()) + "\n" +
        serialize_execution_cache_identity(useVulkan, profile)
    );
}

std::shared_ptr<SuperResPipelineCacheStore> current_pipeline_cache_store() {
    std::lock_guard<std::mutex> lock(pipeline_cache_store_mutex);
    return pipeline_cache_store;
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
                return LoadedNet{
                    cached.pipelineCache,
                    cached.net,
                    true,
                    cached.pipelineCacheHit,
                    cached.pipelineCacheBytes
                };
            }
        }
    }

    auto net = std::make_shared<ncnn::Net>();
    std::shared_ptr<ncnn::PipelineCache> persistentPipelineCache;
    std::shared_ptr<SuperResPipelineCacheStore> persistentStore;
    std::string persistentCacheKey;
    bool persistentCacheHit = false;
    std::size_t persistentCacheBytes = 0u;
    net->opt.num_threads = profile.cpuThreads;
    net->opt.openmp_blocktime = profile.openmpBlocktimeMs;
    net->opt.use_packing_layout = true;
    net->opt.use_vulkan_compute = useVulkan;
    if (useVulkan) {
        const ncnn::GpuInfo& gpuInfo = ncnn::get_gpu_info(ncnn::get_default_gpu_index());
        net->opt.use_fp16_packed = true;
        net->opt.use_fp16_storage = true;
        // Legacy-safe remains on FP32 arithmetic; optimized FP16 is gated by the GPU capability check.
        net->opt.use_fp16_arithmetic =
            profile.effectivePrecision == SuperResInferencePrecision::Fp16;
        if (is_qualcomm_vulkan_vendor(gpuInfo.vendor_id())) {
            net->opt.use_cooperative_matrix = false;
            net->opt.use_mapped_model_loading =
                is_regular_file(files.paramPath) && is_regular_file(files.binPath);
        }
        net->set_vulkan_device(ncnn::get_default_gpu_index());
        persistentStore = !gpuInfo.bug_corrupted_online_pipeline_cache()
            ? current_pipeline_cache_store()
            : nullptr;
        const ncnn::VulkanDevice* device = net->vulkan_device();
        if (persistentStore && device != nullptr) {
            persistentCacheKey = make_pipeline_cache_key(files, useVulkan, profile, gpuInfo);
            persistentPipelineCache = std::make_shared<ncnn::PipelineCache>(device);
            const std::vector<unsigned char> cachedBytes = persistentStore->load(persistentCacheKey);
            if (!cachedBytes.empty()) {
                if (persistentPipelineCache->load_cache(cachedBytes) == 0) {
                    persistentCacheHit = true;
                    persistentCacheBytes = cachedBytes.size();
                } else {
                    persistentStore->remove(persistentCacheKey);
                }
            }
            net->opt.pipeline_cache = persistentPipelineCache.get();
        }
    }
    if (net->load_param(files.paramPath.c_str()) != 0 || net->load_model(files.binPath.c_str()) != 0) {
        return LoadedNet{};
    }
    if (persistentPipelineCache && persistentStore && !persistentCacheHit) {
        std::vector<unsigned char> generatedCache;
        if (persistentPipelineCache->save_cache(generatedCache) == 0 &&
            persistentStore->save(persistentCacheKey, generatedCache)) {
            persistentCacheBytes = generatedCache.size();
        }
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
    net_cache_entries.push_back(NetCacheEntry{
        key,
        persistentPipelineCache,
        net,
        persistentCacheHit,
        persistentCacheBytes
    });
    const NetCacheEntry& stored = net_cache_entries.back();
    return LoadedNet{
        stored.pipelineCache,
        stored.net,
        false,
        stored.pipelineCacheHit,
        stored.pipelineCacheBytes
    };
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
    return task_registry.consume_cancelled(taskId);
}

SuperResRuntimeInferenceControl read_runtime_control(
    const SuperResRuntimeControlCallback& runtimeControl,
    int defaultCommandBatchSize
) {
    const SuperResRuntimeInferenceControl fallback = sanitize_runtime_control(
        SuperResRuntimeInferenceControl{defaultCommandBatchSize, 0, false, 1}
    );
    if (!runtimeControl) {
        return fallback;
    }
    try {
        return sanitize_runtime_control(runtimeControl());
    } catch (...) {
        return fallback;
    }
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

void log_native_performance_sample(
    const SuperResNativeParams& params,
    bool useVulkan,
    const SuperResExecutionProfile& executionProfile,
    const SuperResNativePerformance& performance,
    const char* timingScope,
    bool finalPublishPending
) {
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
        "Performance task=%s model=%s modelKey=%s driverId=0x%08x driverVersion=0x%08x accel=%s backend=%d precision=%d fallback=%d requestedPipelineDepth=%d pipelineDepth=%d peakOccupiedSlots=%d peakSlotBytes=%lld producerWaitMs=%lld consumerWaitMs=%lld overlapMs=%lld threads=%d blocktimeMs=%d postprocessThreads=%d batch=%d cacheCapacity=%d cacheHit=%d cacheSize=%d pipelineCacheHit=%d pipelineCacheBytes=%lld pipelineCacheRebuilt=%d retry=%d region=%d tile=%d decodeMs=%lld modelLoadMs=%lld tileInputMs=%lld tileExtractMs=%lld tileCopyMs=%lld saveMs=%lld preprocessMs=%lld uploadMs=%lld inferenceMs=%lld downloadMs=%lld postprocessMs=%lld encodeMs=%lld totalMs=%lld timingScope=%s finalPublishPending=%d",
        params.taskId.c_str(),
        modelKey.c_str(),
        modelKey.c_str(),
        driverId,
        driverVersion,
        acceleration_name(useVulkan),
        performance.effectiveBackend,
        performance.effectivePrecision,
        performance.fallbackReason,
        performance.requestedMaxPipelineDepth,
        performance.effectivePipelineDepth,
        performance.peakOccupiedPipelineSlots,
        performance.peakPipelineSlotBytes,
        performance.producerWaitMs,
        performance.consumerWaitMs,
        performance.pipelineOverlapMs,
        executionProfile.cpuThreads,
        executionProfile.openmpBlocktimeMs,
        executionProfile.postprocessThreads,
        executionProfile.commandBatchSize,
        executionProfile.modelCacheCapacity,
        performance.cacheHit ? 1 : 0,
        performance.cacheSize,
        performance.pipelineCacheHit ? 1 : 0,
        performance.pipelineCacheBytes,
        performance.pipelineCacheRebuilt ? 1 : 0,
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
        performance.totalMs,
        timingScope,
        finalPublishPending ? 1 : 0
    );
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

void release_stbi_pixels(unsigned char* pixels) noexcept {
    stbi_image_free(pixels);
}

SuperResRgbImage load_rgb_image(const std::string& path) {
    SuperResRgbDecodeResult result = decode_superres_rgb_image(
        path,
        SuperResStbiApi{stbi_load, stbi_failure_reason, release_stbi_pixels}
    );
    if (result.status == SuperResRgbDecodeStatus::OutOfMemory) {
        throw std::bad_alloc{};
    }
    return std::move(result.image);
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
    const SuperResRgbImage& image,
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
        const int sourceY = std::min(image.height() - 1, std::max(0, tileY + y));
        const unsigned char* const sourceRow =
            image.data() + static_cast<size_t>(sourceY) * image.row_stride_bytes();
        if (!copy_clamped_rgb8_row_to_planar(
                sourceRow,
                image.width(),
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
    RgbOutputTarget& output,
    long long& coveredPixels,
    const SuperResRgbImage* residualBaseImage,
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
        (residualBaseImage == nullptr || !residualBaseImage->valid())) {
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
                residualBaseImage->height()
            );
            residualWidth = residualBaseImage->width();
            residualRow = residualBaseImage->data() +
                static_cast<size_t>(baseY) * residualBaseImage->row_stride_bytes();
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

bool normalize_output_tile_for_copy(
    const ncnn::Mat& source,
    int postprocessThreads,
    ncnn::Mat& normalized,
    long long& tileCopyMs
) {
    const auto normalizeStart = std::chrono::steady_clock::now();
    if (source.empty()) {
        normalized = source;
        tileCopyMs += elapsed_ms(normalizeStart);
        return false;
    }

    ncnn::Option opt;
    opt.num_threads = clamp_execution_value(postprocessThreads, 1, 64);

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

SuperResNativeCode run_optimized_vulkan_tiles_serial(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    const SuperResRgbImage& image,
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
    RgbOutputTarget& output,
    long long& coveredPixels,
    int& completed,
    SuperResNativePerformance& performance,
    const SuperResRuntimeInferenceControl& initialControl
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
    bool useInitialControl = true;
    performance.effectivePipelineDepth = 1;
    performance.peakOccupiedPipelineSlots = 0;
    performance.peakPipelineSlotBytes = 0;
    performance.producerWaitMs = 0;
    performance.consumerWaitMs = 0;
    performance.pipelineOverlapMs = 0;

    while (firstTile < totalTiles) {
        const SuperResRuntimeInferenceControl control = useInitialControl
            ? initialControl
            : read_runtime_control(runtimeControl, executionProfile.commandBatchSize);
        useInitialControl = false;
        performance.requestedMaxPipelineDepth = control.maxPipelineDepth;
        if (control.shouldPause) {
            return SuperResNativeCode::Cancelled;
        }
        const bool cancellationRequested = is_cancelled(params.taskId);
        const SuperResCommandBatchPlan plan = plan_command_batch(
            totalTiles,
            control.commandBatchSize,
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
            const int tileW = std::min(tileSize, image.width() - tileX);
            const int tileH = std::min(tileSize, image.height() - tileY);
            const TileInputRegion region = params.engineType == kEngineWaifu2x
                ? make_waifu2x_tile_region(
                    params,
                    image.width(),
                    image.height(),
                    tileX,
                    tileY,
                    tileW,
                    tileH
                )
                : params.engineType == kEngineRealCugan
                    ? make_realcugan_tile_region(
                        params,
                        image.width(),
                        image.height(),
                        tileX,
                        tileY,
                        tileW,
                        tileH
                    )
                    : make_overlapped_tile_region(
                        image.width(),
                        image.height(),
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
            if (inferenceFailed || work.inferred.empty()) {
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
            if (!normalize_output_tile_for_copy(
                    work.downloaded,
                    executionProfile.postprocessThreads,
                    copySource,
                    performance.tileCopyMs
                )) {
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

            ++completed;
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
            resolve_runtime_headroom_percent(
                params.gpuHeadroomPercent,
                control.thermalHeadroomPercent,
                true
            ),
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

SuperResVulkanPipelineAttemptResult run_optimized_vulkan_tiles_ordered(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    const SuperResRgbImage& image,
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
    RgbOutputTarget& output,
    long long& coveredPixels,
    int& completed,
    SuperResNativePerformance& performance,
    const SuperResRuntimeInferenceControl& initialControl
) {
    const ncnn::VulkanDevice* device = net.vulkan_device();
    std::vector<std::unique_ptr<OptimizedVulkanPipelineSlot>> slots;
    try {
        slots.push_back(std::make_unique<OptimizedVulkanPipelineSlot>(device));
        if (!slots.front()->allocators->valid()) {
            return SuperResVulkanPipelineAttemptResult{
                static_cast<int>(SuperResNativeCode::VulkanFailed),
                true,
                false
            };
        }
        auto secondSlot = std::make_unique<OptimizedVulkanPipelineSlot>(device);
        if (!secondSlot->allocators->valid()) {
            return SuperResVulkanPipelineAttemptResult{
                static_cast<int>(SuperResNativeCode::VulkanFailed),
                true,
                false
            };
        }
        slots.push_back(std::move(secondSlot));
    } catch (...) {
        return SuperResVulkanPipelineAttemptResult{
            static_cast<int>(SuperResNativeCode::VulkanFailed),
            true,
            false
        };
    }

    const int totalTiles = tilesX * tilesY;
    std::chrono::steady_clock::duration uploadDuration{};
    std::chrono::steady_clock::duration inferenceDuration{};
    std::chrono::steady_clock::duration downloadDuration{};
    int firstTile = 0;
    SuperResRuntimeInferenceControl nextControl = initialControl;
    bool useInitialControl = true;
    bool outputWritten = false;

    SuperResOrderedPipelineOperations operations;
    // Dynamic command batches mean the exact batch count is unknown. totalTiles is the strict
    // upper bound (batch size one); Finished ends production once every tile has been recorded.
    operations.itemCount = totalTiles;
    operations.maximumDepth = 2;
    operations.pollProducerControl = [&](int) {
        if (useInitialControl) {
            useInitialControl = false;
        } else {
            nextControl = read_runtime_control(
                runtimeControl,
                executionProfile.commandBatchSize
            );
        }
        performance.requestedMaxPipelineDepth = nextControl.maxPipelineDepth;
        const bool cancellationRequested = nextControl.shouldPause ||
            is_cancelled(params.taskId);
        return SuperResOrderedPipelineProducerControl{
            nextControl.maxPipelineDepth,
            cancellationRequested,
            static_cast<int>(SuperResNativeCode::Cancelled)
        };
    };
    operations.produce = [&](int slotIndex,
                             int,
                             const SuperResOrderedPipelineCancellationToken& token) {
        if (firstTile >= totalTiles) {
            return SuperResOrderedPipelineStepResult::finished();
        }
        if (token.stop_requested()) {
            return SuperResOrderedPipelineStepResult::cancelled(
                static_cast<int>(SuperResNativeCode::Cancelled)
            );
        }

        OptimizedVulkanPipelineSlot& slot = *slots[static_cast<size_t>(slotIndex)];
        slot.clear_payload();
        slot.control = nextControl;
        const SuperResCommandBatchPlan plan = plan_command_batch(
            totalTiles,
            slot.control.commandBatchSize,
            firstTile,
            false
        );
        if (!plan.shouldRecord) {
            return SuperResOrderedPipelineStepResult::finished();
        }

        slot.batchStart = std::chrono::steady_clock::now();
        slot.batch.reserve(static_cast<size_t>(plan.tileCount));
        slot.extractors.reserve(static_cast<size_t>(plan.tileCount));
        const ncnn::Option executionOption = slot.allocators->attach_to(net.opt);
        ncnn::VkCompute command(device);

        for (int offset = 0; offset < plan.tileCount; ++offset) {
            const int tileIndex = plan.firstTile + offset;
            const int tx = tileIndex % tilesX;
            const int ty = tileIndex / tilesX;
            const int tileX = tx * tileSize;
            const int tileY = ty * tileSize;
            const int tileW = std::min(tileSize, image.width() - tileX);
            const int tileH = std::min(tileSize, image.height() - tileY);
            const TileInputRegion region = params.engineType == kEngineWaifu2x
                ? make_waifu2x_tile_region(params, image.width(), image.height(), tileX, tileY, tileW, tileH)
                : params.engineType == kEngineRealCugan
                    ? make_realcugan_tile_region(params, image.width(), image.height(), tileX, tileY, tileW, tileH)
                    : make_overlapped_tile_region(
                        image.width(),
                        image.height(),
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
            slot.tileInputMs += elapsed_ms(inputStart);
            slot.batch.push_back(OptimizedVulkanTileWork{
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
            OptimizedVulkanTileWork& work = slot.batch.back();

            const auto uploadStart = std::chrono::steady_clock::now();
            command.record_upload(work.input, work.uploaded, executionOption);
            slot.uploadDuration += std::chrono::steady_clock::now() - uploadStart;
            if (work.uploaded.empty()) {
                return SuperResOrderedPipelineStepResult::failed(
                    static_cast<int>(SuperResNativeCode::VulkanFailed)
                );
            }

            auto extractor = std::make_unique<ncnn::Extractor>(net.create_extractor());
            extractor->set_blob_vkallocator(slot.allocators->blob());
            extractor->set_workspace_vkallocator(slot.allocators->workspace());
            extractor->set_staging_vkallocator(slot.allocators->staging());
            const auto inferenceStart = std::chrono::steady_clock::now();
            const bool inferenceFailed =
                extractor->input(files.inputBlob, work.uploaded) != 0 ||
                extractor->extract(files.outputBlob, work.inferred, command) != 0;
            slot.inferenceDuration += std::chrono::steady_clock::now() - inferenceStart;
            if (inferenceFailed) {
                return SuperResOrderedPipelineStepResult::failed(
                    static_cast<int>(SuperResNativeCode::VulkanFailed)
                );
            }
            if (work.inferred.empty()) {
                return SuperResOrderedPipelineStepResult::failed(
                    static_cast<int>(SuperResNativeCode::VulkanFailed)
                );
            }
            slot.extractors.push_back(std::move(extractor));

            const auto downloadStart = std::chrono::steady_clock::now();
            command.record_download(work.inferred, work.downloaded, executionOption);
            slot.downloadDuration += std::chrono::steady_clock::now() - downloadStart;
        }

        // ncnn exposes one synchronization result for the complete command buffer; keep it in inference time.
        const auto submitStart = std::chrono::steady_clock::now();
        const int submitResult = command.submit_and_wait();
        slot.inferenceDuration += std::chrono::steady_clock::now() - submitStart;
        if (submitResult != 0) {
            return SuperResOrderedPipelineStepResult::failed(
                static_cast<int>(SuperResNativeCode::VulkanFailed)
            );
        }

        if (is_cancelled(params.taskId)) {
            return SuperResOrderedPipelineStepResult::cancelled(
                static_cast<int>(SuperResNativeCode::Cancelled)
            );
        }

        size_t currentSlotBytes = 0;
        const auto addMatrixBytes = [&currentSlotBytes](const auto& matrix) {
            if (matrix.empty() || matrix.elemsize == 0) {
                return;
            }
            const size_t elements = matrix.total();
            const size_t bytes = elements > std::numeric_limits<size_t>::max() / matrix.elemsize
                ? std::numeric_limits<size_t>::max()
                : elements * matrix.elemsize;
            currentSlotBytes = bytes > std::numeric_limits<size_t>::max() - currentSlotBytes
                ? std::numeric_limits<size_t>::max()
                : currentSlotBytes + bytes;
        };
        for (const OptimizedVulkanTileWork& work : slot.batch) {
            addMatrixBytes(work.input);
            addMatrixBytes(work.uploaded);
            addMatrixBytes(work.inferred);
            addMatrixBytes(work.downloaded);
        }
        slot.peakBytes = std::max(slot.peakBytes, currentSlotBytes);
        firstTile += plan.tileCount;
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.onProduced = [&](int slotIndex, int) {
        OptimizedVulkanPipelineSlot& slot = *slots[static_cast<size_t>(slotIndex)];
        performance.tileInputMs += slot.tileInputMs;
        uploadDuration += slot.uploadDuration;
        inferenceDuration += slot.inferenceDuration;
        downloadDuration += slot.downloadDuration;
        sleep_for_gpu_headroom(
            resolve_runtime_headroom_percent(
                params.gpuHeadroomPercent,
                slot.control.thermalHeadroomPercent,
                true
            ),
            std::chrono::steady_clock::now() - slot.batchStart
        );
    };
    operations.consume = [&](int slotIndex,
                             int,
                             const SuperResOrderedPipelineCancellationToken& token) {
        OptimizedVulkanPipelineSlot& slot = *slots[static_cast<size_t>(slotIndex)];
        for (size_t index = 0; index < slot.batch.size(); ++index) {
            if (token.stop_requested()) {
                return SuperResOrderedPipelineStepResult::cancelled(
                    static_cast<int>(SuperResNativeCode::Cancelled)
                );
            }
            OptimizedVulkanTileWork& work = slot.batch[index];
            if (work.downloaded.empty()) {
                return SuperResOrderedPipelineStepResult::failed(
                    static_cast<int>(SuperResNativeCode::VulkanFailed)
                );
            }

            ncnn::Mat copySource;
            if (!normalize_output_tile_for_copy(
                    work.downloaded,
                    executionProfile.postprocessThreads,
                    copySource,
                    slot.tileCopyMs
                )) {
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
                return SuperResOrderedPipelineStepResult::failed(
                    static_cast<int>(SuperResNativeCode::TileOutputMismatch)
                );
            }

            const auto copyStart = std::chrono::steady_clock::now();
            const auto encodingBefore = output.encoding_duration();
            outputWritten = true;
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
                const auto elapsed = std::chrono::steady_clock::now() - copyStart;
                const auto encoding = output.encoding_duration() - encodingBefore;
                const auto copyOnly = elapsed > encoding
                    ? elapsed - encoding
                    : std::chrono::steady_clock::duration{};
                slot.tileCopyMs += std::chrono::duration_cast<std::chrono::milliseconds>(
                    copyOnly
                ).count();
                if (output.failure_code() != SuperResNativeCode::Ok) {
                    return SuperResOrderedPipelineStepResult::failed(
                        static_cast<int>(output.failure_code())
                    );
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
                return SuperResOrderedPipelineStepResult::failed(
                    static_cast<int>(SuperResNativeCode::TileOutputMismatch)
                );
            }
            const auto elapsed = std::chrono::steady_clock::now() - copyStart;
            const auto encoding = output.encoding_duration() - encodingBefore;
            const auto copyOnly = elapsed > encoding
                ? elapsed - encoding
                : std::chrono::steady_clock::duration{};
            slot.tileCopyMs += std::chrono::duration_cast<std::chrono::milliseconds>(
                copyOnly
            ).count();
            ++slot.copiedTiles;
        }
        return SuperResOrderedPipelineStepResult::ok();
    };
    operations.onConsumed = [&](int slotIndex, int) {
        OptimizedVulkanPipelineSlot& slot = *slots[static_cast<size_t>(slotIndex)];
        performance.tileCopyMs += slot.tileCopyMs;
        for (int index = 0; index < slot.copiedTiles; ++index) {
            ++completed;
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
        slot.clear_payload();
    };

    const SuperResOrderedPipelineRunResult pipeline = run_superres_ordered_pipeline(operations);

    // Convert once after all batches so sub-millisecond record calls accumulate instead of truncating per tile.
    performance.uploadMs = std::chrono::duration_cast<std::chrono::milliseconds>(uploadDuration).count();
    performance.inferenceMs = std::chrono::duration_cast<std::chrono::milliseconds>(inferenceDuration).count();
    performance.downloadMs = std::chrono::duration_cast<std::chrono::milliseconds>(downloadDuration).count();
    performance.effectivePipelineDepth = pipeline.telemetry.effectiveDepth;
    performance.peakOccupiedPipelineSlots = pipeline.telemetry.peakOccupiedSlots;
    performance.producerWaitMs = pipeline.telemetry.producerWaitMs;
    performance.consumerWaitMs = pipeline.telemetry.consumerWaitMs;
    performance.pipelineOverlapMs = pipeline.telemetry.overlapMs;
    size_t peakSlotBytes = 0;
    for (const auto& slot : slots) {
        peakSlotBytes = std::max(peakSlotBytes, slot->peakBytes);
    }
    performance.peakPipelineSlotBytes = static_cast<long long>(std::min<size_t>(
        peakSlotBytes,
        static_cast<size_t>(std::numeric_limits<long long>::max())
    ));

    if (pipeline.step.kind == SuperResOrderedPipelineStepKind::Ok) {
        const SuperResNativeCode code = firstTile == totalTiles && completed == totalTiles
            ? SuperResNativeCode::Ok
            : SuperResNativeCode::VulkanFailed;
        return SuperResVulkanPipelineAttemptResult{
            static_cast<int>(code),
            false,
            outputWritten
        };
    }
    if (pipeline.step.kind == SuperResOrderedPipelineStepKind::Cancelled) {
        return SuperResVulkanPipelineAttemptResult{
            static_cast<int>(SuperResNativeCode::Cancelled),
            false,
            outputWritten
        };
    }
    if (pipeline.step.kind == SuperResOrderedPipelineStepKind::InitializationFailed) {
        return SuperResVulkanPipelineAttemptResult{
            static_cast<int>(SuperResNativeCode::VulkanFailed),
            true,
            outputWritten
        };
    }
    const int failureCode = pipeline.step.errorCode;
    if (failureCode >= static_cast<int>(SuperResNativeCode::InputMissing) &&
        failureCode <= static_cast<int>(SuperResNativeCode::PipelineFallbackRequired)) {
        return SuperResVulkanPipelineAttemptResult{failureCode, false, outputWritten};
    }
    return SuperResVulkanPipelineAttemptResult{
        static_cast<int>(SuperResNativeCode::VulkanFailed),
        false,
        outputWritten
    };
}

SuperResNativeCode run_optimized_vulkan_tiles(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    const SuperResRgbImage& image,
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
    RgbOutputTarget& output,
    long long& coveredPixels,
    int& completed,
    SuperResNativePerformance& performance
) {
    const SuperResRuntimeInferenceControl initialControl = read_runtime_control(
        runtimeControl,
        executionProfile.commandBatchSize
    );
    performance.requestedMaxPipelineDepth = initialControl.maxPipelineDepth;

    const int result = dispatch_superres_vulkan_pipeline(
        initialControl.maxPipelineDepth,
        [&]() {
            return static_cast<int>(run_optimized_vulkan_tiles_serial(
                params,
                onProgress,
                runtimeControl,
                image,
                files,
                net,
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
                performance,
                initialControl
            ));
        },
        [&]() {
            return run_optimized_vulkan_tiles_ordered(
                params,
                onProgress,
                runtimeControl,
                image,
                files,
                net,
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
                performance,
                initialControl
            );
        }
    );
    return static_cast<SuperResNativeCode>(result);
}

SuperResNativeCode run_optimized_vulkan_tta_tile(
    const SuperResNativeParams& params,
    const SuperResRuntimeControlCallback& runtimeControl,
    const ModelFiles& files,
    ncnn::Net& net,
    const SuperResExecutionProfile& executionProfile,
    OptimizedVulkanTtaContext& context,
    const ncnn::Mat& input,
    int tileX,
    int tileY,
    int tileW,
    int tileH,
    const TileInputRegion& region,
    ncnn::Mat& copySource,
    SuperResNativePerformance& performance
) {
    if (!context.valid() || input.empty()) {
        return SuperResNativeCode::VulkanFailed;
    }

    performance.effectivePipelineDepth = 1;
    performance.peakOccupiedPipelineSlots = 1;
    performance.peakPipelineSlotBytes = 0;
    performance.producerWaitMs = 0;
    performance.consumerWaitMs = 0;
    performance.pipelineOverlapMs = 0;

    bool stopLatched = false;
    SuperResNativeCode failureCode = SuperResNativeCode::VulkanFailed;
    auto checkBoundary = [&]() {
        if (stopLatched) {
            return SuperResTtaMicroBatchStatus::Cancelled;
        }
        const SuperResRuntimeInferenceControl control = read_runtime_control(
            runtimeControl,
            executionProfile.commandBatchSize
        );
        performance.requestedMaxPipelineDepth = control.maxPipelineDepth;
        stopLatched = control.shouldPause || is_cancelled(params.taskId);
        if (stopLatched) {
            return SuperResTtaMicroBatchStatus::Cancelled;
        }
        return control.commandBatchSize < 2
            ? SuperResTtaMicroBatchStatus::Failed
            : SuperResTtaMicroBatchStatus::Ok;
    };
    auto failedOrCancelled = [&]() {
        return checkBoundary() == SuperResTtaMicroBatchStatus::Cancelled
            ? SuperResTtaMicroBatchStatus::Cancelled
            : SuperResTtaMicroBatchStatus::Failed;
    };

    D4FloatFusion fusion;
    std::vector<OptimizedVulkanTtaPassWork> currentWorks;
    std::vector<std::unique_ptr<ncnn::Extractor>> currentExtractors;
    size_t currentFirstPass = 0u;

    try {
        const SuperResTtaMicroBatchRunResult runResult = run_superres_tta_micro_batches(
            checkBoundary,
            [&](const SuperResTtaPassGroup& group) {
                currentFirstPass = group.firstPass;
                currentWorks.clear();
                currentExtractors.clear();
                currentWorks.reserve(group.passCount);
                currentExtractors.reserve(group.passCount);
                ncnn::VkCompute command(context.device());

                for (size_t offset = 0u; offset < group.passCount; ++offset) {
                    const size_t pass = group.firstPass + offset;
                    ncnn::Mat transformedInput;
                    if (pass >= kD4Transforms.size() ||
                        !d4_transform_ncnn_mat(
                            input,
                            kD4Transforms[pass],
                            transformedInput
                        )) {
                        failureCode = SuperResNativeCode::TileOutputMismatch;
                        return failedOrCancelled();
                    }

                    currentWorks.push_back(OptimizedVulkanTtaPassWork{
                        pass,
                        transformedInput,
                        ncnn::VkMat{},
                        ncnn::VkMat{},
                        ncnn::Mat{}
                    });
                    OptimizedVulkanTtaPassWork& work = currentWorks.back();

                    const auto uploadStart = std::chrono::steady_clock::now();
                    command.record_upload(
                        work.transformedInput,
                        work.uploaded,
                        context.execution_option()
                    );
                    context.uploadDuration += std::chrono::steady_clock::now() - uploadStart;
                    if (work.uploaded.empty()) {
                        failureCode = SuperResNativeCode::VulkanFailed;
                        return failedOrCancelled();
                    }

                    auto extractor = std::make_unique<ncnn::Extractor>(net.create_extractor());
                    extractor->set_blob_vkallocator(context.allocators().blob());
                    extractor->set_workspace_vkallocator(context.allocators().workspace());
                    extractor->set_staging_vkallocator(context.allocators().staging());
                    const auto inferenceStart = std::chrono::steady_clock::now();
                    const bool inferenceFailed =
                        extractor->input(files.inputBlob, work.uploaded) != 0 ||
                        extractor->extract(files.outputBlob, work.inferred, command) != 0;
                    context.inferenceDuration +=
                        std::chrono::steady_clock::now() - inferenceStart;
                    if (inferenceFailed || work.inferred.empty()) {
                        failureCode = SuperResNativeCode::VulkanFailed;
                        return failedOrCancelled();
                    }
                    currentExtractors.push_back(std::move(extractor));

                    const auto downloadStart = std::chrono::steady_clock::now();
                    command.record_download(
                        work.inferred,
                        work.downloaded,
                        context.execution_option()
                    );
                    context.downloadDuration +=
                        std::chrono::steady_clock::now() - downloadStart;
                }

                const auto submitStart = std::chrono::steady_clock::now();
                const int submitResult = command.submit_and_wait();
                context.inferenceDuration += std::chrono::steady_clock::now() - submitStart;
                if (submitResult != 0) {
                    failureCode = SuperResNativeCode::VulkanFailed;
                    return failedOrCancelled();
                }
                return SuperResTtaMicroBatchStatus::Ok;
            },
            [&](size_t pass) {
                if (pass < currentFirstPass ||
                    pass - currentFirstPass >= currentWorks.size()) {
                    failureCode = SuperResNativeCode::TileOutputMismatch;
                    return SuperResTtaMicroBatchStatus::Failed;
                }
                OptimizedVulkanTtaPassWork& work = currentWorks[pass - currentFirstPass];
                if (work.pass != pass || work.downloaded.empty()) {
                    failureCode = work.downloaded.empty()
                        ? SuperResNativeCode::VulkanFailed
                        : SuperResNativeCode::TileOutputMismatch;
                    return SuperResTtaMicroBatchStatus::Failed;
                }

                ncnn::Mat normalizedPass;
                if (!normalize_output_tile_for_copy(
                        work.downloaded,
                        executionProfile.postprocessThreads,
                        normalizedPass,
                        performance.tileCopyMs
                    )) {
                    log_tile_output_mismatch(
                        params,
                        true,
                        "optimized-tta-normalize",
                        tileX,
                        tileY,
                        tileW,
                        tileH,
                        region,
                        work.downloaded,
                        normalizedPass
                    );
                    failureCode = SuperResNativeCode::TileOutputMismatch;
                    return SuperResTtaMicroBatchStatus::Failed;
                }
                if (!d4_add_ncnn_to_fusion(
                        normalizedPass,
                        kD4Transforms[pass],
                        fusion
                    )) {
                    log_tile_output_mismatch(
                        params,
                        true,
                        "optimized-tta-inverse-shape",
                        tileX,
                        tileY,
                        tileW,
                        tileH,
                        region,
                        work.downloaded,
                        normalizedPass
                    );
                    failureCode = SuperResNativeCode::TileOutputMismatch;
                    return SuperResTtaMicroBatchStatus::Failed;
                }
                return SuperResTtaMicroBatchStatus::Ok;
            }
        );

        if (runResult.status == SuperResTtaMicroBatchStatus::Cancelled) {
            return SuperResNativeCode::Cancelled;
        }
        if (runResult.status != SuperResTtaMicroBatchStatus::Ok) {
            return failureCode;
        }
        if (!d4_finish_ncnn_fusion(fusion, copySource)) {
            log_tile_output_mismatch(
                params,
                true,
                "optimized-tta-fusion",
                tileX,
                tileY,
                tileW,
                tileH,
                region,
                ncnn::Mat{},
                copySource
            );
            return SuperResNativeCode::TileOutputMismatch;
        }
        const SuperResTtaMicroBatchStatus finalBoundary = checkBoundary();
        if (finalBoundary == SuperResTtaMicroBatchStatus::Cancelled) {
            return SuperResNativeCode::Cancelled;
        }
        return finalBoundary == SuperResTtaMicroBatchStatus::Failed
            ? failureCode
            : SuperResNativeCode::Ok;
    } catch (const std::bad_alloc&) {
        return checkBoundary() == SuperResTtaMicroBatchStatus::Cancelled
            ? SuperResNativeCode::Cancelled
            : SuperResNativeCode::OutOfMemory;
    } catch (...) {
        return checkBoundary() == SuperResTtaMicroBatchStatus::Cancelled
            ? SuperResNativeCode::Cancelled
            : SuperResNativeCode::Unsupported;
    }
}

struct NcnnStageRunResult {
    SuperResNativeCode code;
    int totalTiles;
    bool useExplicitVulkanPath;

    NcnnStageRunResult(SuperResNativeCode failureCode)
        : code(failureCode), totalTiles(0), useExplicitVulkanPath(false) {}

    NcnnStageRunResult(
        SuperResNativeCode resultCode,
        int resultTotalTiles,
        bool resultUseExplicitVulkanPath
    ) : code(resultCode),
        totalTiles(resultTotalTiles),
        useExplicitVulkanPath(resultUseExplicitVulkanPath) {}
};

NcnnStageRunResult run_ncnn_stage(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    bool transactionalOutput,
    const SuperResRgbImage& image,
    const ModelFiles& files,
    ncnn::Net& net,
    const SuperResExecutionProfile& executionProfile,
    const SuperResNcnnStageGeometry& geometry,
    RgbOutputTarget& output,
    SuperResNativePerformance& performance
) {
    const bool useCroppedOutput = params.outputMode == kNativeOutputRawCroppedRgbTile;
    const int tileSize = geometry.tileSize;
    const int maximumBandHeight = geometry.maximumBandHeight;
    performance.tileSize = tileSize;
    const int tileOverlap = geometry.tileOverlap;
    const int tilesX = geometry.tilesX;
    const int tilesY = geometry.tilesY;
    const int totalTiles = geometry.totalTiles;
    const int outputW = geometry.outputWidth;
    const int outputH = geometry.outputHeight;
    const int bufferW = geometry.bufferWidth;
    const int bufferH = geometry.bufferHeight;

    const SuperResNativeCode outputInitialization = output.initialize(
        params.outputPath,
        bufferW,
        bufferH,
        maximumBandHeight
    );
    if (outputInitialization != SuperResNativeCode::Ok) {
        return outputInitialization;
    }

    int completed = 0;
    long long coveredPixels = 0;
    const bool useOptimizedVulkanTiles =
        should_use_optimized_vulkan_path(executionProfile.effectiveBackend, useVulkan) &&
        !params.tta;
    const bool useOptimizedVulkanTta = should_run_optimized_vulkan_tta(
        params,
        executionProfile,
        useVulkan,
        transactionalOutput
    );
    const bool useExplicitVulkanPath = useOptimizedVulkanTiles || useOptimizedVulkanTta;
    std::unique_ptr<OptimizedVulkanTtaContext> optimizedTtaContext;
    if (useOptimizedVulkanTiles) {
        const SuperResNativeCode optimizedCode = run_optimized_vulkan_tiles(
            params,
            onProgress,
            runtimeControl,
            image,
            files,
            net,
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
    if (useOptimizedVulkanTta) {
        optimizedTtaContext = std::make_unique<OptimizedVulkanTtaContext>(net);
        if (!optimizedTtaContext->valid()) {
            return SuperResNativeCode::VulkanFailed;
        }
    }
    for (int ty = 0; ty < tilesY; ++ty) {
        for (int tx = 0; tx < tilesX; ++tx) {
            const SuperResRuntimeInferenceControl control = read_runtime_control(
                runtimeControl,
                executionProfile.commandBatchSize
            );
            performance.requestedMaxPipelineDepth = control.maxPipelineDepth;
            if (control.shouldPause) {
                return SuperResNativeCode::Cancelled;
            }
            if (is_cancelled(params.taskId)) {
                return SuperResNativeCode::Cancelled;
            }

            const int tileX = tx * tileSize;
            const int tileY = ty * tileSize;
            const int tileW = std::min(tileSize, image.width() - tileX);
            const int tileH = std::min(tileSize, image.height() - tileY);
            const TileInputRegion region = params.engineType == kEngineWaifu2x
                ? make_waifu2x_tile_region(params, image.width(), image.height(), tileX, tileY, tileW, tileH)
                : params.engineType == kEngineRealCugan
                    ? make_realcugan_tile_region(params, image.width(), image.height(), tileX, tileY, tileW, tileH)
                    : make_overlapped_tile_region(
                        image.width(),
                        image.height(),
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
                ncnn::Extractor extractor = net.create_extractor();
                const auto extractStart = std::chrono::steady_clock::now();
                if (extractor.input(files.inputBlob, input) != 0 ||
                    extractor.extract(files.outputBlob, result) != 0) {
                    return useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Unsupported;
                }
                performance.tileExtractMs += elapsed_ms(extractStart);
                if (!normalize_output_tile_for_copy(
                        result,
                        executionProfile.postprocessThreads,
                        copySource,
                        performance.tileCopyMs
                    )) {
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
            } else if (useOptimizedVulkanTta) {
                const SuperResNativeCode optimizedTtaCode = run_optimized_vulkan_tta_tile(
                    params,
                    runtimeControl,
                    files,
                    net,
                    executionProfile,
                    *optimizedTtaContext,
                    input,
                    tileX,
                    tileY,
                    tileW,
                    tileH,
                    region,
                    copySource,
                    performance
                );
                if (optimizedTtaCode != SuperResNativeCode::Ok) {
                    return optimizedTtaCode;
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

                    ncnn::Extractor extractor = net.create_extractor();
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
                    if (!normalize_output_tile_for_copy(
                            passResult,
                            executionProfile.postprocessThreads,
                            normalizedPass,
                            performance.tileCopyMs
                        )) {
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

            const int runtimeHeadroomPercent = resolve_runtime_headroom_percent(
                params.gpuHeadroomPercent,
                control.thermalHeadroomPercent,
                useVulkan
            );
            if (runtimeHeadroomPercent > 0) {
                sleep_for_gpu_headroom(
                    runtimeHeadroomPercent,
                    std::chrono::steady_clock::now() - tileStart
                );
            }
            if (is_cancelled(params.taskId)) {
                return SuperResNativeCode::Cancelled;
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

    if (optimizedTtaContext) {
        performance.uploadMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            optimizedTtaContext->uploadDuration
        ).count();
        performance.inferenceMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            optimizedTtaContext->inferenceDuration
        ).count();
        performance.downloadMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            optimizedTtaContext->downloadDuration
        ).count();
        performance.tileExtractMs = performance.inferenceMs;
    }

    // The last tile can complete while cancellation is being delivered. Consume it before
    // spending time finalizing the temporary PNG, then arbitrate once more at publication.
    if (is_cancelled(params.taskId)) {
        return SuperResNativeCode::Cancelled;
    }
    emit(onProgress, SuperResNativeStage::Stitching, totalTiles, totalTiles, 0.92f, "Stitching tiles");
    const long long expectedCoveredPixels = geometry.expectedCoveredPixels;
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
    return NcnnStageRunResult{
        SuperResNativeCode::Ok,
        totalTiles,
        useExplicitVulkanPath
    };
}

SuperResNativeCode run_ncnn_image_to_extreme_target_attempt(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    const SuperResRgbImage& image,
    RgbOutputTarget& output
) {
    const auto totalStart = std::chrono::steady_clock::now();
    static const bool executionProfileChecksPass = run_execution_profile_runtime_checks();
    static const bool pixelIoChecksPass = run_pixel_io_runtime_checks();
    static const bool rgbImageChecksPass = run_superres_rgb_image_runtime_checks();
    if (!executionProfileChecksPass || !rgbImageChecksPass) {
        return SuperResNativeCode::InvalidParams;
    }
    if (!pixelIoChecksPass) {
        return SuperResNativeCode::Unsupported;
    }
    if (!image.valid() || params.outputCropLeft < 0 || params.outputCropTop < 0 ||
        params.outputCropWidth <= 0 || params.outputCropHeight <= 0) {
        return SuperResNativeCode::InvalidParams;
    }
    if (read_runtime_control(runtimeControl, params.commandBatchSize).shouldPause ||
        is_cancelled(params.taskId)) {
        return SuperResNativeCode::Cancelled;
    }

    const ModelFiles files = select_model_files(params);
    if (!is_regular_file(files.paramPath) || !is_regular_file(files.binPath)) {
        return SuperResNativeCode::ModelMissing;
    }
    if (!useVulkan && params.engineType == kEngineRealEsrgan &&
        is_large_realesrgan_model_base(params.modelFileBase.c_str())) {
        return SuperResNativeCode::OutOfMemory;
    }
    if (params.tta) {
        static const bool d4RuntimeChecksPass = run_d4_tta_runtime_checks();
        if (!d4RuntimeChecksPass) {
            return SuperResNativeCode::TileOutputMismatch;
        }
    }
    if (!output_pixels_within_budget(image.width(), image.height(), params.scale)) {
        return SuperResNativeCode::OutOfMemory;
    }

    std::unique_lock<std::mutex> gpuLock(gpu_mutex, std::defer_lock);
    if (useVulkan) {
        gpuLock.lock();
        if (!ensure_gpu_instance()) {
            return SuperResNativeCode::VulkanFailed;
        }
    }

    emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "Loading ncnn model");
    const SuperResExecutionProfile executionProfile = resolve_execution_profile(params, useVulkan);
    SuperResNativePerformance performance;
    performance.accelerationMode = useVulkan ? kAccelerationVulkan : 2;
    performance.effectiveBackend = static_cast<int>(executionProfile.effectiveBackend);
    performance.effectivePrecision = static_cast<int>(executionProfile.effectivePrecision);
    performance.fallbackReason = static_cast<int>(executionProfile.fallbackReason);
    performance.retryCount = params.retryCount;
    performance.regionIndex = params.regionIndex;
    const auto modelLoadStart = std::chrono::steady_clock::now();
    LoadedNet loadedNet = load_cached_net(files, useVulkan, executionProfile);
    performance.modelLoadMs = elapsed_ms(modelLoadStart);
    performance.cacheHit = loadedNet.cacheHit;
    performance.pipelineCacheHit = loadedNet.pipelineCacheHit;
    performance.pipelineCacheBytes = static_cast<long long>(loadedNet.pipelineCacheBytes);
    performance.pipelineCacheRebuilt =
        !loadedNet.pipelineCacheHit && loadedNet.pipelineCacheBytes > 0u;
    performance.cacheSize = cached_net_count();
    if (!loadedNet.net) {
        return SuperResNativeCode::ModelMissing;
    }

    const SuperResNcnnStageGeometry geometry = resolve_superres_ncnn_stage_geometry(
        image.width(),
        image.height(),
        params.scale,
        params.tileSize,
        SuperResNcnnStageCrop{
            true,
            params.outputCropLeft,
            params.outputCropTop,
            params.outputCropWidth,
            params.outputCropHeight
        }
    );
    if (geometry.status != SuperResNcnnStageGeometryStatus::Ok) {
        return geometry.status == SuperResNcnnStageGeometryStatus::OutputTooLarge
            ? SuperResNativeCode::OutOfMemory
            : SuperResNativeCode::InvalidParams;
    }
    const NcnnStageRunResult stageResult = run_ncnn_stage(
        params,
        onProgress,
        runtimeControl,
        useVulkan,
        false,
        image,
        files,
        *loadedNet.net,
        executionProfile,
        geometry,
        output,
        performance
    );
    if (stageResult.code != SuperResNativeCode::Ok) {
        return stageResult.code;
    }
    performance.preprocessMs = performance.tileInputMs;
    if (!stageResult.useExplicitVulkanPath) {
        performance.inferenceMs = performance.tileExtractMs;
    }
    performance.postprocessMs = performance.tileCopyMs;
    performance.totalMs = elapsed_ms(totalStart);
    performance.hasValue = true;
    log_native_performance_sample(
        params,
        useVulkan,
        executionProfile,
        performance,
        "extreme_region",
        true
    );
    emit(
        onProgress,
        SuperResNativeStage::ProcessingTile,
        stageResult.totalTiles,
        stageResult.totalTiles,
        0.95f,
        "Extreme tile inference complete",
        performance
    );
    return SuperResNativeCode::Ok;
}

SuperResNativeCode run_ncnn_image_to_extreme_target(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    const SuperResRgbImage& image,
    RgbOutputTarget& output
) {
    return run_superres_tta_attempts(
        params,
        useVulkan,
        false,
        [&](const SuperResNativeParams& attemptParams) {
            return run_ncnn_image_to_extreme_target_attempt(
                attemptParams,
                onProgress,
                runtimeControl,
                useVulkan,
                image,
                output
            );
        },
        [](SuperResNativeCode code) { return code; },
        [&]() {
            return read_runtime_control(runtimeControl, params.commandBatchSize).shouldPause ||
                is_cancelled(params.taskId);
        },
        []() { return SuperResNativeCode::Cancelled; },
        [](SuperResNativeCode) {}
    );
}

SuperResRgbPipelineStageAttempt run_ncnn_image_to_memory_attempt(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    const SuperResRgbImage& image
) {
    static const bool executionProfileChecksPass = run_execution_profile_runtime_checks();
    if (!executionProfileChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Execution profile runtime checks failed");
        return {SuperResNativeCode::InvalidParams, {}};
    }
    static const bool pixelIoChecksPass = run_pixel_io_runtime_checks();
    if (!pixelIoChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Pixel I/O runtime checks failed");
        return {SuperResNativeCode::Unsupported, {}};
    }
    static const bool rgbImageChecksPass = run_superres_rgb_image_runtime_checks();
    if (!rgbImageChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "RGB image runtime checks failed");
        return {SuperResNativeCode::InvalidParams, {}};
    }
    if (read_runtime_control(runtimeControl, params.commandBatchSize).shouldPause ||
        is_cancelled(params.taskId)) {
        return {SuperResNativeCode::Cancelled, {}};
    }

    const ModelFiles files = select_model_files(params);
    if (!is_regular_file(files.paramPath) || !is_regular_file(files.binPath)) {
        return {SuperResNativeCode::ModelMissing, {}};
    }
    if (!useVulkan && params.engineType == kEngineRealEsrgan &&
        is_large_realesrgan_model_base(params.modelFileBase.c_str())) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Rejecting CPU load for large Real-ESRGAN model in memory pipeline scale=%d",
            params.scale
        );
        return {SuperResNativeCode::OutOfMemory, {}};
    }
    if (!image.valid()) {
        return {SuperResNativeCode::InvalidParams, {}};
    }
    if (params.tta) {
        static const bool d4RuntimeChecksPass = run_d4_tta_runtime_checks();
        if (!d4RuntimeChecksPass) {
            return {SuperResNativeCode::TileOutputMismatch, {}};
        }
    }
    if (!output_pixels_within_budget(image.width(), image.height(), params.scale)) {
        return {SuperResNativeCode::OutOfMemory, {}};
    }

    std::unique_lock<std::mutex> gpuLock(gpu_mutex, std::defer_lock);
    if (useVulkan) {
        gpuLock.lock();
        if (!ensure_gpu_instance()) {
            return {SuperResNativeCode::VulkanFailed, {}};
        }
    }

    emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "Loading ncnn model");
    const SuperResExecutionProfile executionProfile = resolve_execution_profile(params, useVulkan);
    SuperResNativePerformance performance;
    performance.accelerationMode = useVulkan ? kAccelerationVulkan : 2;
    performance.effectiveBackend = static_cast<int>(executionProfile.effectiveBackend);
    performance.effectivePrecision = static_cast<int>(executionProfile.effectivePrecision);
    performance.fallbackReason = static_cast<int>(executionProfile.fallbackReason);
    const auto modelLoadStart = std::chrono::steady_clock::now();
    LoadedNet loadedNet = load_cached_net(files, useVulkan, executionProfile);
    performance.modelLoadMs = elapsed_ms(modelLoadStart);
    performance.cacheHit = loadedNet.cacheHit;
    performance.pipelineCacheHit = loadedNet.pipelineCacheHit;
    performance.pipelineCacheBytes = static_cast<long long>(loadedNet.pipelineCacheBytes);
    performance.pipelineCacheRebuilt =
        !loadedNet.pipelineCacheHit && loadedNet.pipelineCacheBytes > 0u;
    performance.cacheSize = cached_net_count();
    performance.retryCount = params.retryCount;
    performance.regionIndex = params.regionIndex;
    std::shared_ptr<ncnn::Net> net = loadedNet.net;
    if (!net) {
        return {SuperResNativeCode::ModelMissing, {}};
    }

    const SuperResNcnnStageGeometry geometry = resolve_superres_ncnn_stage_geometry(
        image.width(),
        image.height(),
        params.scale,
        params.tileSize,
        SuperResNcnnStageCrop{}
    );
    if (geometry.status != SuperResNcnnStageGeometryStatus::Ok) {
        return {
            geometry.status == SuperResNcnnStageGeometryStatus::OutputTooLarge
                ? SuperResNativeCode::OutOfMemory
                : SuperResNativeCode::InvalidParams,
            {}
        };
    }

    MemoryRgbOutput output;
    const NcnnStageRunResult stageResult = run_ncnn_stage(
        params,
        onProgress,
        runtimeControl,
        useVulkan,
        true,
        image,
        files,
        *net,
        executionProfile,
        geometry,
        output,
        performance
    );
    if (stageResult.code != SuperResNativeCode::Ok) {
        return {stageResult.code, {}};
    }
    SuperResRgbImage next = output.take_image();
    if (!next.valid()) {
        return {SuperResNativeCode::OutOfMemory, {}};
    }
    return {SuperResNativeCode::Ok, std::move(next)};
}

SuperResRgbPipelineStageAttempt run_ncnn_image_to_memory(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    const SuperResRgbImage& image
) {
    return run_superres_tta_attempts(
        params,
        useVulkan,
        true,
        [&](const SuperResNativeParams& attemptParams) {
            return run_ncnn_image_to_memory_attempt(
                attemptParams,
                onProgress,
                runtimeControl,
                useVulkan,
                image
            );
        },
        [](const SuperResRgbPipelineStageAttempt& result) { return result.code; },
        [&]() {
            return read_runtime_control(runtimeControl, params.commandBatchSize).shouldPause ||
                is_cancelled(params.taskId);
        },
        []() { return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Cancelled, {}}; },
        [](SuperResNativeCode code) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Optimized TTA memory attempt failed code=%d; retrying complete safe TTA",
                static_cast<int>(code)
            );
        }
    );
}

SuperResNativeCode run_ncnn_image_attempt(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    const SuperResRgbImage& image,
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
    static const bool atomicPublishChecksPass = run_atomic_publish_runtime_checks();
    if (!atomicPublishChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Atomic publish runtime checks failed");
        return SuperResNativeCode::OutputFailed;
    }
    static const bool rgbImageChecksPass = run_superres_rgb_image_runtime_checks();
    if (!rgbImageChecksPass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "RGB image runtime checks failed");
        return SuperResNativeCode::InvalidParams;
    }
    if (read_runtime_control(runtimeControl, params.commandBatchSize).shouldPause) {
        return SuperResNativeCode::Cancelled;
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

    if (!image.valid()) {
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

    if (!output_pixels_within_budget(image.width(), image.height(), params.scale)) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "Rejecting output allocation image=%dx%d scale=%d maxPixels=%lld",
            image.width(),
            image.height(),
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
    performance.pipelineCacheHit = loadedNet.pipelineCacheHit;
    performance.pipelineCacheBytes = static_cast<long long>(loadedNet.pipelineCacheBytes);
    performance.pipelineCacheRebuilt =
        !loadedNet.pipelineCacheHit && loadedNet.pipelineCacheBytes > 0u;
    performance.cacheSize = cached_net_count();
    performance.retryCount = params.retryCount;
    performance.regionIndex = params.regionIndex;
    std::shared_ptr<ncnn::Net> net = loadedNet.net;
    if (!net) {
        return SuperResNativeCode::ModelMissing;
    }

    const bool useCroppedOutput = params.outputMode == kNativeOutputRawCroppedRgbTile;
    const SuperResNcnnStageGeometry geometry = resolve_superres_ncnn_stage_geometry(
        image.width(),
        image.height(),
        params.scale,
        params.tileSize,
        SuperResNcnnStageCrop{
            useCroppedOutput,
            params.outputCropLeft,
            params.outputCropTop,
            params.outputCropWidth,
            params.outputCropHeight
        }
    );
    if (geometry.status != SuperResNcnnStageGeometryStatus::Ok) {
        return geometry.status == SuperResNcnnStageGeometryStatus::OutputTooLarge
            ? SuperResNativeCode::OutOfMemory
            : SuperResNativeCode::InvalidParams;
    }
    std::unique_ptr<RgbOutputTarget> output;
    try {
        output = useCroppedOutput
            ? std::make_unique<MemoryRgbOutput>()
            : make_streaming_png_output();
    } catch (...) {
        return SuperResNativeCode::OutOfMemory;
    }
    if (!output) {
        return SuperResNativeCode::OutOfMemory;
    }
    const NcnnStageRunResult stageResult = run_ncnn_stage(
        params,
        onProgress,
        runtimeControl,
        useVulkan,
        true,
        image,
        files,
        *net,
        executionProfile,
        geometry,
        *output,
        performance
    );
    if (stageResult.code != SuperResNativeCode::Ok) {
        return stageResult.code;
    }
    const int totalTiles = stageResult.totalTiles;
    const bool useExplicitVulkanPath = stageResult.useExplicitVulkanPath;
    emit(onProgress, SuperResNativeStage::Saving, totalTiles, totalTiles, 0.96f, "Saving output");
    SuperResStagedOutput stagedOutput(params.outputPath);
    const SuperResNativeCode finishCode = output->finish_attempt(stagedOutput);
    if (finishCode != SuperResNativeCode::Ok) {
        return finishCode;
    }
    // The target includes temporary-file write/flush/fsync/close time only. Final publication
    // remains below, after the potentially throwing performance callback.
    const long long stagingMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        output->encoding_duration()
    ).count();
    performance.preprocessMs = performance.tileInputMs;
    if (!useExplicitVulkanPath) {
        performance.inferenceMs = performance.tileExtractMs;
    }
    performance.postprocessMs = performance.tileCopyMs;
    const SuperResSafePublishTiming safeTiming = make_superres_safe_publish_timing(
        stagingMs,
        elapsed_ms(totalStart)
    );
    performance.saveMs = safeTiming.saveMs;
    performance.encodeMs = safeTiming.encodeMs;
    performance.totalMs = safeTiming.totalMs;
    performance.hasValue = true;
    log_native_performance_sample(
        params,
        useVulkan,
        executionProfile,
        performance,
        "pre_publish_safe",
        true
    );

    const auto emitPerformance = [&]() {
        emit(onProgress,
             SuperResNativeStage::Saving,
             totalTiles,
             totalTiles,
             0.99f,
             "Native performance sample (pre-publish safe)",
             performance);
    };

    // Publication and cancellation share the registry mutex as their linearization point. The
    // staged-output guard removes .part during exception unwinding; no Java call follows publish.
    const SuperResFinalizationResult finalization = finalize_superres_output_after_performance(
        stagedOutput,
        task_registry,
        params.taskId,
        emitPerformance
    );
    if (finalization.outcome == SuperResTaskPublishOutcome::Cancelled) {
        return SuperResNativeCode::Cancelled;
    }
    if (finalization.outcome != SuperResTaskPublishOutcome::Published) {
        return SuperResNativeCode::OutputFailed;
    }

    const long long actualTotalMs = elapsed_ms(totalStart);
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "OutputFinalized task=%s mode=%d safeSaveMs=%lld safeTotalMs=%lld publishMs=%lld actualTotalMs=%lld",
        params.taskId.c_str(),
        params.outputMode,
        performance.saveMs,
        performance.totalMs,
        finalization.publishMs,
        actualTotalMs
    );

    return SuperResNativeCode::Ok;
}

SuperResNativeCode run_ncnn_image(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl,
    bool useVulkan,
    const SuperResRgbImage& image,
    long long decodeMs
) {
    return run_superres_tta_attempts(
        params,
        useVulkan,
        true,
        [&](const SuperResNativeParams& attemptParams) {
            return run_ncnn_image_attempt(
                attemptParams,
                onProgress,
                runtimeControl,
                useVulkan,
                image,
                decodeMs
            );
        },
        [](SuperResNativeCode code) { return code; },
        [&]() {
            return read_runtime_control(runtimeControl, params.commandBatchSize).shouldPause ||
                is_cancelled(params.taskId);
        },
        []() { return SuperResNativeCode::Cancelled; },
        [](SuperResNativeCode code) {
            __android_log_print(
                ANDROID_LOG_WARN,
                kLogTag,
                "Optimized TTA attempt failed code=%d; retrying complete safe TTA",
                static_cast<int>(code)
            );
        }
    );
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
    snapshot.gpuVendorId = vulkan_uint32_to_int64(gpuInfo.vendor_id());
    snapshot.gpuDeviceId = vulkan_uint32_to_int64(gpuInfo.device_id());
    snapshot.gpuApiVersion = vulkan_uint32_to_int64(gpuInfo.api_version());
    snapshot.gpuDriverId = vulkan_uint32_to_int64(gpuInfo.driver_id());
    snapshot.gpuDriverVersionRaw = vulkan_uint32_to_int64(gpuInfo.driver_version());
    snapshot.pipelineCacheUuid = pipeline_cache_uuid_hex(gpuInfo.pipeline_cache_uuid());
    snapshot.subgroupSize = normalize_gpu_rough_score(gpuInfo.subgroup_size());
    snapshot.subgroupMinSize = normalize_gpu_rough_score(gpuInfo.min_subgroup_size());
    snapshot.subgroupMaxSize = normalize_gpu_rough_score(gpuInfo.max_subgroup_size());
    snapshot.computeQueueCount = normalize_gpu_rough_score(gpuInfo.compute_queue_count());
    snapshot.maxComputeSharedMemoryBytes =
        vulkan_uint32_to_int64(gpuInfo.max_shared_memory_size());
    snapshot.supportsSubgroupOps = gpuInfo.support_subgroup_ops() != 0u;
    snapshot.supportsSubgroupSizeControl = gpuInfo.support_subgroup_size_control();
    snapshot.supportsComputeFullSubgroups = gpuInfo.support_compute_full_subgroups();
    snapshot.corruptedOnlinePipelineCache = gpuInfo.bug_corrupted_online_pipeline_cache();
    snapshot.storageBufferNoL1 = gpuInfo.bug_storage_buffer_no_l1();
    snapshot.bufferImageLoadZero = gpuInfo.bug_buffer_image_load_zero();
    snapshot.implicitFp16Arithmetic = gpuInfo.bug_implicit_fp16_arithmetic();
    snapshot.supportsVulkan = true;
    snapshot.supportsFp16Storage = gpuInfo.support_fp16_storage();
    snapshot.supportsFp16Arithmetic = gpuInfo.support_fp16_arithmetic();
    return snapshot;
}

long long read_superres_gpu_heap_budget_mib() {
    std::lock_guard<std::mutex> gpuLock(gpu_mutex);
    if (!ensure_gpu_instance()) {
        return 0;
    }
    const int gpuIndex = ncnn::get_default_gpu_index();
    const int gpuCount = ncnn::get_gpu_count();
    if (gpuIndex < 0 || gpuIndex >= gpuCount) {
        return 0;
    }
    const ncnn::VulkanDevice* device = ncnn::get_gpu_device(gpuIndex);
    return device == nullptr ? 0 : static_cast<long long>(device->get_heap_budget());
}

namespace {
bool task_registry_checks_pass() {
    static const bool taskRegistryChecksPass = run_task_registry_runtime_checks();
    return taskRegistryChecksPass;
}

SuperResProcessInternalDependencies
make_process_dependencies(const SuperResNativeParams& params,
                          std::function<SuperResRgbImage()> loadImage) {
    SuperResProcessInternalDependencies dependencies;
    dependencies.loadImage = std::move(loadImage);
    dependencies.runImage = [](const SuperResNativeParams& runParams,
                               const SuperResProgressCallback& onProgress,
                               const SuperResRuntimeControlCallback& runtimeControl,
                               bool useVulkan,
                               const SuperResRgbImage& image,
                               long long decodeMs) {
        return run_ncnn_image(runParams, onProgress, runtimeControl, useVulkan, image, decodeMs);
    };
    dependencies.registerTask = [](const std::string& taskId) {
        return task_registry.register_task(taskId);
    };
    dependencies.completeTask = [](const std::string& taskId) {
        task_registry.complete_task(taskId);
    };
    dependencies.inputExists = [](const std::string& inputPath) {
        return is_regular_file(inputPath);
    };
    dependencies.onEvent = [&params](SuperResProcessInternalEvent event, SuperResNativeCode code) {
        if (event == SuperResProcessInternalEvent::ForceCpu) {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "Skipping Vulkan model load to avoid native GPU "
                                "loader crash engine=%d scale=%d noise=%d mode=%d",
                                params.engineType,
                                params.scale,
                                params.noise,
                                params.accelerationMode);
        } else {
            __android_log_print(ANDROID_LOG_WARN,
                                kLogTag,
                                "AUTO retrying CPU after GPU code=%d engine=%d scale=%d noise=%d",
                                static_cast<int>(code),
                                params.engineType,
                                params.scale,
                                params.noise);
        }
    };
    return dependencies;
}
} // namespace

SuperResNativeCode process_superres(const SuperResNativeParams& params,
                                    const SuperResProgressCallback& onProgress,
                                    const SuperResRuntimeControlCallback& runtimeControl) {
    if (!task_registry_checks_pass()) {
        return SuperResNativeCode::InvalidParams;
    }
    SuperResProcessInternalDependencies dependencies = make_process_dependencies(
        params,
        [&params]() { return load_rgb_image(params.inputPath); }
    );
    return process_superres_internal(
        params,
        onProgress,
        runtimeControl,
        true,
        dependencies
    );
}

SuperResNativeCode process_superres_pipeline(
    const std::string& rootTaskId,
    const std::vector<SuperResNativeParams>& passes,
    size_t transportBudgetBytes,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
) {
    if (!task_registry_checks_pass()) {
        return SuperResNativeCode::InvalidParams;
    }

    long long decodeMs = 0;
    SuperResRgbPipelineDependencies dependencies;
    dependencies.inputExists = [](const std::string& inputPath) {
        return is_regular_file(inputPath);
    };
    dependencies.registerTask = [](const std::string& taskId) {
        return task_registry.register_task(taskId);
    };
    dependencies.completeTask = [](const std::string& taskId) {
        task_registry.complete_task(taskId);
    };
    dependencies.shouldStop = [&]() {
        if (is_cancelled(rootTaskId)) {
            return true;
        }
        const int defaultBatchSize = passes.empty() ? 1 : passes.front().commandBatchSize;
        return read_runtime_control(runtimeControl, defaultBatchSize).shouldPause;
    };
    dependencies.loadImage = [&]() {
        emit(onProgress, SuperResNativeStage::Preparing, 0, 0, 0.0f, "Preparing native pipeline");
        emit(onProgress, SuperResNativeStage::Analyzing, 0, 0, 0.04f, "Decoding input image");
        const auto decodeStart = std::chrono::steady_clock::now();
        SuperResRgbImage image = load_rgb_image(passes.front().inputPath);
        decodeMs = elapsed_ms(decodeStart);
        return image;
    };
    dependencies.onEvent = [](SuperResRgbPipelineEvent event, size_t stageIndex) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            event == SuperResRgbPipelineEvent::ForceCpu
                ? "Pipeline stage forcing CPU stage=%zu"
                : "Pipeline stage AUTO retrying CPU stage=%zu",
            stageIndex
        );
    };
    dependencies.runStage = [
        &onProgress,
        &runtimeControl,
        &decodeMs
    ](
        const SuperResNativeParams& params,
        const SuperResRgbImage& input,
        bool useVulkan,
        bool finalStage,
        size_t stageIndex,
        size_t stageCount
    ) {
        const SuperResProgressCallback stageProgress = [
            &onProgress,
            stageIndex,
            stageCount
        ](
            SuperResNativeStage stage,
            int currentTile,
            int totalTiles,
            float progress,
            const std::string& message,
            const SuperResNativePerformance& performance
        ) {
            if (!onProgress || stageCount == 0u) {
                return;
            }
            const float boundedProgress = std::clamp(progress, 0.0f, 1.0f);
            const float pipelineProgress = (
                static_cast<float>(stageIndex) + boundedProgress
            ) / static_cast<float>(stageCount);
            onProgress(
                stage,
                currentTile,
                totalTiles,
                pipelineProgress,
                message,
                performance
            );
        };
        if (finalStage) {
            return SuperResRgbPipelineStageAttempt{
                run_ncnn_image(
                    params,
                    stageProgress,
                    runtimeControl,
                    useVulkan,
                    input,
                    decodeMs
                ),
                {}
            };
        }
        return run_ncnn_image_to_memory(
            params,
            stageProgress,
            runtimeControl,
            useVulkan,
            input
        );
    };

    const SuperResNativeCode code = process_superres_rgb_pipeline_internal(
        rootTaskId,
        passes,
        transportBudgetBytes,
        dependencies
    );
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "PipelineTransport task=%s transportMode=%s passCount=%zu budgetBytes=%zu code=%d",
        rootTaskId.c_str(),
        code == SuperResNativeCode::PipelineFallbackRequired ? "png-fallback" : "rgb-memory",
        passes.size(),
        transportBudgetBytes,
        static_cast<int>(code)
    );
    return code;
}

SuperResNativeCode process_superres_rgb(
    const SuperResNativeParams& params,
    SuperResRgbImage image,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
) {
    if (!image.valid()) {
        return SuperResNativeCode::InvalidParams;
    }
    if (!task_registry_checks_pass()) {
        return SuperResNativeCode::InvalidParams;
    }
    SuperResProcessInternalDependencies dependencies = make_process_dependencies(
        params,
        [&image]() mutable { return std::move(image); }
    );
    return process_superres_internal(
        params,
        onProgress,
        runtimeControl,
        false,
        dependencies
    );
}

SuperResNativeCode process_superres_rgb(
    const SuperResNativeParams& params,
    int width,
    int height,
    const unsigned char* rgb,
    size_t rgbSize,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
) {
    size_t rowStrideBytes = 0;
    size_t byteSize = 0;
    if (width <= 0 || height <= 0 || rgb == nullptr ||
        !checked_superres_rgb_layout(
            static_cast<size_t>(width),
            static_cast<size_t>(height),
            rowStrideBytes,
            byteSize
        ) || rgbSize != byteSize) {
        return SuperResNativeCode::InvalidParams;
    }
    auto pixels = SuperResRgbPixels(
        new (std::nothrow) unsigned char[byteSize],
        SuperResRgbDeleter{}
    );
    if (pixels == nullptr) {
        return SuperResNativeCode::OutOfMemory;
    }
    std::memcpy(pixels.get(), rgb, byteSize);
    return process_superres_rgb(
        params,
        SuperResRgbImage(
            width,
            height,
            rowStrideBytes,
            byteSize,
            std::move(pixels)
        ),
        onProgress,
        runtimeControl
    );
}

uint64_t begin_extreme_png_session(
    const std::string& taskId,
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    int maximumBandHeight,
    const std::vector<SuperResExtremePngTile>& tiles
) {
    try {
        std::vector<SuperResExtremeTileConfig> configs;
        configs.reserve(tiles.size());
        for (const SuperResExtremePngTile& tile : tiles) {
            configs.push_back(SuperResExtremeTileConfig{
                tile.index,
                tile.outputX,
                tile.outputY,
                tile.outputWidth,
                tile.outputHeight
            });
        }

        SuperResExtremeSessionDependencies dependencies;
        dependencies.makeOutput = []() { return make_streaming_png_output(); };
        dependencies.registerTask = [](const std::string& value) {
            return task_registry.register_task(value);
        };
        dependencies.completeTask = [](const std::string& value) {
            task_registry.complete_task(value);
        };
        dependencies.consumeCancelled = [](const std::string& value) {
            return is_cancelled(value);
        };
        dependencies.publishOutput = [](
            SuperResStagedOutput& staged,
            const std::string& value
        ) {
            return staged.publish_or_cancel(task_registry, value);
        };

        SuperResNativeCode beginCode = SuperResNativeCode::InvalidParams;
        std::unique_ptr<SuperResExtremePngSession> created =
            SuperResExtremePngSession::create(
                SuperResExtremeSessionConfig{
                    taskId,
                    outputPath,
                    outputWidth,
                    outputHeight,
                    maximumBandHeight,
                    std::move(configs)
                },
                std::move(dependencies),
                beginCode
            );
        if (created == nullptr || beginCode != SuperResNativeCode::Ok) {
            return 0u;
        }
        std::shared_ptr<SuperResExtremePngSession> session(std::move(created));
        const uint64_t handle = extreme_session_registry.insert(session);
        if (handle == 0u) {
            session->abort();
        }
        return handle;
    } catch (...) {
        return 0u;
    }
}

SuperResNativeCode process_extreme_png_session_rgb(
    uint64_t handle,
    const SuperResNativeParams& params,
    SuperResRgbImage image,
    const SuperResProgressCallback& onProgress,
    const SuperResRuntimeControlCallback& runtimeControl
) {
    std::shared_ptr<SuperResExtremePngSession> session =
        extreme_session_registry.find(handle);
    if (session == nullptr || !image.valid()) {
        return SuperResNativeCode::InvalidParams;
    }

    SuperResNativeParams normalized = params;
    normalized.taskId = session->task_id();
    normalized.inputPath.clear();
    normalized.outputPath.clear();
    normalized.outputMode = kNativeOutputRawCroppedRgbTile;
    return session->process_tile(
        normalized.regionIndex,
        normalized.outputCropWidth,
        normalized.outputCropHeight,
        [&](const SuperResExtremeTileConfig&, RgbOutputTarget& output) {
            const bool vulkanRequested =
                normalized.accelerationMode == kAccelerationVulkan ||
                normalized.accelerationMode == kAccelerationAuto;
            const bool forceCpu = vulkanRequested &&
                should_force_cpu_before_vulkan_model_load(normalized.engineType);
            SuperResNativeCode code = run_ncnn_image_to_extreme_target(
                normalized,
                onProgress,
                runtimeControl,
                vulkanRequested && !forceCpu,
                image,
                output
            );
            if (vulkanRequested && !forceCpu &&
                normalized.accelerationMode != kAccelerationVulkan &&
                should_retry_cpu_after_gpu_code(
                    normalized.accelerationMode,
                    static_cast<int>(code)
                )) {
                code = run_ncnn_image_to_extreme_target(
                    normalized,
                    onProgress,
                    runtimeControl,
                    false,
                    image,
                    output
                );
            }
            return code;
        }
    );
}

SuperResNativeCode finish_extreme_png_session(uint64_t handle) {
    std::shared_ptr<SuperResExtremePngSession> session =
        extreme_session_registry.find(handle);
    if (session == nullptr) {
        return SuperResNativeCode::InvalidParams;
    }
    const SuperResNativeCode code = session->finish();
    if (session->terminal()) {
        (void)extreme_session_registry.remove(handle);
    }
    return code;
}

void abort_extreme_png_session(uint64_t handle) {
    std::shared_ptr<SuperResExtremePngSession> session =
        extreme_session_registry.remove(handle);
    if (session != nullptr) {
        session->abort();
    }
}

void cancel_superres(const std::string& taskId) {
    task_registry.cancel(taskId);
}

bool configure_superres_pipeline_cache(const std::string& rootDirectory, bool enabled) {
    std::lock_guard<std::mutex> gpuLock(gpu_mutex);
    std::shared_ptr<SuperResPipelineCacheStore> configuredStore;
    if (enabled) {
        configuredStore = std::make_shared<SuperResPipelineCacheStore>(
            std::filesystem::path(rootDirectory)
        );
        if (!configuredStore->configured()) {
            return false;
        }
    }
    {
        std::lock_guard<std::mutex> storeLock(pipeline_cache_store_mutex);
        pipeline_cache_store = std::move(configuredStore);
    }
    clear_cached_net();
    return true;
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
    const std::string temporaryPath = outputPath + ".part";
    std::remove(temporaryPath.c_str());

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

    FILE* output = std::fopen(temporaryPath.c_str(), "wb");
    if (output == nullptr) {
        for (OpenTile& openTile : openTiles) {
            std::fclose(openTile.file);
        }
        return SuperResNativeCode::OutputFailed;
    }

    z_stream stream{};
    if (deflateInit(&stream, Z_BEST_SPEED) != Z_OK) {
        std::fclose(output);
        std::remove(temporaryPath.c_str());
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
    if (ok && std::fflush(output) != 0) ok = false;
    if (ok && ::fsync(::fileno(output)) != 0) ok = false;
    ok = std::fclose(output) == 0 && ok;
    for (OpenTile& openTile : openTiles) {
        std::fclose(openTile.file);
    }

    if (!ok) {
        std::remove(temporaryPath.c_str());
        return SuperResNativeCode::OutputFailed;
    }
    return publish_atomic_file(temporaryPath, outputPath) == SuperResAtomicPublishStatus::Ok
        ? SuperResNativeCode::Ok
        : SuperResNativeCode::OutputFailed;
}
