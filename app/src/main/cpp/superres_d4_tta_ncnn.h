#pragma once

#include "superres_d4_tta.h"

#include "mat.h"

inline bool d4_ncnn_mat_is_planar_float(const ncnn::Mat& mat) {
    return !mat.empty() && mat.dims == 3 && mat.w > 0 && mat.h > 0 && mat.c > 0 &&
        mat.elempack == 1 && mat.elembits() == 32;
}

inline bool d4_transform_ncnn_mat(
    const ncnn::Mat& source,
    D4Transform transform,
    ncnn::Mat& destination
) {
    if (!d4_ncnn_mat_is_planar_float(source)) {
        return false;
    }
    const D4Shape sourceShape{source.w, source.h};
    const D4Shape destinationShape = d4_transformed_shape(sourceShape, transform);
    if (!d4_shape_valid(destinationShape)) {
        return false;
    }

    ncnn::Mat transformed(destinationShape.width, destinationShape.height, source.c, sizeof(float));
    if (transformed.empty()) {
        return false;
    }
    for (int c = 0; c < source.c; ++c) {
        const ncnn::Mat sourceChannel = source.channel(c);
        ncnn::Mat destinationChannel = transformed.channel(c);
        for (int y = 0; y < source.h; ++y) {
            const float* sourceRow = sourceChannel.row(y);
            for (int x = 0; x < source.w; ++x) {
                const D4Coordinate transformedCoordinate = d4_transform_coordinate(
                    sourceShape,
                    D4Coordinate{x, y},
                    transform
                );
                float* destinationRow = destinationChannel.row(transformedCoordinate.y);
                destinationRow[transformedCoordinate.x] = sourceRow[x];
            }
        }
    }
    destination = transformed;
    return true;
}

inline bool d4_add_ncnn_to_fusion(
    const ncnn::Mat& transformed,
    D4Transform transform,
    D4FloatFusion& fusion
) {
    if (!d4_ncnn_mat_is_planar_float(transformed)) {
        return false;
    }
    return fusion.add(
        reinterpret_cast<const float*>(transformed.data),
        D4Shape{transformed.w, transformed.h},
        transformed.c,
        transformed.cstep,
        transform
    );
}

inline bool d4_finish_ncnn_fusion(const D4FloatFusion& fusion, ncnn::Mat& destination) {
    std::vector<float> average;
    if (!fusion.finish(average)) {
        return false;
    }
    const D4Shape shape = fusion.canonical_shape();
    const int channels = fusion.channels();
    if (!d4_shape_valid(shape) || channels <= 0) {
        return false;
    }

    ncnn::Mat fused(shape.width, shape.height, channels, sizeof(float));
    if (fused.empty()) {
        return false;
    }
    const size_t plane = static_cast<size_t>(shape.width) * shape.height;
    for (int c = 0; c < channels; ++c) {
        ncnn::Mat channel = fused.channel(c);
        for (int y = 0; y < shape.height; ++y) {
            float* row = channel.row(y);
            for (int x = 0; x < shape.width; ++x) {
                const size_t index = static_cast<size_t>(c) * plane +
                    static_cast<size_t>(y) * shape.width + x;
                row[x] = average[index];
            }
        }
    }
    destination = fused;
    return true;
}
