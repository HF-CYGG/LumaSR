#pragma once

#include "superres_engine.h"

#include <cstddef>
#include <functional>
#include <string>
#include <vector>

enum class SuperResRgbPipelineEvent {
    ForceCpu,
    AutoCpuFallback
};

struct SuperResRgbPipelineStageAttempt {
    SuperResNativeCode code = SuperResNativeCode::InvalidParams;
    SuperResRgbImage intermediateImage;
};

struct SuperResRgbPipelineDependencies {
    std::function<SuperResRgbImage()> loadImage;
    std::function<SuperResRgbPipelineStageAttempt(
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool,
        bool,
        size_t,
        size_t
    )> runStage;
    std::function<bool(const std::string&)> registerTask;
    std::function<void(const std::string&)> completeTask;
    std::function<bool(const std::string&)> inputExists;
    std::function<bool()> shouldStop;
    std::function<void(SuperResRgbPipelineEvent, size_t)> onEvent;
};

SuperResNativeCode process_superres_rgb_pipeline_internal(
    const std::string& rootTaskId,
    const std::vector<SuperResNativeParams>& passes,
    size_t transportBudgetBytes,
    const SuperResRgbPipelineDependencies& dependencies
);
