#include "superres_d4_tta.h"
#include "superres_d4_tta_ncnn.h"

#include <array>
#include <vector>

namespace {

constexpr D4Shape kLandscape{3, 2};

static_assert(kD4Transforms.size() == 8);
static_assert(d4_transformed_shape(kLandscape, D4Transform::Identity) == D4Shape{3, 2});
static_assert(d4_transformed_shape(kLandscape, D4Transform::Rotate90) == D4Shape{2, 3});
static_assert(d4_transformed_shape(kLandscape, D4Transform::Rotate180) == D4Shape{3, 2});
static_assert(d4_transformed_shape(kLandscape, D4Transform::Rotate270) == D4Shape{2, 3});
static_assert(d4_transformed_shape(kLandscape, D4Transform::Mirror) == D4Shape{3, 2});
static_assert(d4_transformed_shape(kLandscape, D4Transform::MirrorRotate90) == D4Shape{2, 3});
static_assert(d4_transformed_shape(kLandscape, D4Transform::MirrorRotate180) == D4Shape{3, 2});
static_assert(d4_transformed_shape(kLandscape, D4Transform::MirrorRotate270) == D4Shape{2, 3});

static_assert(d4_inverse_shape(D4Shape{2, 3}, D4Transform::Rotate90) == kLandscape);
static_assert(d4_inverse_shape(D4Shape{2, 3}, D4Transform::MirrorRotate270) == kLandscape);

static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::Identity) == D4Coordinate{0, 0});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::Rotate90) == D4Coordinate{1, 0});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::Rotate180) == D4Coordinate{2, 1});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::Rotate270) == D4Coordinate{0, 2});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::Mirror) == D4Coordinate{2, 0});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::MirrorRotate90) == D4Coordinate{1, 2});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::MirrorRotate180) == D4Coordinate{0, 1});
static_assert(d4_transform_coordinate(kLandscape, D4Coordinate{0, 0}, D4Transform::MirrorRotate270) == D4Coordinate{0, 0});

}  // namespace

