#include "superres_process_internal.h"

#include "superres_jni_safety.h"
#include "superres_rgb_image.h"
#include "superres_tile_geometry.h"

#include <chrono>
#include <new>
#include <utility>

namespace {
constexpr int kEngineWaifu2x = 0;
constexpr int kEngineRealCugan = 1;
constexpr int kEngineRealEsrgan = 2;
constexpr int kAccelerationAuto = 0;
constexpr int kAccelerationVulkan = 1;
constexpr int kNativeOutputPng = 0;
constexpr int kNativeOutputRawCroppedRgbTile = 1;

class CompletionGuard {
  public:
    CompletionGuard(const std::function<void(const std::string&)>& completeTask,
                    const std::string& taskId) noexcept
        : completeTask_(completeTask), taskId_(taskId) {}

    ~CompletionGuard() {
        try {
            if (completeTask_) {
                completeTask_(taskId_);
            }
        } catch (...) {
            // Completion is best-effort during stack unwinding; never cross a
            // destructor boundary.
        }
    }

    CompletionGuard(const CompletionGuard&) = delete;
    CompletionGuard& operator=(const CompletionGuard&) = delete;

  private:
    const std::function<void(const std::string&)>& completeTask_;
    const std::string& taskId_;
};

void emit(const SuperResProgressCallback& onProgress,
          SuperResNativeStage stage,
          float progress,
          const char* message) {
    if (onProgress) {
        onProgress(stage, 0, 0, progress, message, SuperResNativePerformance{});
    }
}

long long elapsed_ms(const std::chrono::steady_clock::time_point& start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() -
                                                                 start)
        .count();
}
} // namespace

SuperResNativeCode
process_superres_internal(const SuperResNativeParams& params,
                          const SuperResProgressCallback& onProgress,
                          const SuperResRuntimeControlCallback& runtimeControl,
                          bool requireInputPath,
                          const SuperResProcessInternalDependencies& dependencies) {
    if (!dependencies.loadImage || !dependencies.runImage || !dependencies.registerTask ||
        !dependencies.completeTask || (requireInputPath && !dependencies.inputExists)) {
        return SuperResNativeCode::InvalidParams;
    }
    if (params.taskId.empty() || params.outputPath.empty() || params.modelDir.empty() ||
        params.scale <= 0 || params.tileSize <= 0) {
        return SuperResNativeCode::InvalidParams;
    }
    if (requireInputPath && params.inputPath.empty()) {
        return SuperResNativeCode::InvalidParams;
    }
    if (params.outputMode != kNativeOutputPng &&
        params.outputMode != kNativeOutputRawCroppedRgbTile) {
        return SuperResNativeCode::InvalidParams;
    }
    if (params.outputMode == kNativeOutputRawCroppedRgbTile &&
        (params.outputCropWidth <= 0 || params.outputCropHeight <= 0)) {
        return SuperResNativeCode::InvalidParams;
    }
    if (params.engineType != kEngineWaifu2x && params.engineType != kEngineRealCugan &&
        params.engineType != kEngineRealEsrgan) {
        return SuperResNativeCode::Unsupported;
    }
    if (params.engineType == kEngineRealEsrgan && params.modelFileBase.empty()) {
        return SuperResNativeCode::Unsupported;
    }
    if (requireInputPath && !dependencies.inputExists(params.inputPath)) {
        return SuperResNativeCode::InputMissing;
    }
    if (!dependencies.registerTask(params.taskId)) {
        return SuperResNativeCode::InvalidParams;
    }
    CompletionGuard completion(dependencies.completeTask, params.taskId);

    emit(onProgress, SuperResNativeStage::Preparing, 0.0f, "Preparing native inference");
    emit(onProgress,
         SuperResNativeStage::Analyzing,
         0.04f,
         requireInputPath ? "Decoding input image" : "Reading bitmap region");

    const auto decodeStart = std::chrono::steady_clock::now();
    SuperResRgbImage image;
    try {
        image = dependencies.loadImage();
    } catch (const std::bad_alloc&) {
        return SuperResNativeCode::OutOfMemory;
    } catch (...) {
        return requireInputPath ? SuperResNativeCode::Unsupported
                                : SuperResNativeCode::InvalidParams;
    }
    const long long decodeMs = requireInputPath ? elapsed_ms(decodeStart) : 0;
    if (!image.valid()) {
        return requireInputPath ? SuperResNativeCode::Unsupported
                                : SuperResNativeCode::InvalidParams;
    }

    const bool vulkanRequested = params.accelerationMode == kAccelerationVulkan ||
                                 params.accelerationMode == kAccelerationAuto;
    const bool forceCpu =
        vulkanRequested && should_force_cpu_before_vulkan_model_load(params.engineType);
    if (forceCpu) {
        if (dependencies.onEvent) {
            dependencies.onEvent(SuperResProcessInternalEvent::ForceCpu, SuperResNativeCode::Ok);
        }
        emit(onProgress, SuperResNativeStage::LoadingModel, 0.10f, "Using CPU for this model");
    }

    try {
        return run_superres_rgb_attempts(
            image,
            vulkanRequested && !forceCpu,
            [&params, &onProgress, &runtimeControl, &dependencies, decodeMs](
                const SuperResRgbImage& input, bool useVulkan) {
                return dependencies.runImage(
                    params, onProgress, runtimeControl, useVulkan, input, decodeMs);
            },
            [&params](SuperResNativeCode code) {
                return params.accelerationMode != kAccelerationVulkan &&
                       should_retry_cpu_after_gpu_code(params.accelerationMode,
                                                       static_cast<int>(code));
            },
            [&dependencies, &onProgress](SuperResNativeCode code) {
                if (dependencies.onEvent) {
                    dependencies.onEvent(SuperResProcessInternalEvent::AutoCpuFallback, code);
                }
                emit(onProgress,
                     SuperResNativeStage::LoadingModel,
                     0.10f,
                     "GPU failed; retrying on CPU");
            });
    } catch (const std::bad_alloc&) {
        return SuperResNativeCode::OutOfMemory;
    } catch (const SuperResJniPendingException&) {
        throw;
    } catch (...) {
        return SuperResNativeCode::Unsupported;
    }
}
