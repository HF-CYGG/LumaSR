#include "superres_pipeline_internal.h"

#include "superres_jni_safety.h"
#include "superres_rgb_transport.h"
#include "superres_tile_geometry.h"

#include <limits>
#include <new>
#include <utility>

namespace {

constexpr int kAccelerationAuto = 0;
constexpr int kAccelerationVulkan = 1;
constexpr int kNativeOutputPng = 0;

class PipelineCompletionGuard {
public:
    PipelineCompletionGuard(
        const std::function<void(const std::string&)>& completeTask,
        const std::string& taskId
    ) noexcept : completeTask_(completeTask), taskId_(taskId) {}

    ~PipelineCompletionGuard() {
        try {
            if (completeTask_) {
                completeTask_(taskId_);
            }
        } catch (...) {
            // Completion is best-effort while unwinding and must never escape a destructor.
        }
    }

    PipelineCompletionGuard(const PipelineCompletionGuard&) = delete;
    PipelineCompletionGuard& operator=(const PipelineCompletionGuard&) = delete;

private:
    const std::function<void(const std::string&)>& completeTask_;
    const std::string& taskId_;
};

bool pipeline_shape_is_supported(const std::vector<SuperResNativeParams>& passes) {
    if (passes.size() < 2u) {
        return false;
    }
    for (const SuperResNativeParams& pass : passes) {
        if (pass.outputMode != kNativeOutputPng) {
            return false;
        }
    }
    return true;
}

bool pipeline_params_are_valid(
    const std::string& rootTaskId,
    const std::vector<SuperResNativeParams>& passes
) {
    if (rootTaskId.empty() || passes.size() < 2u || passes.front().inputPath.empty() ||
        passes.back().outputPath.empty()) {
        return false;
    }
    for (const SuperResNativeParams& pass : passes) {
        if (pass.modelDir.empty() || pass.scale <= 0 || pass.tileSize <= 0) {
            return false;
        }
    }
    return true;
}

bool checked_scaled_dimension(int value, int scale, int& result) {
    result = 0;
    if (value <= 0 || scale <= 0 ||
        value > std::numeric_limits<int>::max() / scale) {
        return false;
    }
    result = value * scale;
    return true;
}

bool pipeline_intermediates_fit_budget(
    const SuperResRgbImage& initialImage,
    const std::vector<SuperResNativeParams>& passes,
    size_t transportBudgetBytes
) {
    int width = initialImage.width();
    int height = initialImage.height();
    if (width <= 0 || height <= 0 || transportBudgetBytes == 0) {
        return false;
    }
    for (size_t stageIndex = 0; stageIndex + 1u < passes.size(); ++stageIndex) {
        int outputWidth = 0;
        int outputHeight = 0;
        if (!checked_scaled_dimension(width, passes[stageIndex].scale, outputWidth) ||
            !checked_scaled_dimension(height, passes[stageIndex].scale, outputHeight)) {
            return false;
        }
        const SuperResRgbTransportDecision decision = assess_superres_rgb_transport(
            outputWidth,
            outputHeight,
            transportBudgetBytes
        );
        if (!decision.eligible()) {
            return false;
        }
        width = outputWidth;
        height = outputHeight;
    }
    return true;
}

SuperResRgbPipelineStageAttempt run_stage_attempts(
    const SuperResNativeParams& params,
    const SuperResRgbImage& current,
    bool finalStage,
    size_t stageIndex,
    size_t stageCount,
    const SuperResRgbPipelineDependencies& dependencies
) {
    const bool vulkanRequested = params.accelerationMode == kAccelerationVulkan ||
        params.accelerationMode == kAccelerationAuto;
    const bool forceCpu = vulkanRequested &&
        should_force_cpu_before_vulkan_model_load(params.engineType);
    if (forceCpu && dependencies.onEvent) {
        dependencies.onEvent(SuperResRgbPipelineEvent::ForceCpu, stageIndex);
    }

    auto attempt = [&](bool useVulkan) {
        return dependencies.runStage(
            params,
            current,
            useVulkan,
            finalStage,
            stageIndex,
            stageCount
        );
    };

    SuperResRgbPipelineStageAttempt result = attempt(vulkanRequested && !forceCpu);
    if (vulkanRequested && !forceCpu &&
        params.accelerationMode != kAccelerationVulkan &&
        should_retry_cpu_after_gpu_code(
            params.accelerationMode,
            static_cast<int>(result.code)
        )) {
        // Drop any partially written attempt output before allocating the CPU target.
        result = SuperResRgbPipelineStageAttempt{};
        if (dependencies.onEvent) {
            dependencies.onEvent(SuperResRgbPipelineEvent::AutoCpuFallback, stageIndex);
        }
        result = attempt(false);
    }
    return result;
}

} // namespace

