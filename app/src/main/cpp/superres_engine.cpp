// 这个文件负责在原生层执行基于 ncnn 的超分推理，包括模型选择、图像分块、推理拼接与结果输出。
#include "superres_engine.h"
#include "superres_tile_geometry.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

#include "net.h"
#include "gpu.h"
#include "mat.h"

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {
constexpr const char* kLogTag = "LumaSRNative";
constexpr int kEngineWaifu2x = 0;
constexpr int kEngineRealCugan = 1;
constexpr int kEngineRealEsrgan = 2;
constexpr int kAccelerationAuto = 0;
constexpr int kAccelerationVulkan = 1;

std::mutex cancel_mutex;
std::mutex gpu_mutex;
std::mutex net_cache_mutex;
std::unordered_set<std::string> cancelled_tasks;
bool gpu_instance_created = false;

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

NetCacheEntry net_cache;

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

int clamp_gpu_headroom_percent(int value) {
    return std::min(10, std::max(5, value));
}

int inference_threads(bool useVulkan) {
    if (useVulkan) {
        return 1;
    }
    const unsigned int hardware = std::thread::hardware_concurrency();
    const int available = hardware == 0 ? 2 : static_cast<int>(hardware);
    return std::min(4, std::max(1, available - 1));
}

bool ensure_gpu_instance() {
    if (gpu_instance_created) {
        return ncnn::get_gpu_count() > 0;
    }
    if (ncnn::create_gpu_instance() != 0 || ncnn::get_gpu_count() <= 0) {
        return false;
    }
    gpu_instance_created = true;
    return true;
}

std::string make_net_cache_key(const ModelFiles& files, bool useVulkan, int threadCount) {
    return files.paramPath + "|" + files.binPath + "|" + (useVulkan ? "vulkan" : "cpu") + "|" + std::to_string(threadCount);
}

LoadedNet load_cached_net(
    const ModelFiles& files,
    bool useVulkan,
    int threadCount
) {
    const std::string key = make_net_cache_key(files, useVulkan, threadCount);
    {
        std::lock_guard<std::mutex> lock(net_cache_mutex);
        if (net_cache.key == key && net_cache.net) {
            return LoadedNet{net_cache.net, true};
        }
    }

    auto net = std::make_shared<ncnn::Net>();
    net->opt.num_threads = threadCount;
    net->opt.use_packing_layout = true;
    net->opt.use_vulkan_compute = useVulkan;
    if (useVulkan) {
        net->opt.use_fp16_packed = true;
        net->opt.use_fp16_storage = true;
        // Keep arithmetic in fp32 like the upstream waifu2x/RealCUGAN Vulkan pipelines.
        // Some desktop/emulator Vulkan stacks advertise fp16 arithmetic but crash on CUnet.
        net->opt.use_fp16_arithmetic = false;
        net->set_vulkan_device(ncnn::get_default_gpu_index());
    }
    if (net->load_param(files.paramPath.c_str()) != 0 || net->load_model(files.binPath.c_str()) != 0) {
        return LoadedNet{};
    }

    std::lock_guard<std::mutex> lock(net_cache_mutex);
    net_cache = NetCacheEntry{key, net};
    return LoadedNet{net_cache.net, false};
}

void clear_cached_net() {
    std::lock_guard<std::mutex> lock(net_cache_mutex);
    net_cache = NetCacheEntry{};
}

void sleep_for_gpu_headroom(
    int gpuHeadroomPercent,
    const std::chrono::steady_clock::duration& elapsed
) {
    const int headroom = clamp_gpu_headroom_percent(gpuHeadroomPercent);
    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(elapsed).count();
    if (elapsedMs <= 0) {
        return;
    }
    const long long sleepMs = std::min<long long>(20, std::max<long long>(1, elapsedMs * headroom / (100 - headroom)));
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

float clamp01(float value) {
    if (value < 0.0f) {
        return 0.0f;
    }
    if (value > 1.0f) {
        return 1.0f;
    }
    return value;
}

unsigned char to_u8(float normalized) {
    return static_cast<unsigned char>(std::min(255.0f, std::max(0.0f, normalized * 255.0f + 0.5f)));
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
    for (int c = 0; c < 3; ++c) {
        ncnn::Mat channel = tile.channel(c);
        for (int y = 0; y < inputH; ++y) {
            float* row = channel.row(y);
            const int sourceY = std::min(image.height - 1, std::max(0, tileY + y));
            for (int x = 0; x < inputW; ++x) {
                const int sourceX = std::min(image.width - 1, std::max(0, tileX + x));
                const size_t offset = (static_cast<size_t>(sourceY) * image.width + sourceX) * 3 + c;
                row[x] = static_cast<float>(image.rgb[offset]) / 255.0f;
            }
        }
    }
    return tile;
}

bool copy_output_tile(
    const ncnn::Mat& tile,
    std::vector<unsigned char>& output,
    long long& coveredPixels,
    int outputW,
    int outputH,
    int srcX,
    int srcY,
    int dstX,
    int dstY,
    int requestedCopyW,
    int requestedCopyH,
    bool useOriginOutputWindow
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
        dstX + requestedCopyW > outputW ||
        dstY + requestedCopyH > outputH) {
        return false;
    }

    for (int c = 0; c < 3; ++c) {
        const ncnn::Mat channel = tile.channel(c);
        for (int y = 0; y < requestedCopyH; ++y) {
            const float* row = channel.row(srcY + y);
            for (int x = 0; x < requestedCopyW; ++x) {
                const size_t offset = (static_cast<size_t>(dstY + y) * outputW + dstX + x) * 3 + c;
                output[offset] = to_u8(clamp01(row[srcX + x]));
            }
        }
    }
    coveredPixels += static_cast<long long>(requestedCopyW) * requestedCopyH;
    return true;
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

SuperResNativeCode run_ncnn(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    bool useVulkan
) {
    const auto totalStart = std::chrono::steady_clock::now();
    SuperResNativePerformance performance;
    performance.accelerationMode = useVulkan ? kAccelerationVulkan : 2;

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

    emit(onProgress, SuperResNativeStage::Analyzing, 0, 0, 0.04f, "Decoding input image");
    const auto decodeStart = std::chrono::steady_clock::now();
    LoadedImage image = load_rgb_image(params.inputPath);
    performance.decodeMs = elapsed_ms(decodeStart);
    if (image.rgb.empty()) {
        return SuperResNativeCode::Unsupported;
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
    const int threadCount = inference_threads(useVulkan);
    const auto modelLoadStart = std::chrono::steady_clock::now();
    LoadedNet loadedNet = load_cached_net(files, useVulkan, threadCount);
    performance.modelLoadMs = elapsed_ms(modelLoadStart);
    performance.cacheHit = loadedNet.cacheHit;
    std::shared_ptr<ncnn::Net> net = loadedNet.net;
    if (!net) {
        return SuperResNativeCode::ModelMissing;
    }

    const int tileSize = std::max(32, params.tileSize);
    performance.tileSize = tileSize;
    const int tileOverlap = std::min(32, std::max(8, tileSize / 4));
    const int tilesX = (image.width + tileSize - 1) / tileSize;
    const int tilesY = (image.height + tileSize - 1) / tileSize;
    const int totalTiles = tilesX * tilesY;
    const int outputW = image.width * params.scale;
    const int outputH = image.height * params.scale;

    std::vector<unsigned char> output;
    try {
        output.resize(static_cast<size_t>(outputW) * outputH * 3);
    } catch (...) {
        return SuperResNativeCode::OutOfMemory;
    }
    if (output.empty()) {
        return SuperResNativeCode::OutOfMemory;
    }

    int completed = 0;
    long long coveredPixels = 0;
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
            const auto tileStart = std::chrono::steady_clock::now();

            ncnn::Extractor extractor = net->create_extractor();
            const auto extractStart = std::chrono::steady_clock::now();
            if (extractor.input(files.inputBlob, input) != 0 ||
                extractor.extract(files.outputBlob, result) != 0) {
                return useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Unsupported;
            }
            performance.tileExtractMs += elapsed_ms(extractStart);
            ncnn::Mat copySource;
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

            const auto copyStart = std::chrono::steady_clock::now();
            if (!copy_output_tile(
                    copySource,
                    output,
                    coveredPixels,
                    outputW,
                    outputH,
                    region.cropX,
                    region.cropY,
                    region.dstX,
                    region.dstY,
                    region.copyW,
                    region.copyH,
                    params.engineType == kEngineWaifu2x || params.engineType == kEngineRealCugan
                )) {
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
            performance.tileCopyMs += elapsed_ms(copyStart);
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

    emit(onProgress, SuperResNativeStage::Stitching, totalTiles, totalTiles, 0.92f, "Stitching tiles");
    if (coveredPixels != static_cast<long long>(outputW) * outputH) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kLogTag,
            "TileOutputMismatch reason=coverage engine=%d scale=%d noise=%d accel=%s output=%dx%d covered=%lld",
            params.engineType,
            params.scale,
            params.noise,
            acceleration_name(useVulkan),
            outputW,
            outputH,
            coveredPixels
        );
        return SuperResNativeCode::TileOutputMismatch;
    }
    emit(onProgress, SuperResNativeStage::Saving, totalTiles, totalTiles, 0.96f, "Saving output");
    // The output buffer owns RGB bytes until stbi_write_png has synchronously copied them to disk.
    const auto saveStart = std::chrono::steady_clock::now();
    if (stbi_write_png(params.outputPath.c_str(), outputW, outputH, 3, output.data(), outputW * 3) == 0) {
        return SuperResNativeCode::OutputFailed;
    }
    performance.saveMs = elapsed_ms(saveStart);
    performance.totalMs = elapsed_ms(totalStart);
    performance.hasValue = true;
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Performance task=%s model=%s accel=%s cacheHit=%d tile=%d decodeMs=%lld modelLoadMs=%lld tileInputMs=%lld tileExtractMs=%lld tileCopyMs=%lld saveMs=%lld totalMs=%lld",
        params.taskId.c_str(),
        params.modelFileBase.c_str(),
        acceleration_name(useVulkan),
        performance.cacheHit ? 1 : 0,
        performance.tileSize,
        performance.decodeMs,
        performance.modelLoadMs,
        performance.tileInputMs,
        performance.tileExtractMs,
        performance.tileCopyMs,
        performance.saveMs,
        performance.totalMs
    );
    emit(onProgress, SuperResNativeStage::Saving, totalTiles, totalTiles, 0.99f, "Native performance sample", performance);

    return is_cancelled(params.taskId) ? SuperResNativeCode::Cancelled : SuperResNativeCode::Ok;
}
}

SuperResNativeCode process_superres(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress
) {
    {
        std::lock_guard<std::mutex> lock(cancel_mutex);
        cancelled_tasks.erase(params.taskId);
    }

    if (params.taskId.empty() || params.inputPath.empty() || params.outputPath.empty() ||
        params.modelDir.empty() || params.scale <= 0 || params.tileSize <= 0) {
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

    if (!is_regular_file(params.inputPath)) {
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
            return run_ncnn(params, onProgress, false);
        }
        SuperResNativeCode code = run_ncnn(params, onProgress, true);
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

    return run_ncnn(params, onProgress, false);
}

void cancel_superres(const std::string& taskId) {
    std::lock_guard<std::mutex> lock(cancel_mutex);
    cancelled_tasks.insert(taskId);
}

void clear_superres_cache() {
    std::lock_guard<std::mutex> gpuLock(gpu_mutex);
    clear_cached_net();
}
