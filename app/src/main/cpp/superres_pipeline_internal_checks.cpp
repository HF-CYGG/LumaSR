#include "superres_pipeline_internal_checks.h"

#include "superres_pipeline_internal.h"

#include <algorithm>
#include <climits>
#include <cstddef>
#include <new>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr size_t kMiB = 1024u * 1024u;

SuperResNativeParams make_pass(
    const std::string& taskId,
    int scale,
    int accelerationMode = 2
) {
    SuperResNativeParams params{};
    params.taskId = taskId;
    params.inputPath = "input.png";
    params.outputPath = "output.png";
    params.engineType = 0;
    params.modelDir = "models";
    params.scale = scale;
    params.noise = 0;
    params.tileSize = 64;
    params.accelerationMode = accelerationMode;
    params.outputMode = 0;
    return params;
}

SuperResRgbImage make_image(int width, int height, unsigned char seed = 0) {
    size_t rowBytes = 0;
    size_t byteSize = 0;
    if (!checked_superres_rgb_layout(
            static_cast<size_t>(width),
            static_cast<size_t>(height),
            rowBytes,
            byteSize
        )) {
        return {};
    }
    SuperResRgbPixels pixels(
        new (std::nothrow) unsigned char[byteSize],
        SuperResRgbDeleter{}
    );
    if (pixels == nullptr) {
        return {};
    }
    for (size_t index = 0; index < byteSize; ++index) {
        pixels[index] = static_cast<unsigned char>(seed + index % 251u);
    }
    return SuperResRgbImage(width, height, rowBytes, byteSize, std::move(pixels));
}

int partialAttemptReleaseCount = 0;

void release_partial_attempt(unsigned char* pixels) noexcept {
    ++partialAttemptReleaseCount;
    delete[] pixels;
}

SuperResRgbImage make_counted_image() {
    SuperResRgbPixels pixels(
        new (std::nothrow) unsigned char[3],
        SuperResRgbDeleter{release_partial_attempt}
    );
    if (pixels == nullptr) {
        return {};
    }
    return SuperResRgbImage(1, 1, 3u, 3u, std::move(pixels));
}

struct DependencyCounters {
    int loads = 0;
    int registrations = 0;
    int completions = 0;
    int attempts = 0;
    int fallbacks = 0;
};

SuperResRgbPipelineDependencies base_dependencies(
    DependencyCounters& counters,
    int inputWidth = 1,
    int inputHeight = 1
) {
    SuperResRgbPipelineDependencies dependencies;
    dependencies.inputExists = [](const std::string&) { return true; };
    dependencies.loadImage = [&counters, inputWidth, inputHeight]() {
        ++counters.loads;
        return make_image(inputWidth, inputHeight, 1);
    };
    dependencies.registerTask = [&counters](const std::string&) {
        ++counters.registrations;
        return true;
    };
    dependencies.completeTask = [&counters](const std::string&) {
        ++counters.completions;
    };
    dependencies.shouldStop = [] { return false; };
    dependencies.onEvent = [&counters](SuperResRgbPipelineEvent event, size_t) {
        if (event == SuperResRgbPipelineEvent::AutoCpuFallback) {
            ++counters.fallbacks;
        }
    };
    return dependencies;
}

