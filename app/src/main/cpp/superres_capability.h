#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>

constexpr std::size_t kMaxCapabilityStringLength = 128;
constexpr std::size_t kMaxCapabilityInputBytes = 512;

enum class SuperResGpuInitState {
    Uninitialized,
    Available,
    Unavailable
};

constexpr bool should_attempt_gpu_initialization(SuperResGpuInitState state) {
    return state == SuperResGpuInitState::Uninitialized;
}

constexpr SuperResGpuInitState transition_gpu_init_state(
    SuperResGpuInitState currentState,
    int createResult,
    int gpuCount
) {
    if (!should_attempt_gpu_initialization(currentState)) {
        return currentState;
    }
    return createResult == 0 && gpuCount > 0
        ? SuperResGpuInitState::Available
        : SuperResGpuInitState::Unavailable;
}

constexpr bool should_destroy_empty_gpu_instance(int createResult, int gpuCount) {
    return createResult == 0 && gpuCount <= 0;
}

struct SuperResCpuTopology {
    int physicalCoreCount;
    int bigCoreCount;
    int littleCoreCount;
};

struct SuperResNativeCapabilitySnapshot {
    int physicalCoreCount = 0;
    int bigCoreCount = 0;
    int littleCoreCount = 0;
    std::string gpuVendor;
    std::string gpuDevice;
    std::string gpuDriverVersion;
    int gpuRoughScore = -1;
    long long gpuVendorId = -1;
    long long gpuDeviceId = -1;
    long long gpuApiVersion = -1;
    long long gpuDriverId = -1;
    long long gpuDriverVersionRaw = -1;
    std::string pipelineCacheUuid;
    int subgroupSize = -1;
    int subgroupMinSize = -1;
    int subgroupMaxSize = -1;
    int computeQueueCount = -1;
    long long maxComputeSharedMemoryBytes = -1;
    bool supportsSubgroupOps = false;
    bool supportsSubgroupSizeControl = false;
    bool supportsComputeFullSubgroups = false;
    bool corruptedOnlinePipelineCache = false;
    bool storageBufferNoL1 = false;
    bool bufferImageLoadZero = false;
    bool implicitFp16Arithmetic = false;
    bool supportsVulkan = false;
    bool supportsFp16Storage = false;
    bool supportsFp16Arithmetic = false;
};

constexpr bool is_qualcomm_vulkan_vendor(std::uint32_t vendorId) {
    return vendorId == 0x5143u;
}

constexpr long long vulkan_uint32_to_int64(std::uint32_t value) {
    return static_cast<long long>(value);
}

inline std::string pipeline_cache_uuid_hex(const unsigned char* uuid) {
    if (uuid == nullptr) return {};
    static constexpr char kHex[] = "0123456789abcdef";
    std::string encoded(32, '0');
    for (std::size_t index = 0; index < 16; ++index) {
        encoded[index * 2] = kHex[(uuid[index] >> 4) & 0x0f];
        encoded[index * 2 + 1] = kHex[uuid[index] & 0x0f];
    }
    return encoded;
}

constexpr SuperResCpuTopology normalize_cpu_topology(
    int physicalCoreCount,
    int bigCoreCount,
    int littleCoreCount
) {
    if (physicalCoreCount <= 0) {
        return SuperResCpuTopology{0, 0, 0};
    }
    const int normalizedBig = bigCoreCount > 0
        ? std::min(bigCoreCount, physicalCoreCount)
        : 0;
    const int remainingCores = physicalCoreCount - normalizedBig;
    const int normalizedLittle = littleCoreCount > 0
        ? std::min(littleCoreCount, remainingCores)
        : 0;
    return SuperResCpuTopology{
        physicalCoreCount,
        normalizedBig,
        normalizedLittle
    };
}

constexpr int normalize_gpu_rough_score(unsigned int roughScore) {
    return roughScore > static_cast<unsigned int>(std::numeric_limits<int>::max())
        ? std::numeric_limits<int>::max()
        : static_cast<int>(roughScore);
}

constexpr std::size_t sanitized_capability_length(
    const char* value,
    std::size_t maxLength = kMaxCapabilityStringLength
) {
    if (value == nullptr || maxLength == 0) {
        return 0;
    }

    const std::size_t boundedMaxLength = std::min(maxLength, kMaxCapabilityStringLength);
    std::size_t outputLength = 0;
    std::size_t lastNonSpaceLength = 0;
    for (std::size_t inputIndex = 0;
         inputIndex < kMaxCapabilityInputBytes && value[inputIndex] != '\0' && outputLength < boundedMaxLength;
         ++inputIndex) {
        const unsigned char byte = static_cast<unsigned char>(value[inputIndex]);
        if (byte < 0x20 || byte > 0x7e) {
            continue;
        }
        if (byte == ' ' && outputLength == 0) {
            continue;
        }
        ++outputLength;
        if (byte != ' ') {
            lastNonSpaceLength = outputLength;
        }
    }
    return lastNonSpaceLength;
}

inline std::string sanitize_capability_text(
    const char* value,
    std::size_t maxLength = kMaxCapabilityStringLength
) {
    std::string sanitized;
    if (value == nullptr || maxLength == 0) {
        return sanitized;
    }

    const std::size_t boundedMaxLength = std::min(maxLength, kMaxCapabilityStringLength);
    sanitized.reserve(boundedMaxLength);
    for (std::size_t inputIndex = 0;
         inputIndex < kMaxCapabilityInputBytes && value[inputIndex] != '\0' && sanitized.size() < boundedMaxLength;
         ++inputIndex) {
        const unsigned char byte = static_cast<unsigned char>(value[inputIndex]);
        if (byte < 0x20 || byte > 0x7e) {
            continue;
        }
        if (byte == ' ' && sanitized.empty()) {
            continue;
        }
        sanitized.push_back(static_cast<char>(byte));
    }
    while (!sanitized.empty() && sanitized.back() == ' ') {
        sanitized.pop_back();
    }
    return sanitized;
}

bool run_capability_normalization_runtime_checks();
