#include "superres_rgb_image.h"

#include <cstdint>
#include <limits>
#include <utility>

namespace {
int deleter_call_count = 0;

void counting_delete(unsigned char* pixels) noexcept {
    ++deleter_call_count;
    delete[] pixels;
}

bool ownership_and_deleter_check() {
    deleter_call_count = 0;
    const unsigned char* adopted_address = nullptr;
    {
        auto pixels = SuperResRgbPixels(
            new unsigned char[6]{1, 2, 3, 4, 5, 6},
            SuperResRgbDeleter{counting_delete}
        );
        SuperResRgbImage image(2, 1, 6, 6, std::move(pixels));
        if (!image.valid() || image.width() != 2 || image.height() != 1 ||
            image.row_stride_bytes() != 6 || image.byte_size() != 6) {
            return false;
        }
        adopted_address = image.data();
        SuperResRgbImage moved(std::move(image));
        if (image.data() != nullptr || moved.data() != adopted_address ||
            deleter_call_count != 0) {
            return false;
        }
    }
    return deleter_call_count == 1;
}

bool checked_layout_check() {
    size_t row_stride = 0;
    size_t byte_size = 0;
    if (!checked_superres_rgb_layout(3, 2, row_stride, byte_size) ||
        row_stride != 9 || byte_size != 18) {
        return false;
    }
    if (checked_superres_rgb_layout(0, 2, row_stride, byte_size) ||
        checked_superres_rgb_layout(
            2,
            std::numeric_limits<size_t>::max(),
            row_stride,
            byte_size
        )) {
        return false;
    }
    const size_t too_wide = std::numeric_limits<size_t>::max() / 3 + 1;
    return !checked_superres_rgb_layout(too_wide, 1, row_stride, byte_size) &&
        !checked_superres_rgb_layout(
            std::numeric_limits<size_t>::max() / 3,
            2,
            row_stride,
            byte_size
        );
}

bool owned_stride_validation_check() {
    SuperResRgbImage padded(
        1,
        2,
        4,
        8,
        SuperResRgbPixels(new unsigned char[8]{}, SuperResRgbDeleter{})
    );
    SuperResRgbImage short_stride(
        1,
        2,
        2,
        4,
        SuperResRgbPixels(new unsigned char[4]{}, SuperResRgbDeleter{})
    );
    SuperResRgbImage short_buffer(
        1,
        2,
        4,
        7,
        SuperResRgbPixels(new unsigned char[7]{}, SuperResRgbDeleter{})
    );
    return padded.valid() && !short_stride.valid() && !short_buffer.valid();
}

bool rgba8888_conversion_check() {
    const unsigned char source[] = {
        1, 2, 3, 255, 4, 5, 6, 128, 99, 98, 97, 96,
        7, 8, 9, 64, 10, 11, 12, 0, 95, 94, 93, 92
    };
    SuperResRgbImage image = convert_superres_bitmap_pixels_to_rgb(
        source,
        2,
        2,
        12,
        SuperResBitmapFormat::Rgba8888
    );
    const unsigned char expected[] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    if (!image.valid() || image.row_stride_bytes() != 6 || image.byte_size() != 12) {
        return false;
    }
    for (size_t index = 0; index < image.byte_size(); ++index) {
        if (image.data()[index] != expected[index]) return false;
    }
    return true;
}

bool rgb565_conversion_check() {
    const uint16_t source[] = {
        0xf800, 0x07e0, 0x1234,
        0x001f, 0xffff, 0x5678
    };
    SuperResRgbImage image = convert_superres_bitmap_pixels_to_rgb(
        reinterpret_cast<const unsigned char*>(source),
        2,
        2,
        sizeof(uint16_t) * 3,
        SuperResBitmapFormat::Rgb565
    );
    const unsigned char expected[] = {
        255, 0, 0, 0, 255, 0,
        0, 0, 255, 255, 255, 255
    };
    if (!image.valid()) return false;
    for (size_t index = 0; index < image.byte_size(); ++index) {
        if (image.data()[index] != expected[index]) return false;
    }
    return true;
}

bool invalid_bitmap_layout_check() {
    const unsigned char source[8] = {};
    return !convert_superres_bitmap_pixels_to_rgb(
                source,
                2,
                1,
                7,
                SuperResBitmapFormat::Rgba8888
            ).valid() &&
        !convert_superres_bitmap_pixels_to_rgb(
                source,
                2,
                1,
                3,
                SuperResBitmapFormat::Rgb565
            ).valid() &&
        !convert_superres_bitmap_pixels_to_rgb(
                nullptr,
                2,
                1,
                8,
                SuperResBitmapFormat::Rgba8888
            ).valid();
}

bool allocation_failure_reason_check() {
    return superres_rgb_failure_is_out_of_memory("outofmem") &&
        !superres_rgb_failure_is_out_of_memory("unknown image type") &&
        !superres_rgb_failure_is_out_of_memory(nullptr);
}

}

bool run_superres_rgb_image_runtime_checks() {
    return ownership_and_deleter_check() &&
        checked_layout_check() &&
        owned_stride_validation_check() &&
        rgba8888_conversion_check() &&
        rgb565_conversion_check() &&
        invalid_bitmap_layout_check() &&
        allocation_failure_reason_check();
}
