#include "superres_stbi_decode.h"

#include <mutex>
#include <utility>

namespace {
std::mutex& stbi_decode_mutex() {
    static std::mutex mutex;
    return mutex;
}
} // namespace

SuperResRgbDecodeResult decode_superres_rgb_image(const std::string& path,
                                                  const SuperResStbiApi& api) {
    if (api.load == nullptr || api.release == nullptr) {
        return {};
    }

    int width = 0;
    int height = 0;
    int channels = 0;
    unsigned char* rawPixels = nullptr;
    bool outOfMemory = false;
    {
        // stb exposes its last failure through shared global state, so load and
        // classification must stay in the same critical section across all decoding
        // callers.
        std::lock_guard<std::mutex> lock(stbi_decode_mutex());
        rawPixels = api.load(path.c_str(), &width, &height, &channels, 3);
        if (rawPixels == nullptr && api.failureReason != nullptr) {
            outOfMemory = superres_rgb_failure_is_out_of_memory(api.failureReason());
        }
    }

    if (rawPixels == nullptr) {
        return SuperResRgbDecodeResult{outOfMemory ? SuperResRgbDecodeStatus::OutOfMemory
                                                   : SuperResRgbDecodeStatus::DecodeFailed,
                                       {}};
    }

    SuperResRgbPixels pixels(rawPixels, SuperResRgbDeleter{api.release});
    if (width <= 0 || height <= 0) {
        return {};
    }
    size_t rowStrideBytes = 0;
    size_t byteSize = 0;
    if (!checked_superres_rgb_layout(
            static_cast<size_t>(width), static_cast<size_t>(height), rowStrideBytes, byteSize)) {
        return {};
    }
    return SuperResRgbDecodeResult{
        SuperResRgbDecodeStatus::Ok,
        SuperResRgbImage(width, height, rowStrideBytes, byteSize, std::move(pixels))};
}
