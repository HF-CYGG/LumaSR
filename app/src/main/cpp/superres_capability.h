#pragma once

#include <algorithm>
#include <cstddef>
#include <limits>
#include <string>

constexpr std::size_t kMaxCapabilityStringLength = 128;
constexpr std::size_t kMaxCapabilityInputBytes = 512;

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
    bool supportsVulkan = false;
    bool supportsFp16Storage = false;
    bool supportsFp16Arithmetic = false;
};

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