bool eligible_pipeline_retries_only_failed_stage_and_preserves_rgb8_check() {
    DependencyCounters counters;
    SuperResRgbPipelineDependencies dependencies = base_dependencies(counters);
    std::vector<bool> backends;
    std::vector<const unsigned char*> firstStagePointers;
    const std::vector<unsigned char> expected{
        9, 8, 7, 6, 5, 4,
        3, 2, 1, 0, 11, 12
    };
    dependencies.runStage = [
        &counters,
        &backends,
        &firstStagePointers,
        &expected
    ](
        const SuperResNativeParams& params,
        const SuperResRgbImage& input,
        bool useVulkan,
        bool finalStage,
        size_t stageIndex,
        size_t stageCount
    ) {
        ++counters.attempts;
        if (params.taskId != "root-task" || stageCount != 2u) {
            return SuperResRgbPipelineStageAttempt{SuperResNativeCode::InvalidParams, {}};
        }
        backends.push_back(useVulkan);
        if (stageIndex == 0u) {
            firstStagePointers.push_back(input.data());
            if (useVulkan) {
                return SuperResRgbPipelineStageAttempt{SuperResNativeCode::VulkanFailed, {}};
            }
            SuperResRgbImage output = make_image(2, 2);
            std::copy(expected.begin(), expected.end(), output.data());
            return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, std::move(output)};
        }
        if (!finalStage || input.width() != 2 || input.height() != 2 ||
            input.byte_size() != expected.size() ||
            !std::equal(expected.begin(), expected.end(), input.data())) {
            return SuperResRgbPipelineStageAttempt{
                SuperResNativeCode::TileOutputMismatch,
                {}
            };
        }
        return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, {}};
    };

    std::vector<SuperResNativeParams> passes{
        make_pass("foreign-stage-task", 2, 0),
        make_pass("another-foreign-task", 1, 2)
    };
    const SuperResNativeCode code = process_superres_rgb_pipeline_internal(
        "root-task",
        passes,
        16u * kMiB,
        dependencies
    );
    return code == SuperResNativeCode::Ok && counters.loads == 1 &&
        counters.registrations == 1 && counters.completions == 1 &&
        counters.attempts == 3 && counters.fallbacks == 1 &&
        backends == std::vector<bool>({true, false, false}) &&
        firstStagePointers.size() == 2u &&
        firstStagePointers[0] == firstStagePointers[1];
}

bool budget_rejection_precedes_every_attempt_check() {
    const size_t budgets[] = {16u * kMiB, 48u * kMiB, 96u * kMiB};
    for (size_t budget : budgets) {
        DependencyCounters counters;
        SuperResRgbPipelineDependencies dependencies =
            base_dependencies(counters, 1024, 1024);
        dependencies.runStage = [&counters](
            const SuperResNativeParams&,
            const SuperResRgbImage&,
            bool,
            bool,
            size_t,
            size_t
        ) {
            ++counters.attempts;
            return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, {}};
        };
        const std::vector<SuperResNativeParams> passes{
            make_pass("root-task", 1),
            make_pass("root-task", 1)
        };
        const SuperResNativeCode exact = process_superres_rgb_pipeline_internal(
            "root-task",
            passes,
            budget,
            dependencies
        );
        if (exact != SuperResNativeCode::TileOutputMismatch || counters.attempts != 1) {
            return false;
        }
    }

    DependencyCounters rejectedCounters;
    SuperResRgbPipelineDependencies rejected = base_dependencies(rejectedCounters, 1024, 1024);
    rejected.runStage = [&rejectedCounters](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool,
        bool,
        size_t,
        size_t
    ) {
        ++rejectedCounters.attempts;
        return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, {}};
    };
    const std::vector<SuperResNativeParams> overBudget{
        make_pass("root-task", 4),
        make_pass("root-task", 1)
    };
    const SuperResNativeCode rejectedCode = process_superres_rgb_pipeline_internal(
        "root-task",
        overBudget,
        16u * kMiB,
        rejected
    );
    return rejectedCode == SuperResNativeCode::PipelineFallbackRequired &&
        rejectedCounters.attempts == 0 && rejectedCounters.registrations == 1 &&
        rejectedCounters.completions == 1;
}

