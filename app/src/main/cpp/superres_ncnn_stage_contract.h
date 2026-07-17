#pragma once

#include "superres_tile_geometry.h"

#include <limits>

enum class SuperResNcnnStageGeometryStatus {
    Ok,
    InvalidParams,
    OutputTooLarge
};

struct SuperResNcnnStageCrop {
    bool enabled = false;
    int left = 0;
    int top = 0;
    int width = 0;
    int height = 0;
};

struct SuperResNcnnStageGeometry {
    SuperResNcnnStageGeometryStatus status = SuperResNcnnStageGeometryStatus::InvalidParams;
    int tileSize = 0;
    int tileOverlap = 0;
    int maximumBandHeight = 0;
    int tilesX = 0;
    int tilesY = 0;
    int totalTiles = 0;
    int outputWidth = 0;
    int outputHeight = 0;
    int bufferWidth = 0;
    int bufferHeight = 0;
    long long expectedCoveredPixels = 0;
};

constexpr bool checked_superres_ncnn_stage_total_tiles(
    int tilesX,
    int tilesY,
    int& totalTiles
) {
    if (tilesX <= 0 || tilesY <= 0 ||
        !safe_mul_leq(tilesX, tilesY, std::numeric_limits<int>::max())) {
        totalTiles = 0;
        return false;
    }
    totalTiles = tilesX * tilesY;
    return true;
}

constexpr bool checked_superres_ncnn_stage_coverage(
    long long width,
    long long height,
    long long& coveredPixels
) {
    if (width <= 0 || height <= 0 ||
        !safe_mul_leq(width, height, std::numeric_limits<long long>::max())) {
        coveredPixels = 0;
        return false;
    }
    coveredPixels = width * height;
    return true;
}

constexpr SuperResNcnnStageGeometry resolve_superres_ncnn_stage_geometry(
    int imageWidth,
    int imageHeight,
    int scale,
    int requestedTileSize,
    SuperResNcnnStageCrop crop
) {
    SuperResNcnnStageGeometry geometry;
    if (!output_pixels_within_budget(imageWidth, imageHeight, scale)) {
        geometry.status = SuperResNcnnStageGeometryStatus::OutputTooLarge;
        return geometry;
    }

    geometry.tileSize = requestedTileSize < 32 ? 32 : requestedTileSize;
    if (!safe_mul_leq(geometry.tileSize, scale, kMaxIntDimension)) {
        return geometry;
    }
    geometry.maximumBandHeight = geometry.tileSize * scale;
    geometry.tileOverlap = min_int(32, max_int(8, geometry.tileSize / 4));
    geometry.tilesX = 1 + (imageWidth - 1) / geometry.tileSize;
    geometry.tilesY = 1 + (imageHeight - 1) / geometry.tileSize;
    if (!checked_superres_ncnn_stage_total_tiles(
            geometry.tilesX,
            geometry.tilesY,
            geometry.totalTiles
        )) {
        return geometry;
    }
    geometry.outputWidth = imageWidth * scale;
    geometry.outputHeight = imageHeight * scale;

    if (crop.enabled) {
        if (crop.left < 0 || crop.top < 0 || crop.width <= 0 || crop.height <= 0 ||
            crop.left > geometry.outputWidth || crop.top > geometry.outputHeight ||
            crop.width > geometry.outputWidth - crop.left ||
            crop.height > geometry.outputHeight - crop.top) {
            return geometry;
        }
        geometry.bufferWidth = crop.width;
        geometry.bufferHeight = crop.height;
    } else {
        geometry.bufferWidth = geometry.outputWidth;
        geometry.bufferHeight = geometry.outputHeight;
    }
    if (!checked_superres_ncnn_stage_coverage(
            geometry.bufferWidth,
            geometry.bufferHeight,
            geometry.expectedCoveredPixels
        )) {
        return geometry;
    }
    geometry.status = SuperResNcnnStageGeometryStatus::Ok;
    return geometry;
}
