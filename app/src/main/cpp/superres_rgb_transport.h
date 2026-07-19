#pragma once

#include <cstddef>
#include <limits>

constexpr size_t kSuperResRgbTransportHardLimitBytes =
    static_cast<size_t>(96u) * 1024u * 1024u;

enum class SuperResRgbTransportStatus {
    Eligible,
    InvalidDimensions,
    InvalidBudget,
    LayoutOverflow,
    BudgetExceeded,
    HardLimitExceeded
};

struct SuperResRgbTransportDecision {
    SuperResRgbTransportStatus status = SuperResRgbTransportStatus::InvalidDimensions;
    size_t byteSize = 0;
    size_t effectiveBudgetBytes = 0;

    constexpr bool eligible() const noexcept {
        return status == SuperResRgbTransportStatus::Eligible;
    }
};

constexpr bool checked_superres_rgb_transport_bytes(
    size_t width,
    size_t height,
    size_t& byteSize
) noexcept {
    byteSize = 0;
    if (width == 0 || height == 0 ||
        width > std::numeric_limits<size_t>::max() / 3u) {
        return false;
    }
    const size_t rowBytes = width * 3u;
    if (height > std::numeric_limits<size_t>::max() / rowBytes) {
        return false;
    }
    byteSize = rowBytes * height;
    return true;
}

constexpr SuperResRgbTransportDecision assess_superres_rgb_transport_bytes(
    size_t byteSize,
    size_t requestedBudgetBytes
) noexcept {
    if (requestedBudgetBytes == 0) {
        return SuperResRgbTransportDecision{
            SuperResRgbTransportStatus::InvalidBudget,
            byteSize,
            0
        };
    }
    const size_t effectiveBudget =
        requestedBudgetBytes < kSuperResRgbTransportHardLimitBytes
        ? requestedBudgetBytes
        : kSuperResRgbTransportHardLimitBytes;
    if (byteSize == 0) {
        return SuperResRgbTransportDecision{
            SuperResRgbTransportStatus::InvalidDimensions,
            0,
            effectiveBudget
        };
    }
    if (byteSize > kSuperResRgbTransportHardLimitBytes) {
        return SuperResRgbTransportDecision{
            SuperResRgbTransportStatus::HardLimitExceeded,
            byteSize,
            effectiveBudget
        };
    }
    if (byteSize > effectiveBudget) {
        return SuperResRgbTransportDecision{
            SuperResRgbTransportStatus::BudgetExceeded,
            byteSize,
            effectiveBudget
        };
    }
    return SuperResRgbTransportDecision{
        SuperResRgbTransportStatus::Eligible,
        byteSize,
        effectiveBudget
    };
}

constexpr SuperResRgbTransportDecision assess_superres_rgb_transport(
    int width,
    int height,
    size_t requestedBudgetBytes
) noexcept {
    if (width <= 0 || height <= 0) {
        return SuperResRgbTransportDecision{
            SuperResRgbTransportStatus::InvalidDimensions,
            0,
            requestedBudgetBytes < kSuperResRgbTransportHardLimitBytes
                ? requestedBudgetBytes
                : kSuperResRgbTransportHardLimitBytes
        };
    }
    size_t byteSize = 0;
    if (!checked_superres_rgb_transport_bytes(
            static_cast<size_t>(width),
            static_cast<size_t>(height),
            byteSize
        )) {
        return SuperResRgbTransportDecision{
            SuperResRgbTransportStatus::LayoutOverflow,
            0,
            requestedBudgetBytes < kSuperResRgbTransportHardLimitBytes
                ? requestedBudgetBytes
                : kSuperResRgbTransportHardLimitBytes
        };
    }
    return assess_superres_rgb_transport_bytes(byteSize, requestedBudgetBytes);
}