bool overflow_and_unsupported_output_preflight_fallback_check() {
    const std::vector<std::vector<SuperResNativeParams>> cases{
        {make_pass("root-task", INT_MAX), make_pass("root-task", 1)},
        {
            [] {
                SuperResNativeParams pass = make_pass("root-task", 1);
                pass.outputMode = 1;
                return pass;
            }(),
            make_pass("root-task", 1)
        }
    };
    for (const auto& passes : cases) {
        DependencyCounters counters;
        SuperResRgbPipelineDependencies dependencies = base_dependencies(counters, 2, 1);
        dependencies.runStage = [&counters](
            const SuperResNativeParams&,
            const SuperResRgbImage&,
            bool,
            bool,
            size_t,
            size_t
        ) {
            ++counters.attempts;
            return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, {}};
        };
        if (process_superres_rgb_pipeline_internal(
                "root-task",
                passes,
                96u * kMiB,
                dependencies
            ) != SuperResNativeCode::PipelineFallbackRequired || counters.attempts != 0) {
            return false;
        }
    }
    return true;
}

bool runtime_failure_never_becomes_preflight_fallback_check() {
    DependencyCounters counters;
    SuperResRgbPipelineDependencies dependencies = base_dependencies(counters);
    dependencies.runStage = [&counters](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool,
        bool,
        size_t,
        size_t
    ) {
        ++counters.attempts;
        return SuperResRgbPipelineStageAttempt{SuperResNativeCode::OutOfMemory, {}};
    };
    const std::vector<SuperResNativeParams> passes{
        make_pass("root-task", 1),
        make_pass("root-task", 1)
    };
    return process_superres_rgb_pipeline_internal(
        "root-task",
        passes,
        16u * kMiB,
        dependencies
    ) == SuperResNativeCode::OutOfMemory && counters.attempts == 1 &&
        counters.completions == 1;
}

bool partial_gpu_output_is_released_before_cpu_retry_check() {
    DependencyCounters counters;
    SuperResRgbPipelineDependencies dependencies = base_dependencies(counters);
    partialAttemptReleaseCount = 0;
    dependencies.runStage = [&counters](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool useVulkan,
        bool finalStage,
        size_t,
        size_t
    ) {
        ++counters.attempts;
        if (useVulkan) {
            return SuperResRgbPipelineStageAttempt{
                SuperResNativeCode::VulkanFailed,
                make_counted_image()
            };
        }
        if (partialAttemptReleaseCount != 1) {
            return SuperResRgbPipelineStageAttempt{SuperResNativeCode::InvalidParams, {}};
        }
        return finalStage
            ? SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, {}}
            : SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, make_image(1, 1)};
    };
    const std::vector<SuperResNativeParams> passes{
        make_pass("root-task", 1, 0),
        make_pass("root-task", 1, 2)
    };
    const SuperResNativeCode code = process_superres_rgb_pipeline_internal(
        "root-task",
        passes,
        16u * kMiB,
        dependencies
    );
    return code == SuperResNativeCode::Ok && partialAttemptReleaseCount == 1 &&
        counters.attempts == 3;
}

bool later_stage_failure_does_not_replay_completed_stage_check() {
    DependencyCounters counters;
    SuperResRgbPipelineDependencies dependencies = base_dependencies(counters);
    int firstStageRuns = 0;
    int secondStageRuns = 0;
    dependencies.runStage = [&firstStageRuns, &secondStageRuns](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool,
        bool,
        size_t stageIndex,
        size_t
    ) {
        if (stageIndex == 0u) {
            ++firstStageRuns;
            return SuperResRgbPipelineStageAttempt{
                SuperResNativeCode::Ok,
                make_image(1, 1)
            };
        }
        ++secondStageRuns;
        return SuperResRgbPipelineStageAttempt{SuperResNativeCode::OutOfMemory, {}};
    };
    const std::vector<SuperResNativeParams> passes{
        make_pass("root-task", 1, 2),
        make_pass("root-task", 1, 2)
    };
    return process_superres_rgb_pipeline_internal(
        "root-task",
        passes,
        16u * kMiB,
        dependencies
    ) == SuperResNativeCode::OutOfMemory && firstStageRuns == 1 && secondStageRuns == 1;
}

