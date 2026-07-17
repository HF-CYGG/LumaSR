#pragma once

#include <cstddef>
#include <string>

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
    int postprocessThreads = 1;
    int commandBatchSize = 1;
    int modelCacheCapacity = 1;
};

struct SuperResExecutionProfile {
    SuperResNativeBackend effectiveBackend = SuperResNativeBackend::LegacySafe;
    SuperResInferencePrecision effectivePrecision = SuperResInferencePrecision::Fp32;
    int cpuThreads = 1;
    int openmpBlocktimeMs = 20;
    int postprocessThreads = 1;
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
        clamp_execution_value(request.postprocessThreads, 1, 64),
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

constexpr int sanitize_gpu_headroom_percent(int value) {
    return clamp_execution_value(value, 0, 25);
}

constexpr long long calculate_gpu_headroom_sleep_ms(int requestedPercent, long long elapsedMs) {
    const int headroom = sanitize_gpu_headroom_percent(requestedPercent);
    if (headroom == 0 || elapsedMs <= 0) {
        return 0;
    }
    const long long divisor = 100 - headroom;
    const long long cappedSleepNumerator = 20LL * divisor;
    const long long capThreshold = (cappedSleepNumerator + headroom - 1) / headroom;
    if (elapsedMs >= capThreshold) {
        return 20;
    }
    const long long calculated = elapsedMs * headroom / divisor;
    return calculated < 1 ? 1 : calculated;
}

struct SuperResCommandBatchPlan {
    int firstTile = 0;
    int tileCount = 0;
    bool shouldRecord = false;
};

constexpr SuperResCommandBatchPlan plan_command_batch(
    int totalTiles,
    int requestedBatchSize,
    int firstTile,
    bool cancellationRequested
) {
    const int safeTotal = totalTiles < 0 ? 0 : totalTiles;
    const int safeFirst = firstTile < 0 ? 0 : firstTile;
    if (cancellationRequested || safeFirst >= safeTotal) {
        return SuperResCommandBatchPlan{safeFirst, 0, false};
    }
    const int batchSize = clamp_execution_value(requestedBatchSize, 1, 8);
    const int remaining = safeTotal - safeFirst;
    return SuperResCommandBatchPlan{
        safeFirst,
        remaining < batchSize ? remaining : batchSize,
        true
    };
}

constexpr bool should_use_optimized_vulkan_path(
    SuperResNativeBackend backend,
    bool useVulkan
) {
    return backend == SuperResNativeBackend::Optimized && useVulkan;
}

inline std::string serialize_execution_cache_identity(
    bool useVulkan,
    const SuperResExecutionProfile& profile
) {
    return std::string("device=") + (useVulkan ? "vulkan" : "cpu") +
        "|backend=" + std::to_string(static_cast<int>(profile.effectiveBackend)) +
        "|precision=" + std::to_string(static_cast<int>(profile.effectivePrecision)) +
        "|threads=" + std::to_string(profile.cpuThreads) +
        "|blocktime_ms=" + std::to_string(profile.openmpBlocktimeMs);
}

bool run_execution_profile_runtime_checks();
