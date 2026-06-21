#pragma once

struct TileInputRegion {
    int inputX;
    int inputY;
    int inputW;
    int inputH;
    int cropX;
    int cropY;
    int dstX;
    int dstY;
    int copyW;
    int copyH;
};

constexpr int align_delta(int value, int alignment) {
    if (alignment <= 1) {
        return 0;
    }
    return ((value + alignment - 1) / alignment) * alignment - value;
}

constexpr int waifu2x_alignment(int scale) {
    if (scale == 1) {
        return 4;
    }
    if (scale == 2) {
        return 2;
    }
    return 1;
}

constexpr int realcugan_alignment(int scale) {
    if (scale == 1 || scale == 3) {
        return 4;
    }
    if (scale == 2 || scale == 4) {
        return 2;
    }
    return 1;
}

constexpr bool tile_output_has_rgb_channels(int channels, int elempack) {
    return channels * elempack >= 3;
}

constexpr bool tile_output_needs_unpacking(int channels, int elempack) {
    return tile_output_has_rgb_channels(channels, elempack) && elempack != 1;
}

constexpr bool tile_output_needs_fp32_conversion(int elemsize, int elempack) {
    if (elemsize <= 0 || elempack <= 0) {
        return false;
    }
    return elemsize * 8 / elempack == 16;
}

constexpr int resolve_waifu2x_output_crop(int outputSize, int copySize) {
    if (outputSize < copySize || copySize <= 0) {
        return -1;
    }
    return 0;
}

constexpr int resolve_tile_output_crop(int outputSize, int copySize, int preferredCrop) {
    if (outputSize < copySize || copySize <= 0 || preferredCrop < 0) {
        return -1;
    }
    const int maxCrop = outputSize - copySize;
    return preferredCrop < maxCrop ? preferredCrop : maxCrop;
}

constexpr bool should_retry_cpu_after_gpu_code(int accelerationMode, int nativeCode) {
    constexpr int accelerationAuto = 0;
    constexpr int codeVulkanFailed = 8;
    constexpr int codeTileOutputMismatch = 9;
    return accelerationMode == accelerationAuto &&
        (nativeCode == codeVulkanFailed || nativeCode == codeTileOutputMismatch);
}

constexpr bool should_force_cpu_before_vulkan_model_load(int engineType) {
    constexpr int engineRealEsrgan = 2;
#if defined(__i386__) || defined(__x86_64__)
    return engineType == engineRealEsrgan;
#else
    (void)engineType;
    return false;
#endif
}

constexpr bool contains_token(const char* text, const char* token) {
    if (text == nullptr || token == nullptr || token[0] == '\0') {
        return false;
    }
    for (int i = 0; text[i] != '\0'; ++i) {
        int j = 0;
        while (token[j] != '\0' && text[i + j] != '\0' && text[i + j] == token[j]) {
            ++j;
        }
        if (token[j] == '\0') {
            return true;
        }
    }
    return false;
}

constexpr bool is_large_realesrgan_model_base(const char* modelFileBase) {
    return contains_token(modelFileBase, "realesrgan-x4plus");
}

constexpr bool is_waifu2x_cunet_dir(const char* modelDir) {
    return contains_token(modelDir, "models-cunet") ||
        contains_token(modelDir, "waifu2x-cunet");
}

constexpr bool is_waifu2x_upconv_dir(const char* modelDir) {
    return contains_token(modelDir, "models-upconv_7_anime_style_art_rgb") ||
        contains_token(modelDir, "models-upconv_7_photo") ||
        contains_token(modelDir, "waifu2x-anime") ||
        contains_token(modelDir, "waifu2x-photo");
}

constexpr TileInputRegion make_waifu2x_tile_region(
    int scale,
    int prepadding,
    int imageW,
    int imageH,
    int tileX,
    int tileY,
    int tileW,
    int tileH
) {
    const int alignment = waifu2x_alignment(scale);
    const int rightPadding = prepadding + align_delta(imageW, alignment);
    const int bottomPadding = prepadding + align_delta(imageH, alignment);
    return TileInputRegion{
        tileX - prepadding,
        tileY - prepadding,
        tileW + prepadding + rightPadding,
        tileH + prepadding + bottomPadding,
        0,
        0,
        tileX * scale,
        tileY * scale,
        tileW * scale,
        tileH * scale
    };
}

constexpr TileInputRegion make_realcugan_tile_region(
    int scale,
    int prepadding,
    int imageW,
    int imageH,
    int tileX,
    int tileY,
    int tileW,
    int tileH
) {
    (void)imageW;
    (void)imageH;
    const int alignment = realcugan_alignment(scale);
    const int rightPadding = prepadding + align_delta(tileW, alignment);
    const int bottomPadding = prepadding + align_delta(tileH, alignment);
    return TileInputRegion{
        tileX - prepadding,
        tileY - prepadding,
        tileW + prepadding + rightPadding,
        tileH + prepadding + bottomPadding,
        0,
        0,
        tileX * scale,
        tileY * scale,
        tileW * scale,
        tileH * scale
    };
}