bool run_d4_tta_runtime_checks() {
    constexpr D4Shape sourceShape{5, 3};
    for (const D4Transform transform : kD4Transforms) {
        const D4Shape transformedShape = d4_transformed_shape(sourceShape, transform);
        if (d4_inverse_shape(transformedShape, transform) != sourceShape) {
            return false;
        }

        std::array<bool, sourceShape.width * sourceShape.height> destinations{};
        for (int y = 0; y < sourceShape.height; ++y) {
            for (int x = 0; x < sourceShape.width; ++x) {
                const D4Coordinate source{x, y};
                const D4Coordinate destination = d4_transform_coordinate(sourceShape, source, transform);
                if (!d4_coordinate_within_shape(transformedShape, destination)) {
                    return false;
                }
                const int destinationIndex = destination.y * transformedShape.width + destination.x;
                if (destinations[static_cast<size_t>(destinationIndex)]) {
                    return false;
                }
                destinations[static_cast<size_t>(destinationIndex)] = true;
                if (d4_inverse_coordinate(sourceShape, destination, transform) != source) {
                    return false;
                }
            }
        }
        for (const bool visited : destinations) {
            if (!visited) {
                return false;
            }
        }
    }

    const std::vector<float> planar{
        0.0f, 1.0f, 2.0f,
        3.0f, 4.0f, 5.0f,
        10.0f, 11.0f, 12.0f,
        13.0f, 14.0f, 15.0f
    };
    const std::vector<float> expectedRotate90{
        3.0f, 0.0f,
        4.0f, 1.0f,
        5.0f, 2.0f,
        13.0f, 10.0f,
        14.0f, 11.0f,
        15.0f, 12.0f
    };
    std::vector<float> transformed;
    if (!d4_transform_planar_float(planar, kLandscape, 2, D4Transform::Rotate90, transformed) ||
        transformed != expectedRotate90) {
        return false;
    }
    for (const D4Transform transform : kD4Transforms) {
        std::vector<float> restored;
        if (!d4_transform_planar_float(planar, kLandscape, 2, transform, transformed) ||
            !d4_inverse_planar_float(transformed, kLandscape, 2, transform, restored) ||
            restored != planar) {
            return false;
        }
    }
    if (d4_transform_planar_float({}, kLandscape, 2, D4Transform::Identity, transformed) ||
        d4_transform_planar_float(planar, D4Shape{0, 2}, 2, D4Transform::Identity, transformed) ||
        d4_transform_planar_float(planar, kLandscape, 0, D4Transform::Identity, transformed)) {
        return false;
    }

    D4FloatFusion fusion;
    std::vector<float> average;
    for (size_t pass = 0; pass < kD4Transforms.size(); ++pass) {
        std::vector<float> passSource = planar;
        for (float& value : passSource) {
            value += static_cast<float>(pass);
        }
        const D4Transform transform = kD4Transforms[pass];
        const D4Shape passShape = d4_transformed_shape(kLandscape, transform);
        if (!d4_transform_planar_float(passSource, kLandscape, 2, transform, transformed) ||
            !fusion.add(
                transformed.data(),
                passShape,
                2,
                static_cast<size_t>(passShape.width) * passShape.height,
                transform
            )) {
            return false;
        }
        if (pass + 1 < kD4Transforms.size() && fusion.finish(average)) {
            return false;
        }
    }
    if (!fusion.finish(average) || average.size() != planar.size()) {
        return false;
    }
    for (size_t index = 0; index < average.size(); ++index) {
        if (average[index] != planar[index] + 3.5f) {
            return false;
        }
    }

    D4FloatFusion mismatch;
    if (!mismatch.add(
            planar.data(),
            kLandscape,
            2,
            static_cast<size_t>(kLandscape.width) * kLandscape.height,
            D4Transform::Identity
        ) || mismatch.add(
            planar.data(),
            kLandscape,
            2,
            static_cast<size_t>(kLandscape.width) * kLandscape.height,
            D4Transform::Rotate90
        )) {
        return false;
    }

    ncnn::Mat ncnnSource(kLandscape.width, kLandscape.height, 2, sizeof(float));
    if (ncnnSource.empty()) {
        return false;
    }
    for (int c = 0; c < 2; ++c) {
        ncnn::Mat channel = ncnnSource.channel(c);
        for (int y = 0; y < kLandscape.height; ++y) {
            float* row = channel.row(y);
            for (int x = 0; x < kLandscape.width; ++x) {
                row[x] = planar[static_cast<size_t>(c) * 6 + static_cast<size_t>(y) * 3 + x];
            }
        }
    }
    ncnn::Mat ncnnRotated;
    if (!d4_transform_ncnn_mat(ncnnSource, D4Transform::Rotate90, ncnnRotated) ||
        ncnnRotated.w != 2 || ncnnRotated.h != 3 || ncnnRotated.c != 2) {
        return false;
    }
    for (int c = 0; c < 2; ++c) {
        const ncnn::Mat channel = ncnnRotated.channel(c);
        for (int y = 0; y < ncnnRotated.h; ++y) {
            const float* row = channel.row(y);
            for (int x = 0; x < ncnnRotated.w; ++x) {
                const size_t index = static_cast<size_t>(c) * 6 + static_cast<size_t>(y) * 2 + x;
                if (row[x] != expectedRotate90[index]) {
                    return false;
                }
            }
        }
    }

    D4FloatFusion ncnnFusion;
    for (const D4Transform transform : kD4Transforms) {
        ncnn::Mat transformedMat;
        if (!d4_transform_ncnn_mat(ncnnSource, transform, transformedMat) ||
            !d4_add_ncnn_to_fusion(transformedMat, transform, ncnnFusion)) {
            return false;
        }
    }
    ncnn::Mat fusedMat;
    if (!d4_finish_ncnn_fusion(ncnnFusion, fusedMat) ||
        fusedMat.w != ncnnSource.w || fusedMat.h != ncnnSource.h || fusedMat.c != ncnnSource.c) {
        return false;
    }
    for (int c = 0; c < fusedMat.c; ++c) {
        const ncnn::Mat expectedChannel = ncnnSource.channel(c);
        const ncnn::Mat actualChannel = fusedMat.channel(c);
        for (int y = 0; y < fusedMat.h; ++y) {
            const float* expectedRow = expectedChannel.row(y);
            const float* actualRow = actualChannel.row(y);
            for (int x = 0; x < fusedMat.w; ++x) {
                if (actualRow[x] != expectedRow[x]) {
                    return false;
                }
            }
        }
    }
    return true;
}
