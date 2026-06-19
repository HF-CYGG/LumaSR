#include "superres_engine.h"

#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <sys/stat.h>
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
constexpr int kAccelerationAuto = 0;
constexpr int kAccelerationVulkan = 1;

std::mutex cancel_mutex;
std::mutex gpu_mutex;
std::unordered_set<std::string> cancelled_tasks;

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

struct GpuInstanceGuard {
    bool created = false;

    ~GpuInstanceGuard() {
        if (created) {
            ncnn::destroy_gpu_instance();
        }
    }
};

bool is_regular_file(const std::string& path) {
    struct stat info {};
    return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
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

ModelFiles select_model_files(const SuperResNativeParams& params) {
    const bool realcugan = params.engineType == kEngineRealCugan;
    const std::string baseName = realcugan
        ? realcugan_base_name(params.scale, params.noise)
        : waifu2x_base_name(params.scale, params.noise);
    return ModelFiles{
        join_path(params.modelDir, baseName + ".param"),
        join_path(params.modelDir, baseName + ".bin"),
        realcugan ? "in0" : "Input1",
        realcugan ? "out0" : "Eltwise4"
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

ncnn::Mat make_input_tile(
    const LoadedImage& image,
    int tileX,
    int tileY,
    int tileW,
    int tileH
) {
    ncnn::Mat tile(tileW, tileH, 3, sizeof(float));
    for (int c = 0; c < 3; ++c) {
        ncnn::Mat channel = tile.channel(c);
        for (int y = 0; y < tileH; ++y) {
            float* row = channel.row(y);
            const int sourceY = tileY + y;
            for (int x = 0; x < tileW; ++x) {
                const int sourceX = tileX + x;
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
    int outputW,
    int outputH,
    int dstX,
    int dstY
) {
    if (tile.empty() || tile.c < 3) {
        return false;
    }

    const int copyW = std::min(tile.w, outputW - dstX);
    const int copyH = std::min(tile.h, outputH - dstY);
    if (copyW <= 0 || copyH <= 0) {
        return false;
    }

    for (int c = 0; c < 3; ++c) {
        const ncnn::Mat channel = tile.channel(c);
        for (int y = 0; y < copyH; ++y) {
            const float* row = channel.row(y);
            for (int x = 0; x < copyW; ++x) {
                const size_t offset = (static_cast<size_t>(dstY + y) * outputW + dstX + x) * 3 + c;
                output[offset] = to_u8(clamp01(row[x]));
            }
        }
    }
    return true;
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

    GpuInstanceGuard gpuGuard;
    std::unique_lock<std::mutex> gpuLock(gpu_mutex, std::defer_lock);
    if (useVulkan) {
        gpuLock.lock();
        // ncnn owns a process-wide Vulkan instance, so creation/destruction is serialized.
        if (ncnn::create_gpu_instance() != 0 || ncnn::get_gpu_count() <= 0) {
            return SuperResNativeCode::VulkanFailed;
        }
        gpuGuard.created = true;
    }

    emit(onProgress, SuperResNativeStage::LoadingModel, 0, 0, 0.10f, "Loading ncnn model");
    ncnn::Net net;
    net.opt.num_threads = 2;
    net.opt.use_vulkan_compute = useVulkan;
    if (useVulkan) {
        net.set_vulkan_device(ncnn::get_default_gpu_index());
    }
    if (net.load_param(files.paramPath.c_str()) != 0 || net.load_model(files.binPath.c_str()) != 0) {
        return SuperResNativeCode::ModelMissing;
    }

    const int tileSize = std::max(32, params.tileSize);
    const int tilesX = (image.width + tileSize - 1) / tileSize;
    const int tilesY = (image.height + tileSize - 1) / tileSize;
    const int totalTiles = tilesX * tilesY;
    const int outputW = image.width * params.scale;
    const int outputH = image.height * params.scale;

    std::vector<unsigned char> output(static_cast<size_t>(outputW) * outputH * 3);
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
            ncnn::Mat input = make_input_tile(image, tileX, tileY, tileW, tileH);
            ncnn::Mat result;

            ncnn::Extractor extractor = net.create_extractor();
            if (extractor.input(files.inputBlob, input) != 0 ||
                extractor.extract(files.outputBlob, result) != 0) {
                return useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Unsupported;
            }

            if (!copy_output_tile(result, output, outputW, outputH, tileX * params.scale, tileY * params.scale)) {
                return SuperResNativeCode::Unsupported;
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

    if (params.engineType != kEngineWaifu2x && params.engineType != kEngineRealCugan) {
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
