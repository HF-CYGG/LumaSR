#include "superres_runtime_control.h"
#include "superres_execution_profile.h"

namespace {

constexpr SuperResRuntimeInferenceControl kMinimumControl = sanitize_runtime_control(
    SuperResRuntimeInferenceControl{-4, -10, false}
);
constexpr SuperResRuntimeInferenceControl kMaximumControl = sanitize_runtime_control(
    SuperResRuntimeInferenceControl{99, 99, true}
);

static_assert(kMinimumControl.commandBatchSize == 1);
static_assert(kMinimumControl.thermalHeadroomPercent == 0);
static_assert(!kMinimumControl.shouldPause);
static_assert(kMaximumControl.commandBatchSize == 8);
static_assert(kMaximumControl.thermalHeadroomPercent == 25);
static_assert(kMaximumControl.shouldPause);

static_assert(resolve_runtime_headroom_percent(8, 0, true) == 8);
static_assert(resolve_runtime_headroom_percent(8, 20, true) == 20);
static_assert(resolve_runtime_headroom_percent(25, 10, true) == 25);
static_assert(resolve_runtime_headroom_percent(8, 0, false) == 0);
static_assert(resolve_runtime_headroom_percent(8, 20, false) == 20);
static_assert(resolve_runtime_headroom_percent(99, 99, true) == 25);

constexpr SuperResRuntimeInferenceControl kModerateControl = sanitize_runtime_control(
    SuperResRuntimeInferenceControl{4, 10, false}
);
constexpr SuperResCommandBatchPlan kModerateVulkanBatch = plan_command_batch(
    10,
    kModerateControl.commandBatchSize,
    0,
    kModerateControl.shouldPause
);
constexpr SuperResCommandBatchPlan kCriticalVulkanBatch = plan_command_batch(
    10,
    kMaximumControl.commandBatchSize,
    0,
    kMaximumControl.shouldPause
);
static_assert(kModerateVulkanBatch.shouldRecord && kModerateVulkanBatch.tileCount == 4);
static_assert(!kCriticalVulkanBatch.shouldRecord && kCriticalVulkanBatch.tileCount == 0);

}
