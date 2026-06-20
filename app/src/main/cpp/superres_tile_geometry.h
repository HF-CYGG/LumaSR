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