bool later_stage_auto_retry_does_not_replay_completed_stage_check() {
    DependencyCounters counters;
    SuperResRgbPipelineDependencies dependencies = base_dependencies(counters);
    int firstStageRuns = 0;
    int secondStageRuns = 0;
    std::vector<bool> secondStageBackends;
    dependencies.runStage = [
        &counters,
        &firstStageRuns,
        &secondStageRuns,
        &secondStageBackends
    ](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool useVulkan,
        bool finalStage,
        size_t stageIndex,
        size_t
    ) {
        ++counters.attempts;
        if (stageIndex == 0u) {
            ++firstStageRuns;
            return SuperResRgbPipelineStageAttempt{
                SuperResNativeCode::Ok,
                make_image(1, 1)
            };
        }
        ++secondStageRuns;
        secondStageBackends.push_back(useVulkan);
        if (!finalStage) {
            return SuperResRgbPipelineStageAttempt{
                SuperResNativeCode::TileOutputMismatch,
                {}
            };
        }
        return SuperResRgbPipelineStageAttempt{
            useVulkan ? SuperResNativeCode::VulkanFailed : SuperResNativeCode::Ok,
            {}
        };
    };
    const std::vector<SuperResNativeParams> passes{
        make_pass("root-task", 1, 2),
        make_pass("root-task", 1, 0)
    };
    return process_superres_rgb_pipeline_internal(
        "root-task",
        passes,
        16u * kMiB,
        dependencies
    ) == SuperResNativeCode::Ok && firstStageRuns == 1 && secondStageRuns == 2 &&
        secondStageBackends == std::vector<bool>({true, false}) &&
        counters.attempts == 3 && counters.fallbacks == 1 &&
        counters.registrations == 1 && counters.completions == 1;
}

bool explicit_vulkan_and_stop_do_not_retry_or_fallback_check() {
    DependencyCounters vulkanCounters;
    SuperResRgbPipelineDependencies vulkan = base_dependencies(vulkanCounters);
    vulkan.runStage = [&vulkanCounters](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool,
        bool,
        size_t,
        size_t
    ) {
        ++vulkanCounters.attempts;
        return SuperResRgbPipelineStageAttempt{SuperResNativeCode::VulkanFailed, {}};
    };
    const std::vector<SuperResNativeParams> vulkanPasses{
        make_pass("root-task", 1, 1),
        make_pass("root-task", 1, 1)
    };
    if (process_superres_rgb_pipeline_internal(
            "root-task",
            vulkanPasses,
            16u * kMiB,
            vulkan
        ) != SuperResNativeCode::VulkanFailed || vulkanCounters.attempts != 1 ||
        vulkanCounters.fallbacks != 0) {
        return false;
    }

    DependencyCounters stopCounters;
    SuperResRgbPipelineDependencies stopped = base_dependencies(stopCounters);
    stopped.shouldStop = [] { return true; };
    stopped.runStage = [&stopCounters](
        const SuperResNativeParams&,
        const SuperResRgbImage&,
        bool,
        bool,
        size_t,
        size_t
    ) {
        ++stopCounters.attempts;
        return SuperResRgbPipelineStageAttempt{SuperResNativeCode::Ok, {}};
    };
    return process_superres_rgb_pipeline_internal(
        "root-task",
        vulkanPasses,
        16u * kMiB,
        stopped
    ) == SuperResNativeCode::Cancelled && stopCounters.attempts == 0 &&
        stopCounters.completions == 1;
}

} // namespace

bool run_superres_pipeline_internal_runtime_checks() {
    return eligible_pipeline_retries_only_failed_stage_and_preserves_rgb8_check() &&
        budget_rejection_precedes_every_attempt_check() &&
        overflow_and_unsupported_output_preflight_fallback_check() &&
        runtime_failure_never_becomes_preflight_fallback_check() &&
        partial_gpu_output_is_released_before_cpu_retry_check() &&
        later_stage_failure_does_not_replay_completed_stage_check() &&
        later_stage_auto_retry_does_not_replay_completed_stage_check() &&
        explicit_vulkan_and_stop_do_not_retry_or_fallback_check();
}
