#include "superres_rgb_image.h"

#include <cstdint>
#include <cstring>
#include <limits>
#include <new>
#include <utility>

namespace {
bool checked_multiply(size_t left, size_t right, size_t& result) noexcept {
    if (left != 0 && right > std::numeric_limits<size_t>::max() / left) {
        result = 0;
        return false;
    }
    result = left * right;
    return true;
}
}

void delete_superres_rgb_array(unsigned char* pixels) noexcept {
    delete[] pixels;
}

SuperResRgbImage::SuperResRgbImage(
    int width,
    int height,
    size_t rowStrideBytes,
    size_t byteSize,
    SuperResRgbPixels pixels
) noexcept
    : width_(width),
      height_(height),
      rowStrideBytes_(rowStrideBytes),
      byteSize_(byteSize),
      pixels_(std::move(pixels)) {}

bool SuperResRgbImage::valid() const noexcept {
    if (width_ <= 0 || height_ <= 0 || pixels_ == nullptr) {
        return false;
    }
    size_t minimumRowBytes = 0;
    size_t tightByteSize = 0;
    if (!checked_superres_rgb_layout(
            static_cast<size_t>(width_),
            static_cast<size_t>(height_),
            minimumRowBytes,
            tightByteSize
        ) || rowStrideBytes_ < minimumRowBytes) {
        return false;
    }
    if (byteSize_ < tightByteSize) {
        return false;
    }
    size_t requiredByteSize = 0;
    return checked_multiply(
            rowStrideBytes_,
            static_cast<size_t>(height_),
            requiredByteSize
        ) && byteSize_ >= requiredByteSize;
}

bool checked_superres_rgb_layout(
    size_t width,
    size_t height,
    size_t& rowStrideBytes,
    size_t& byteSize
) noexcept {
    rowStrideBytes = 0;
    byteSize = 0;
    if (width == 0 || height == 0 ||
        !checked_multiply(width, static_cast<size_t>(3), rowStrideBytes)) {
        return false;
    }
    if (!checked_multiply(rowStrideBytes, height, byteSize)) {
        rowStrideBytes = 0;
        return false;
    }
    return true;
}

bool superres_rgb_failure_is_out_of_memory(const char* reason) noexcept {
    return reason != nullptr && std::strcmp(reason, "outofmem") == 0;
}

SuperResRgbImage convert_superres_bitmap_pixels_to_rgb(
    const unsigned char* source,
    int width,
    int height,
    size_t sourceStrideBytes,
    SuperResBitmapFormat format
) noexcept {
    if (source == nullptr || width <= 0 || height <= 0) {
        return {};
    }

    const size_t sourcePixelBytes = format == SuperResBitmapFormat::Rgba8888 ? 4u : 2u;
    size_t minimumSourceRowBytes = 0;
    size_t sourceByteSize = 0;
    if (!checked_multiply(
            static_cast<size_t>(width),
            sourcePixelBytes,
            minimumSourceRowBytes
        ) || sourceStrideBytes < minimumSourceRowBytes ||
        !checked_multiply(
            sourceStrideBytes,
            static_cast<size_t>(height),
            sourceByteSize
        )) {
        return {};
    }
    (void)sourceByteSize;

    size_t rowStrideBytes = 0;
    size_t byteSize = 0;
    if (!checked_superres_rgb_layout(
            static_cast<size_t>(width),
            static_cast<size_t>(height),
            rowStrideBytes,
            byteSize
        )) {
        return {};
    }

    auto output = SuperResRgbPixels(
        new (std::nothrow) unsigned char[byteSize],
        SuperResRgbDeleter{}
    );
    if (output == nullptr) {
        return {};
    }

    for (int y = 0; y < height; ++y) {
        const unsigned char* sourceRow =
            source + static_cast<size_t>(y) * sourceStrideBytes;
        unsigned char* destinationRow = output.get() + static_cast<size_t>(y) * rowStrideBytes;
        for (int x = 0; x < width; ++x) {
            const size_t destinationOffset = static_cast<size_t>(x) * 3;
            if (format == SuperResBitmapFormat::Rgba8888) {
                const unsigned char* pixel = sourceRow + static_cast<size_t>(x) * 4;
                destinationRow[destinationOffset] = pixel[0];
                destinationRow[destinationOffset + 1] = pixel[1];
                destinationRow[destinationOffset + 2] = pixel[2];
            } else {
                uint16_t value = 0;
                std::memcpy(
                    &value,
                    sourceRow + static_cast<size_t>(x) * sizeof(uint16_t),
                    sizeof(value)
                );
                destinationRow[destinationOffset] =
                    static_cast<unsigned char>(((value >> 11) & 0x1f) * 255 / 31);
                destinationRow[destinationOffset + 1] =
                    static_cast<unsigned char>(((value >> 5) & 0x3f) * 255 / 63);
                destinationRow[destinationOffset + 2] =
                    static_cast<unsigned char>((value & 0x1f) * 255 / 31);
            }
        }
    }

    return SuperResRgbImage(
        width,
        height,
        rowStrideBytes,
        byteSize,
        std::move(output)
    );
}
