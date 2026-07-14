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

}
