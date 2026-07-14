#include "superres_pixel_io.h"

#include <array>
#include <cmath>
#include <cstring>
#include <limits>

namespace {

constexpr int kMaxPixels = 32;

float reference_clamp01(float value) {
    if (value < 0.0f) {
        return 0.0f;
    }
    if (value > 1.0f) {
        return 1.0f;
    }
    return value;
}

unsigned char reference_to_u8(float normalized) {
    return static_cast<unsigned char>(
        std::min(255.0f, std::max(0.0f, normalized * 255.0f + 0.5f))
    );
}

void reference_clamped_rgb8_row_to_planar(
    const unsigned char* sourceRgb,
    int sourceWidth,
    int sourceX,
    int pixelCount,
    float* red,
    float* green,
    float* blue
) {
    float* channels[3]{red, green, blue};
    for (int channel = 0; channel < 3; ++channel) {
        for (int x = 0; x < pixelCount; ++x) {
            const int sourceCoordinate =
                sourceX + x < 0 ? 0 : sourceX + x >= sourceWidth ? sourceWidth - 1 : sourceX + x;
            channels[channel][x] =
                static_cast<float>(sourceRgb[sourceCoordinate * 3 + channel]) / 255.0f;
        }
    }
}

void reference_planar_float_row_to_rgb8(
    const float* red,
    const float* green,
    const float* blue,
    unsigned char* destinationRgb,
    int pixelCount,
    const unsigned char* residualRgb,
    int residualWidth,
    int fullDestinationX
) {
    const float* channels[3]{red, green, blue};
    for (int channel = 0; channel < 3; ++channel) {
        for (int x = 0; x < pixelCount; ++x) {
            float value = channels[channel][x];
            if (residualRgb != nullptr) {
                const int residualX = realcugan_4x_residual_source_coord(
                    fullDestinationX + x,
                    residualWidth
                );
                value += static_cast<float>(residualRgb[residualX * 3 + channel]) / 255.0f;
            }
            destinationRgb[x * 3 + channel] = reference_to_u8(reference_clamp01(value));
        }
    }
}

bool input_rows_match_reference(
    const unsigned char* sourceRgb,
    int sourceWidth,
    int sourceX,
    int pixelCount
) {
    std::array<float, kMaxPixels> expectedRed{};
    std::array<float, kMaxPixels> expectedGreen{};
    std::array<float, kMaxPixels> expectedBlue{};
    std::array<float, kMaxPixels> actualRed{};
    std::array<float, kMaxPixels> actualGreen{};
    std::array<float, kMaxPixels> actualBlue{};
    reference_clamped_rgb8_row_to_planar(
        sourceRgb,
        sourceWidth,
        sourceX,
        pixelCount,
        expectedRed.data(),
        expectedGreen.data(),
        expectedBlue.data()
    );
    if (!copy_clamped_rgb8_row_to_planar(
            sourceRgb,
            sourceWidth,
            sourceX,
            pixelCount,
            actualRed.data(),
            actualGreen.data(),
            actualBlue.data()
        )) {
        return false;
    }
    const size_t bytes = static_cast<size_t>(pixelCount) * sizeof(float);
    return std::memcmp(expectedRed.data(), actualRed.data(), bytes) == 0 &&
        std::memcmp(expectedGreen.data(), actualGreen.data(), bytes) == 0 &&
        std::memcmp(expectedBlue.data(), actualBlue.data(), bytes) == 0;
}

bool output_rows_match_reference(
    const float* red,
    const float* green,
    const float* blue,
    int sourceOffset,
    int pixelCount,
    const unsigned char* residualRgb,
    int residualWidth,
    int fullDestinationX
) {
    std::array<unsigned char, kMaxPixels * 3> expected{};
    std::array<unsigned char, kMaxPixels * 3> actual{};
    reference_planar_float_row_to_rgb8(
        red + sourceOffset,
        green + sourceOffset,
        blue + sourceOffset,
        expected.data(),
        pixelCount,
        residualRgb,
        residualWidth,
        fullDestinationX
    );
    if (!copy_planar_float_row_to_rgb8(
            red + sourceOffset,
            green + sourceOffset,
            blue + sourceOffset,
            actual.data(),
            pixelCount,
            residualRgb,
            residualWidth,
            fullDestinationX
        )) {
        return false;
    }
    return std::memcmp(expected.data(), actual.data(), static_cast<size_t>(pixelCount) * 3) == 0;
}

unsigned int next_random(unsigned int& state) {
    state = state * 1664525u + 1013904223u;
    return state;
}

}  // namespace

