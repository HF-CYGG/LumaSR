#include "superres_execution_profile.h"

namespace {

constexpr SuperResExecutionProfileRequest kOptimizedRequest{
    SuperResNativeBackend::Optimized,
    SuperResInferencePrecision::Fp16,
    8,
    130,
    12,
    7
};
constexpr SuperResExecutionProfile kSanitizedCpuProfile = sanitize_execution_profile(
    kOptimizedRequest,
    false,
    6,
    4,
    false
);
static_assert(kSanitizedCpuProfile.cpuThreads == 4);
static_assert(kSanitizedCpuProfile.openmpBlocktimeMs == 100);
static_assert(kSanitizedCpuProfile.commandBatchSize == 8);
static_assert(kSanitizedCpuProfile.modelCacheCapacity == 2);
static_assert(kSanitizedCpuProfile.effectivePrecision == SuperResInferencePrecision::Fp32);
static_assert(kSanitizedCpuProfile.fallbackReason == SuperResNativeFallbackReason::CpuRequiresFp32);

constexpr SuperResExecutionProfileRequest kAutoRequest{};
static_assert(sanitize_execution_profile(kAutoRequest, false, 6, 8, false).cpuThreads == 6);
static_assert(sanitize_execution_profile(kAutoRequest, false, 0, 8, false).cpuThreads == 8);
static_assert(sanitize_execution_profile(kAutoRequest, false, 0, 0, false).cpuThreads == 1);
static_assert(sanitize_execution_profile(kOptimizedRequest, true, 6, 8, true).cpuThreads == 1);
static_assert(
    sanitize_execution_profile(kOptimizedRequest, true, 6, 8, true).effectivePrecision ==
        SuperResInferencePrecision::Fp16
);

constexpr SuperResExecutionCacheIdentity kLegacyFp32Key{
    SuperResNativeBackend::LegacySafe,
    SuperResInferencePrecision::Fp32,
    4
};
constexpr SuperResExecutionCacheIdentity kOptimizedFp32Key{
    SuperResNativeBackend::Optimized,
    SuperResInferencePrecision::Fp32,
    4
};
constexpr SuperResExecutionCacheIdentity kOptimizedFp16Key{
    SuperResNativeBackend::Optimized,
    SuperResInferencePrecision::Fp16,
    4
};
static_assert(kLegacyFp32Key != kOptimizedFp32Key);
static_assert(kOptimizedFp32Key != kOptimizedFp16Key);
static_assert(kOptimizedFp16Key != SuperResExecutionCacheIdentity{
    SuperResNativeBackend::Optimized,
    SuperResInferencePrecision::Fp16,
    2
});
static_assert(cache_entries_to_trim(3, 1) == 2);
static_assert(cache_entries_to_trim(1, 2) == 0);

static_assert(sanitize_gpu_headroom_percent(-1) == 0);
static_assert(sanitize_gpu_headroom_percent(0) == 0);
static_assert(sanitize_gpu_headroom_percent(17) == 17);
static_assert(sanitize_gpu_headroom_percent(26) == 25);
static_assert(calculate_gpu_headroom_sleep_ms(0, 100) == 0);
static_assert(calculate_gpu_headroom_sleep_ms(8, 100) == 8);

constexpr SuperResCommandBatchPlan kFirstBatch = plan_command_batch(10, 4, 0, false);
constexpr SuperResCommandBatchPlan kSecondBatch = plan_command_batch(10, 4, 4, false);
constexpr SuperResCommandBatchPlan kFinalBatch = plan_command_batch(10, 4, 8, false);
constexpr SuperResCommandBatchPlan kMinimumBatch = plan_command_batch(3, 0, 0, false);
constexpr SuperResCommandBatchPlan kMaximumBatch = plan_command_batch(9, 99, 0, false);
constexpr SuperResCommandBatchPlan kCancelledBoundary = plan_command_batch(10, 4, 4, true);
constexpr SuperResCommandBatchPlan kFinishedBoundary = plan_command_batch(10, 4, 10, false);
static_assert(kFirstBatch.shouldRecord && kFirstBatch.firstTile == 0 && kFirstBatch.tileCount == 4);
static_assert(kSecondBatch.shouldRecord && kSecondBatch.firstTile == 4 && kSecondBatch.tileCount == 4);
static_assert(kFinalBatch.shouldRecord && kFinalBatch.firstTile == 8 && kFinalBatch.tileCount == 2);
static_assert(kMinimumBatch.shouldRecord && kMinimumBatch.tileCount == 1);
static_assert(kMaximumBatch.shouldRecord && kMaximumBatch.tileCount == 8);
static_assert(!kCancelledBoundary.shouldRecord && kCancelledBoundary.tileCount == 0);
static_assert(!kFinishedBoundary.shouldRecord && kFinishedBoundary.tileCount == 0);
static_assert(should_use_optimized_vulkan_path(SuperResNativeBackend::Optimized, true));
static_assert(!should_use_optimized_vulkan_path(SuperResNativeBackend::LegacySafe, true));
static_assert(!should_use_optimized_vulkan_path(SuperResNativeBackend::Optimized, false));

}

bool run_execution_profile_runtime_checks() {
    SuperResExecutionProfile profile;
    profile.effectiveBackend = SuperResNativeBackend::Optimized;
    profile.effectivePrecision = SuperResInferencePrecision::Fp16;
    profile.cpuThreads = 4;
    profile.openmpBlocktimeMs = 20;
    return serialize_execution_cache_identity(false, profile) ==
            "device=cpu|backend=1|precision=1|threads=4|blocktime_ms=20" &&
        serialize_execution_cache_identity(true, profile) ==
            "device=vulkan|backend=1|precision=1|threads=4|blocktime_ms=20";
}
