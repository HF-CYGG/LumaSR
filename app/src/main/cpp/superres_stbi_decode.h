#pragma once

#include "superres_rgb_image.h"

#include <string>

struct SuperResStbiApi {
    unsigned char* (*load)(const char*, int*, int*, int*, int);
    const char* (*failureReason)();
    SuperResRgbRelease release;
};

enum class SuperResRgbDecodeStatus { Ok, DecodeFailed, OutOfMemory };

struct SuperResRgbDecodeResult {
    SuperResRgbDecodeStatus status = SuperResRgbDecodeStatus::DecodeFailed;
    SuperResRgbImage image;
};

SuperResRgbDecodeResult decode_superres_rgb_image(const std::string& path,
                                                  const SuperResStbiApi& api);
