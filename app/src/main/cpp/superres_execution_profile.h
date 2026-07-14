#pragma once

#include <cstddef>

enum class SuperResNativeBackend {
    LegacySafe = 0,
    Optimized = 1
};

enum class SuperResInferencePrecision {
    Fp32 = 0,
    Fp16 = 1
};

enum class SuperResNativeFallbackReason {
    None = -1,
    LegacySafeRequiresFp32 = 0,
    CpuRequiresFp32 = 1,
    Fp16Unsupported = 2
};

struct SuperResExecutionProfileRequest {
    SuperResNativeBackend backend = SuperResNativeBackend::LegacySafe;
    SuperResInferencePrecision precision = SuperResInferencePrecision::Fp32;
    int cpuThreads = 0;
    int openmpBlocktimeMs = 20;
    int commandBatchSize = 1;
    int modelCacheCapacity = 1;
};

struct SuperResExecutionProfile {
    SuperResNativeBackend effectiveBackend = SuperResNativeBackend::LegacySafe;
    SuperResInferencePrecision effectivePrecision = SuperResInferencePrecision::Fp32;
    int cpuThreads = 1;
    int openmpBlocktimeMs = 20;
    int commandBatchSize = 1;
    int modelCacheCapacity = 1;
    SuperResNativeFallbackReason fallbackReason = SuperResNativeFallbackReason::None;
};

constexpr int clamp_execution_value(int value, int minimum, int maximum) {
    return value < minimum ? minimum : value > maximum ? maximum : value;
}

constexpr int sanitize_cpu_threads(
    int requestedThreads,
    bool useVulkan,
    int bigCoreCount,
    int physicalCoreCount
) {
    if (useVulkan) {
        return 1;
    }
    const int automaticThreads = bigCoreCount > 0
        ? bigCoreCount
        : physicalCoreCount > 0 ? physicalCoreCount : 1;
    const int requestedOrAutomatic = requestedThreads == 0
        ? automaticThreads
        : requestedThreads < 1 ? 1 : requestedThreads;
    return physicalCoreCount > 0 && requestedOrAutomatic > physicalCoreCount
        ? physicalCoreCount
        : requestedOrAutomatic;
}

constexpr SuperResExecutionProfile sanitize_execution_profile(
    const SuperResExecutionProfileRequest& request,
    bool useVulkan,
    int bigCoreCount,
    int physicalCoreCount,
    bool gpuSupportsFp16Arithmetic
) {
    SuperResInferencePrecision effectivePrecision = SuperResInferencePrecision::Fp32;
    SuperResNativeFallbackReason fallbackReason = SuperResNativeFallbackReason::None;
    if (request.precision == SuperResInferencePrecision::Fp16) {
        if (request.backend == SuperResNativeBackend::LegacySafe) {
            fallbackReason = SuperResNativeFallbackReason::LegacySafeRequiresFp32;
        } else if (!useVulkan) {
            fallbackReason = SuperResNativeFallbackReason::CpuRequiresFp32;
        } else if (gpuSupportsFp16Arithmetic) {
            effectivePrecision = SuperResInferencePrecision::Fp16;
        } else {
            fallbackReason = SuperResNativeFallbackReason::Fp16Unsupported;
        }
    }
    return SuperResExecutionProfile{
        request.backend,
        effectivePrecision,
        sanitize_cpu_threads(request.cpuThreads, useVulkan, bigCoreCount, physicalCoreCount),
        clamp_execution_value(request.openmpBlocktimeMs, 0, 100),
        clamp_execution_value(request.commandBatchSize, 1, 8),
        clamp_execution_value(request.modelCacheCapacity, 1, 2),
        fallbackReason
    };
}

struct SuperResExecutionCacheIdentity {
    SuperResNativeBackend backend;
    SuperResInferencePrecision precision;
    int effectiveThreadCount;
};

constexpr bool operator==(
    const SuperResExecutionCacheIdentity& lhs,
    const SuperResExecutionCacheIdentity& rhs
) {
    return lhs.backend == rhs.backend &&
        lhs.precision == rhs.precision &&
        lhs.effectiveThreadCount == rhs.effectiveThreadCount;
}

constexpr bool operator!=(
    const SuperResExecutionCacheIdentity& lhs,
    const SuperResExecutionCacheIdentity& rhs
) {
    return !(lhs == rhs);
}

constexpr std::size_t cache_entries_to_trim(std::size_t currentSize, int requestedCapacity) {
    const std::size_t capacity = static_cast<std::size_t>(
        clamp_execution_value(requestedCapacity, 1, 2)
    );
    return currentSize > capacity ? currentSize - capacity : 0;
}
