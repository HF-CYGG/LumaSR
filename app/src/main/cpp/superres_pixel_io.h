#pragma once

#include "superres_tile_geometry.h"

#include <algorithm>
#include <cstdint>

inline float superres_clamp01(float value) {
    if (value < 0.0f) {
        return 0.0f;
    }
    if (value > 1.0f) {
        return 1.0f;
    }
    return value;
}

inline unsigned char superres_to_u8(float normalized) {
    return static_cast<unsigned char>(
        std::min(255.0f, std::max(0.0f, normalized * 255.0f + 0.5f))
    );
}

inline void copy_rgb8_pixel_to_planar(
    const unsigned char* pixel,
    int destinationX,
    float* red,
    float* green,
    float* blue
) {
    red[destinationX] = static_cast<float>(pixel[0]) / 255.0f;
    green[destinationX] = static_cast<float>(pixel[1]) / 255.0f;
    blue[destinationX] = static_cast<float>(pixel[2]) / 255.0f;
}

inline bool copy_clamped_rgb8_row_to_planar(
    const unsigned char* sourceRgb,
    int sourceWidth,
    int sourceX,
    int pixelCount,
    float* red,
    float* green,
    float* blue
) {
    if (sourceRgb == nullptr || sourceWidth <= 0 || pixelCount <= 0 ||
        red == nullptr || green == nullptr || blue == nullptr) {
        return false;
    }

    // Split the row into replicated-left, contiguous-source and replicated-right spans once.
    const int64_t interiorBegin64 = std::max<int64_t>(0, -static_cast<int64_t>(sourceX));
    const int64_t interiorEnd64 =
        static_cast<int64_t>(sourceWidth) - static_cast<int64_t>(sourceX);
    const int interiorBegin = static_cast<int>(
        std::min<int64_t>(pixelCount, interiorBegin64)
    );
    const int interiorEnd = static_cast<int>(
        std::max<int64_t>(
            interiorBegin,
            std::min<int64_t>(pixelCount, interiorEnd64)
        )
    );

    const unsigned char* const firstPixel = sourceRgb;
    for (int x = 0; x < interiorBegin; ++x) {
        copy_rgb8_pixel_to_planar(firstPixel, x, red, green, blue);
    }

    if (interiorBegin < interiorEnd) {
        const int sourceInteriorX = sourceX + interiorBegin;
        const unsigned char* sourcePixel = sourceRgb + static_cast<size_t>(sourceInteriorX) * 3;
        for (int x = interiorBegin; x < interiorEnd; ++x) {
            copy_rgb8_pixel_to_planar(sourcePixel, x, red, green, blue);
            sourcePixel += 3;
        }
    }

    const unsigned char* const lastPixel = sourceRgb + static_cast<size_t>(sourceWidth - 1) * 3;
    for (int x = interiorEnd; x < pixelCount; ++x) {
        copy_rgb8_pixel_to_planar(lastPixel, x, red, green, blue);
    }
    return true;
}

inline bool copy_planar_float_row_to_rgb8(
    const float* red,
    const float* green,
    const float* blue,
    unsigned char* destinationRgb,
    int pixelCount,
    const unsigned char* residualRgb,
    int residualWidth,
    int fullDestinationX
) {
    if (red == nullptr || green == nullptr || blue == nullptr ||
        destinationRgb == nullptr || pixelCount <= 0 ||
        (residualRgb != nullptr && residualWidth <= 0)) {
        return false;
    }

    for (int x = 0; x < pixelCount; ++x) {
        float redValue = red[x];
        float greenValue = green[x];
        float blueValue = blue[x];
        if (residualRgb != nullptr) {
            const int residualX = realcugan_4x_residual_source_coord(
                fullDestinationX + x,
                residualWidth
            );
            const unsigned char* const residualPixel = residualRgb + residualX * 3;
            redValue += static_cast<float>(residualPixel[0]) / 255.0f;
            greenValue += static_cast<float>(residualPixel[1]) / 255.0f;
            blueValue += static_cast<float>(residualPixel[2]) / 255.0f;
        }
        destinationRgb[0] = superres_to_u8(superres_clamp01(redValue));
        destinationRgb[1] = superres_to_u8(superres_clamp01(greenValue));
        destinationRgb[2] = superres_to_u8(superres_clamp01(blueValue));
        destinationRgb += 3;
    }
    return true;
}

bool run_pixel_io_runtime_checks();