SuperResNativeCode process_superres_rgb_pipeline_internal(
    const std::string& rootTaskId,
    const std::vector<SuperResNativeParams>& passes,
    size_t transportBudgetBytes,
    const SuperResRgbPipelineDependencies& dependencies
) {
    if (!dependencies.loadImage || !dependencies.runStage || !dependencies.registerTask ||
        !dependencies.completeTask || !dependencies.inputExists || !dependencies.shouldStop) {
        return SuperResNativeCode::InvalidParams;
    }
    if (!pipeline_params_are_valid(rootTaskId, passes)) {
        return SuperResNativeCode::InvalidParams;
    }
    if (!pipeline_shape_is_supported(passes) || transportBudgetBytes == 0) {
        return SuperResNativeCode::PipelineFallbackRequired;
    }
    if (!dependencies.inputExists(passes.front().inputPath)) {
        return SuperResNativeCode::InputMissing;
    }
    if (!dependencies.registerTask(rootTaskId)) {
        return SuperResNativeCode::InvalidParams;
    }
    PipelineCompletionGuard completion(dependencies.completeTask, rootTaskId);
    if (dependencies.shouldStop()) {
        return SuperResNativeCode::Cancelled;
    }

    SuperResRgbImage current;
    try {
        current = dependencies.loadImage();
    } catch (const std::bad_alloc&) {
        return SuperResNativeCode::OutOfMemory;
    } catch (const SuperResJniPendingException&) {
        throw;
    } catch (...) {
        return SuperResNativeCode::Unsupported;
    }
    if (!current.valid()) {
        return SuperResNativeCode::Unsupported;
    }
    if (dependencies.shouldStop()) {
        return SuperResNativeCode::Cancelled;
    }
    if (!pipeline_intermediates_fit_budget(current, passes, transportBudgetBytes)) {
        return SuperResNativeCode::PipelineFallbackRequired;
    }

    try {
        for (size_t stageIndex = 0; stageIndex < passes.size(); ++stageIndex) {
            if (dependencies.shouldStop()) {
                return SuperResNativeCode::Cancelled;
            }
            SuperResNativeParams normalized = passes[stageIndex];
            normalized.taskId = rootTaskId;
            const bool finalStage = stageIndex + 1u == passes.size();
            SuperResRgbPipelineStageAttempt result = run_stage_attempts(
                normalized,
                current,
                finalStage,
                stageIndex,
                passes.size(),
                dependencies
            );
            if (result.code != SuperResNativeCode::Ok) {
                return result.code;
            }
            if (!finalStage) {
                if (!result.intermediateImage.valid()) {
                    return SuperResNativeCode::TileOutputMismatch;
                }
                current = std::move(result.intermediateImage);
            }
        }
        return SuperResNativeCode::Ok;
    } catch (const std::bad_alloc&) {
        return SuperResNativeCode::OutOfMemory;
    } catch (const SuperResJniPendingException&) {
        throw;
    } catch (...) {
        return SuperResNativeCode::Unsupported;
    }
}
