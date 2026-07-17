#pragma once

#include <cstddef>
#include <memory>

using SuperResRgbRelease = void (*)(unsigned char*) noexcept;

void delete_superres_rgb_array(unsigned char* pixels) noexcept;

struct SuperResRgbDeleter {
    SuperResRgbRelease release = delete_superres_rgb_array;

    void operator()(unsigned char* pixels) const noexcept {
        if (pixels != nullptr && release != nullptr) {
            release(pixels);
        }
    }
};

using SuperResRgbPixels = std::unique_ptr<unsigned char[], SuperResRgbDeleter>;

class SuperResRgbImage {
public:
    SuperResRgbImage() noexcept = default;
    SuperResRgbImage(
        int width,
        int height,
        size_t rowStrideBytes,
        size_t byteSize,
        SuperResRgbPixels pixels
    ) noexcept;

    SuperResRgbImage(SuperResRgbImage&&) noexcept = default;
    SuperResRgbImage& operator=(SuperResRgbImage&&) noexcept = default;
    SuperResRgbImage(const SuperResRgbImage&) = delete;
    SuperResRgbImage& operator=(const SuperResRgbImage&) = delete;

    bool valid() const noexcept;
    int width() const noexcept { return width_; }
    int height() const noexcept { return height_; }
    size_t row_stride_bytes() const noexcept { return rowStrideBytes_; }
    size_t byte_size() const noexcept { return byteSize_; }
    unsigned char* data() noexcept { return pixels_.get(); }
    const unsigned char* data() const noexcept { return pixels_.get(); }

private:
    int width_ = 0;
    int height_ = 0;
    size_t rowStrideBytes_ = 0;
    size_t byteSize_ = 0;
    SuperResRgbPixels pixels_;
};

enum class SuperResBitmapFormat {
    Rgba8888,
    Rgb565
};

bool checked_superres_rgb_layout(
    size_t width,
    size_t height,
    size_t& rowStrideBytes,
    size_t& byteSize
) noexcept;

bool superres_rgb_failure_is_out_of_memory(const char* reason) noexcept;

SuperResRgbImage convert_superres_bitmap_pixels_to_rgb(
    const unsigned char* source,
    int width,
    int height,
    size_t sourceStrideBytes,
    SuperResBitmapFormat format
) noexcept;

template <typename Attempt, typename ShouldRetryCpu, typename BeforeCpuFallback>
auto run_superres_rgb_attempts(
    const SuperResRgbImage& image,
    bool tryVulkan,
    Attempt&& attempt,
    ShouldRetryCpu&& shouldRetryCpu,
    BeforeCpuFallback&& beforeCpuFallback
) -> decltype(attempt(image, true)) {
    if (tryVulkan) {
        auto result = attempt(image, true);
        if (!shouldRetryCpu(result)) {
            return result;
        }
        beforeCpuFallback(result);
    }
    return attempt(image, false);
}
