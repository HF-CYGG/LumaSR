// 这个文件负责在原生层执行基于 ncnn 的超分推理，包括模型选择、图像分块、推理拼接与结果输出。
#include "superres_engine.h"

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

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

namespace {
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

struct TileInputRegion {
    int inputX;
    int inputY;
    int inputW;
    int inputH;
    int cropX;
    int cropY;
    int dstX;
    int dstY;
    int copyW;
    int copyH;
};

NetCacheEntry net_cache;

bool is_regular_file(const std::string& path) {
    std::error_code error;
    return std::filesystem::is_regular_file(std::filesystem::path(path), error) && !error;
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

std::shared_ptr<ncnn::Net> load_cached_net(
    const ModelFiles& files,
    bool useVulkan,
    int threadCount
) {
    const std::string key = make_net_cache_key(files, useVulkan, threadCount);
    {
        std::lock_guard<std::mutex> lock(net_cache_mutex);
        if (net_cache.key == key && net_cache.net) {
            return net_cache.net;
        }
    }

    auto net = std::make_shared<ncnn::Net>();
    net->opt.num_threads = threadCount;
    net->opt.use_packing_layout = true;
    net->opt.use_vulkan_compute = useVulkan;
    if (useVulkan) {
        net->opt.use_fp16_packed = true;
        net->opt.use_fp16_storage = true;
        net->opt.use_fp16_arithmetic = true;
        net->set_vulkan_device(ncnn::get_default_gpu_index());
    }
    if (net->load_param(files.paramPath.c_str()) != 0 || net->load_model(files.binPath.c_str()) != 0) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(net_cache_mutex);
    net_cache = NetCacheEntry{key, net};
    return net_cache.net;
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
    const std::string& message
) {
    if (onProgress) {
        onProgress(stage, currentTile, totalTiles, progress, message);
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

int align_delta(int value, int alignment) {
    if (alignment <= 1) {
        return 0;
    }
    return ((value + alignment - 1) / alignment) * alignment - value;
}

int waifu2x_prepadding(const SuperResNativeParams& params) {
    if (params.engineType != kEngineWaifu2x) {
        return 0;
    }
    if (params.modelDir.find("models-cunet") != std::string::npos) {
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
    if (params.modelDir.find("models-upconv_7_anime_style_art_rgb") != std::string::npos ||
        params.modelDir.find("models-upconv_7_photo") != std::string::npos) {
        return 7;
    }
    return 0;
}

int waifu2x_alignment(int scale) {
    if (scale == 1) {
        return 4;
    }
    if (scale == 2) {
        return 2;
    }
    return 1;
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
    const int prepadding = waifu2x_prepadding(params);
    const int alignment = waifu2x_alignment(params.scale);
    const int rightPadding = prepadding + align_delta(imageW, alignment);
    const int bottomPadding = prepadding + align_delta(imageH, alignment);
    return TileInputRegion{
        tileX - prepadding,
        tileY - prepadding,
        tileW + prepadding + rightPadding,
        tileH + prepadding + bottomPadding,
        prepadding * params.scale,
        prepadding * params.scale,
        tileX * params.scale,
        tileY * params.scale,
        tileW * params.scale,
        tileH * params.scale
    };
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
    std::vector<unsigned char>& coverage,
    int outputW,
    int outputH,
    int srcX,
    int srcY,
    int dstX,
    int dstY,
    int requestedCopyW,
    int requestedCopyH
) {
    if (tile.empty() || tile.c < 3) {
        return false;
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
    for (int y = 0; y < requestedCopyH; ++y) {
        const size_t rowOffset = static_cast<size_t>(dstY + y) * outputW + dstX;
        for (int x = 0; x < requestedCopyW; ++x) {
            coverage[rowOffset + x] = 1;
        }
    }
    return true;
}

bool all_output_pixels_covered(const std::vector<unsigned char>& coverage) {
    return std::all_of(coverage.begin(), coverage.end(), [](unsigned char value) {
        return value != 0;
    });
}

SuperResNativeCode run_ncnn(
    const SuperResNativeParams& params,
    const SuperResProgressCallback& onProgress,
    bool useVulkan
) {
    const ModelFiles files = select_model_files(params);
    if (!is_regular_file(files.paramPath) || !is_regular_file(files.binPath)) {
        return SuperResNativeCode::ModelMissing;
    }

    emit(onProgress, SuperResNativeStage::Analyzing, 0, 0, 0.04f, "Decoding input image");
    LoadedImage image = load_rgb_image(params.inputPath);
    if (image.rgb.empty()) {
        return SuperResNativeCode::Unsupported;
    }

    if (is_cancelled(params.taskId)) {
        return SuperResNativeCode::Cancelled;
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
    std::shared_ptr<ncnn::Net> net = load_cached_net(files, useVulkan, threadCount);
    if (!net) {
        return SuperResNativeCode::ModelMissing;
    }

    const int tileSize = std::max(32, params.tileSize);
    const int tileOverlap = std::min(32, std::max(8, tileSize / 4));
    const int tilesX = (image.width + tileSize - 1) / tileSize;
    const int tilesY = (image.height + tileSize - 1) / tileSize;
    const int totalTiles = tilesX * tilesY;
    const int outputW = image.width * params.scale;
    const int outputH = image.height * params.scale;

    std::vector<unsigned char> output(static_cast<size_t>(outputW) * outputH * 3);
    std::vector<unsigned char> coverage(static_cast<size_t>(outputW) * outputH);
    if (output.empty()) {
        return SuperResNativeCode::OutOfMemory;
    }

    int completed = 0;
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
            ncnn::Mat input = make_input_tile(image, region.inputX, region.inputY, region.inputW, region.inputH);
            ncnn::Mat result;
            const auto tileStart = std::chrono::steady_clock::now();

            ncnn::Extractor extractor = net->create_extractor();
            if (extractor.input(files.inputBlob, input) != 0 ||
                extractor.extract(files.outputBlob, result) != 0) {
                return useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Unsupported;
            }

            if (!copy_output_tile(
                    result,
                    output,
                    coverage,
                    outputW,
                    outputH,
                    region.cropX,
                    region.cropY,
                    region.dstX,
                    region.dstY,
                    region.copyW,
                    region.copyH
                )) {
                return SuperResNativeCode::TileOutputMismatch;
            }

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
    if (!all_output_pixels_covered(coverage)) {
        return SuperResNativeCode::TileOutputMismatch;
    }
    emit(onProgress, SuperResNativeStage::Saving, totalTiles, totalTiles, 0.96f, "Saving output");
    // The output buffer owns RGB bytes until stbi_write_png has synchronously copied them to disk.
    if (stbi_write_png(params.outputPath.c_str(), outputW, outputH, 3, output.data(), outputW * 3) == 0) {
        return SuperResNativeCode::OutputFailed;
    }

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
        SuperResNativeCode code = run_ncnn(params, onProgress, true);
        if (code != SuperResNativeCode::VulkanFailed || params.accelerationMode == kAccelerationVulkan) {
            return code;
        }
        emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "GPU failed; retrying on CPU");
    }

    return run_ncnn(params, onProgress, false);
}

void cancel_superres(const std::string& taskId) {
    std::lock_guard<std::mutex> lock(cancel_mutex);
    cancelled_tasks.insert(taskId);
}