bool run_pixel_io_runtime_checks() {
    const std::array<unsigned char, 12> boundarySource{
        0, 17, 255,
        63, 127, 191,
        1, 2, 3,
        254, 128, 4
    };
    if (!input_rows_match_reference(boundarySource.data(), 4, -5, 12) ||
        !input_rows_match_reference(boundarySource.data(), 4, -2, 9) ||
        !input_rows_match_reference(boundarySource.data(), 4, 2, 8) ||
        !input_rows_match_reference(boundarySource.data(), 4, 7, 6)) {
        return false;
    }

    std::array<float, kMaxPixels> red{};
    std::array<float, kMaxPixels> green{};
    std::array<float, kMaxPixels> blue{};
    red[2] = -std::numeric_limits<float>::infinity();
    green[2] = std::numeric_limits<float>::infinity();
    blue[2] = std::numeric_limits<float>::quiet_NaN();
    red[3] = -0.001f;
    green[3] = 0.5f;
    blue[3] = 1.001f;
    red[4] = 0.0f;
    green[4] = 1.0f;
    blue[4] = 0.499f;
    std::array<unsigned char, 9> specialActual{};
    const std::array<unsigned char, 9> specialExpected{
        0, 255, 0,
        0, 128, 255,
        0, 255, 127
    };
    if (!copy_planar_float_row_to_rgb8(
            red.data() + 2,
            green.data() + 2,
            blue.data() + 2,
            specialActual.data(),
            3,
            nullptr,
            0,
            0
        ) || specialActual != specialExpected) {
        return false;
    }
    const std::array<unsigned char, 15> residual{
        1, 2, 3,
        31, 63, 95,
        127, 159, 191,
        223, 239, 251,
        255, 128, 0
    };
    if (!output_rows_match_reference(red.data(), green.data(), blue.data(), 2, 3, nullptr, 0, 0) ||
        !output_rows_match_reference(red.data(), green.data(), blue.data(), 2, 3, residual.data(), 5, 3)) {
        return false;
    }

    constexpr CroppedCopyWindow crop = resolve_cropped_copy_window(
        1,
        0,
        2,
        0,
        8,
        1,
        5,
        0,
        4,
        1
    );
    static_assert(crop.valid && crop.srcX == 4 && crop.dstX == 0 && crop.copyW == 4);
    if (!output_rows_match_reference(
            red.data(),
            green.data(),
            blue.data(),
            crop.srcX,
            crop.copyW,
            residual.data(),
            5,
            5
        )) {
        return false;
    }

    unsigned int state = 0x4c554d41u;
    std::array<unsigned char, kMaxPixels * 3> randomRgb{};
    std::array<unsigned char, kMaxPixels * 3> randomResidual{};
    for (int iteration = 0; iteration < 128; ++iteration) {
        const int sourceWidth = 1 + static_cast<int>(next_random(state) % 11u);
        const int pixelCount = 1 + static_cast<int>(next_random(state) % 24u);
        const int sourceX = static_cast<int>(next_random(state) % 29u) - 14;
        for (int index = 0; index < sourceWidth * 3; ++index) {
            randomRgb[static_cast<size_t>(index)] = static_cast<unsigned char>(next_random(state) >> 24);
        }
        if (!input_rows_match_reference(randomRgb.data(), sourceWidth, sourceX, pixelCount)) {
            return false;
        }

        const int planarWidth = 1 + static_cast<int>(next_random(state) % 24u);
        for (int index = 0; index < planarWidth; ++index) {
            const int signedValue = static_cast<int>(next_random(state) % 4097u) - 1024;
            red[static_cast<size_t>(index)] = static_cast<float>(signedValue) / 2048.0f;
            green[static_cast<size_t>(index)] = static_cast<float>(signedValue - 311) / 1536.0f;
            blue[static_cast<size_t>(index)] = static_cast<float>(signedValue + 177) / 3072.0f;
        }
        const int sourceOffset = static_cast<int>(next_random(state) % static_cast<unsigned int>(planarWidth));
        const int outputCount = 1 + static_cast<int>(
            next_random(state) % static_cast<unsigned int>(planarWidth - sourceOffset)
        );
        const int residualWidth = 1 + static_cast<int>(next_random(state) % 11u);
        for (int index = 0; index < residualWidth * 3; ++index) {
            randomResidual[static_cast<size_t>(index)] =
                static_cast<unsigned char>(next_random(state) >> 24);
        }
        const bool useResidual = (next_random(state) & 1u) != 0;
        if (!output_rows_match_reference(
                red.data(),
                green.data(),
                blue.data(),
                sourceOffset,
                outputCount,
                useResidual ? randomResidual.data() : nullptr,
                useResidual ? residualWidth : 0,
                static_cast<int>(next_random(state) % 48u)
            )) {
            return false;
        }
    }

    return !copy_clamped_rgb8_row_to_planar(
            nullptr,
            1,
            0,
            1,
            red.data(),
            green.data(),
            blue.data()
        ) &&
        !copy_planar_float_row_to_rgb8(
            red.data(),
            green.data(),
            blue.data(),
            randomRgb.data(),
            1,
            residual.data(),
            0,
            0
        );
}
