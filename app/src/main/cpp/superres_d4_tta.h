#pragma once

#include <array>
#include <cstddef>
#include <limits>
#include <vector>

enum class D4Transform {
    Identity = 0,
    Rotate90 = 1,
    Rotate180 = 2,
    Rotate270 = 3,
    Mirror = 4,
    MirrorRotate90 = 5,
    MirrorRotate180 = 6,
    MirrorRotate270 = 7
};

inline constexpr std::array<D4Transform, 8> kD4Transforms{
    D4Transform::Identity,
    D4Transform::Rotate90,
    D4Transform::Rotate180,
    D4Transform::Rotate270,
    D4Transform::Mirror,
    D4Transform::MirrorRotate90,
    D4Transform::MirrorRotate180,
    D4Transform::MirrorRotate270
};

struct D4Shape {
    int width;
    int height;
};

constexpr bool operator==(D4Shape lhs, D4Shape rhs) {
    return lhs.width == rhs.width && lhs.height == rhs.height;
}

constexpr bool operator!=(D4Shape lhs, D4Shape rhs) {
    return !(lhs == rhs);
}

struct D4Coordinate {
    int x;
    int y;
};

constexpr bool operator==(D4Coordinate lhs, D4Coordinate rhs) {
    return lhs.x == rhs.x && lhs.y == rhs.y;
}

constexpr bool operator!=(D4Coordinate lhs, D4Coordinate rhs) {
    return !(lhs == rhs);
}

constexpr bool d4_shape_valid(D4Shape shape) {
    return shape.width > 0 && shape.height > 0;
}

constexpr bool d4_coordinate_within_shape(D4Shape shape, D4Coordinate coordinate) {
    return d4_shape_valid(shape) &&
        coordinate.x >= 0 && coordinate.y >= 0 &&
        coordinate.x < shape.width && coordinate.y < shape.height;
}

constexpr int d4_rotation_quarter_turns(D4Transform transform) {
    switch (transform) {
        case D4Transform::Identity:
        case D4Transform::Mirror:
            return 0;
        case D4Transform::Rotate90:
        case D4Transform::MirrorRotate90:
            return 1;
        case D4Transform::Rotate180:
        case D4Transform::MirrorRotate180:
            return 2;
        case D4Transform::Rotate270:
        case D4Transform::MirrorRotate270:
            return 3;
    }
    return -1;
}

constexpr bool d4_is_mirrored(D4Transform transform) {
    return transform == D4Transform::Mirror ||
        transform == D4Transform::MirrorRotate90 ||
        transform == D4Transform::MirrorRotate180 ||
        transform == D4Transform::MirrorRotate270;
}

constexpr D4Shape d4_transformed_shape(D4Shape sourceShape, D4Transform transform) {
    const int rotation = d4_rotation_quarter_turns(transform);
    if (!d4_shape_valid(sourceShape) || rotation < 0) {
        return D4Shape{0, 0};
    }
    return rotation % 2 == 0
        ? sourceShape
        : D4Shape{sourceShape.height, sourceShape.width};
}

constexpr D4Shape d4_inverse_shape(D4Shape transformedShape, D4Transform transform) {
    return d4_transformed_shape(transformedShape, transform);
}

// Mirrored variants apply a horizontal reflection first, followed by the named clockwise rotation.
constexpr D4Coordinate d4_transform_coordinate(
    D4Shape sourceShape,
    D4Coordinate source,
    D4Transform transform
) {
    if (!d4_coordinate_within_shape(sourceShape, source)) {
        return D4Coordinate{-1, -1};
    }
    const int rotation = d4_rotation_quarter_turns(transform);
    if (rotation < 0) {
        return D4Coordinate{-1, -1};
    }

    const int mirroredX = d4_is_mirrored(transform)
        ? sourceShape.width - 1 - source.x
        : source.x;
    switch (rotation) {
        case 0:
            return D4Coordinate{mirroredX, source.y};
        case 1:
            return D4Coordinate{sourceShape.height - 1 - source.y, mirroredX};
        case 2:
            return D4Coordinate{sourceShape.width - 1 - mirroredX, sourceShape.height - 1 - source.y};
        case 3:
            return D4Coordinate{source.y, sourceShape.width - 1 - mirroredX};
        default:
            return D4Coordinate{-1, -1};
    }
}

constexpr D4Coordinate d4_inverse_coordinate(
    D4Shape sourceShape,
    D4Coordinate transformed,
    D4Transform transform
) {
    const D4Shape transformedShape = d4_transformed_shape(sourceShape, transform);
    if (!d4_coordinate_within_shape(transformedShape, transformed)) {
        return D4Coordinate{-1, -1};
    }
    const int rotation = d4_rotation_quarter_turns(transform);
    if (rotation < 0) {
        return D4Coordinate{-1, -1};
    }

    D4Coordinate mirroredSource{-1, -1};
    switch (rotation) {
        case 0:
            mirroredSource = transformed;
            break;
        case 1:
            mirroredSource = D4Coordinate{transformed.y, sourceShape.height - 1 - transformed.x};
            break;
        case 2:
            mirroredSource = D4Coordinate{
                sourceShape.width - 1 - transformed.x,
                sourceShape.height - 1 - transformed.y
            };
            break;
        case 3:
            mirroredSource = D4Coordinate{sourceShape.width - 1 - transformed.y, transformed.x};
            break;
        default:
            return D4Coordinate{-1, -1};
    }
    return D4Coordinate{
        d4_is_mirrored(transform) ? sourceShape.width - 1 - mirroredSource.x : mirroredSource.x,
        mirroredSource.y
    };
}

inline bool d4_planar_element_count(D4Shape shape, int channels, size_t& elementCount) {
    if (!d4_shape_valid(shape) || channels <= 0) {
        return false;
    }
    const size_t width = static_cast<size_t>(shape.width);
    const size_t height = static_cast<size_t>(shape.height);
    const size_t channelCount = static_cast<size_t>(channels);
    if (width > std::numeric_limits<size_t>::max() / height) {
        return false;
    }
    const size_t pixels = width * height;
    if (pixels > std::numeric_limits<size_t>::max() / channelCount) {
        return false;
    }
    elementCount = pixels * channelCount;
    return true;
}

inline bool d4_transform_planar_float(
    const std::vector<float>& source,
    D4Shape sourceShape,
    int channels,
    D4Transform transform,
    std::vector<float>& destination
) {
    size_t elementCount = 0;
    const D4Shape destinationShape = d4_transformed_shape(sourceShape, transform);
    if (!d4_planar_element_count(sourceShape, channels, elementCount) ||
        !d4_shape_valid(destinationShape) || source.size() != elementCount) {
        return false;
    }
    try {
        destination.assign(elementCount, 0.0f);
    } catch (...) {
        return false;
    }
    const size_t sourcePlane = static_cast<size_t>(sourceShape.width) * sourceShape.height;
    const size_t destinationPlane = static_cast<size_t>(destinationShape.width) * destinationShape.height;
    for (int c = 0; c < channels; ++c) {
        for (int y = 0; y < sourceShape.height; ++y) {
            for (int x = 0; x < sourceShape.width; ++x) {
                const D4Coordinate transformed = d4_transform_coordinate(
                    sourceShape,
                    D4Coordinate{x, y},
                    transform
                );
                const size_t sourceIndex = static_cast<size_t>(c) * sourcePlane +
                    static_cast<size_t>(y) * sourceShape.width + x;
                const size_t destinationIndex = static_cast<size_t>(c) * destinationPlane +
                    static_cast<size_t>(transformed.y) * destinationShape.width + transformed.x;
                destination[destinationIndex] = source[sourceIndex];
            }
        }
    }
    return true;
}

inline bool d4_inverse_planar_float(
    const std::vector<float>& transformed,
    D4Shape sourceShape,
    int channels,
    D4Transform transform,
    std::vector<float>& destination
) {
    size_t elementCount = 0;
    const D4Shape transformedShape = d4_transformed_shape(sourceShape, transform);
    if (!d4_planar_element_count(sourceShape, channels, elementCount) ||
        !d4_shape_valid(transformedShape) || transformed.size() != elementCount) {
        return false;
    }
    try {
        destination.assign(elementCount, 0.0f);
    } catch (...) {
        return false;
    }
    const size_t sourcePlane = static_cast<size_t>(sourceShape.width) * sourceShape.height;
    const size_t transformedPlane = static_cast<size_t>(transformedShape.width) * transformedShape.height;
    for (int c = 0; c < channels; ++c) {
        for (int y = 0; y < sourceShape.height; ++y) {
            for (int x = 0; x < sourceShape.width; ++x) {
                const D4Coordinate transformedCoordinate = d4_transform_coordinate(
                    sourceShape,
                    D4Coordinate{x, y},
                    transform
                );
                const size_t destinationIndex = static_cast<size_t>(c) * sourcePlane +
                    static_cast<size_t>(y) * sourceShape.width + x;
                const size_t transformedIndex = static_cast<size_t>(c) * transformedPlane +
                    static_cast<size_t>(transformedCoordinate.y) * transformedShape.width + transformedCoordinate.x;
                destination[destinationIndex] = transformed[transformedIndex];
            }
        }
    }
    return true;
}

class D4FloatFusion {
public:
    bool add(
        const float* transformed,
        D4Shape transformedShape,
        int channels,
        size_t channelStride,
        D4Transform transform
    ) {
        if (transformed == nullptr || passes_ >= static_cast<int>(kD4Transforms.size()) ||
            transform != kD4Transforms[static_cast<size_t>(passes_)]) {
            return false;
        }

        if (passes_ == 0) {
            if (transform != D4Transform::Identity) {
                return false;
            }
            canonicalShape_ = transformedShape;
            channels_ = channels;
        }

        size_t elementCount = 0;
        if (!d4_planar_element_count(canonicalShape_, channels_, elementCount) ||
            channels != channels_ ||
            transformedShape != d4_transformed_shape(canonicalShape_, transform)) {
            return false;
        }
        const size_t transformedPlane = static_cast<size_t>(transformedShape.width) * transformedShape.height;
        if (channelStride < transformedPlane ||
            channelStride > std::numeric_limits<size_t>::max() / static_cast<size_t>(channels_)) {
            return false;
        }

        if (passes_ == 0) {
            try {
                sum_.assign(elementCount, 0.0f);
            } catch (...) {
                canonicalShape_ = D4Shape{0, 0};
                channels_ = 0;
                return false;
            }
        } else if (sum_.size() != elementCount) {
            return false;
        }

        const size_t canonicalPlane = static_cast<size_t>(canonicalShape_.width) * canonicalShape_.height;
        for (int c = 0; c < channels_; ++c) {
            const float* transformedChannel = transformed + static_cast<size_t>(c) * channelStride;
            for (int y = 0; y < canonicalShape_.height; ++y) {
                for (int x = 0; x < canonicalShape_.width; ++x) {
                    const D4Coordinate transformedCoordinate = d4_transform_coordinate(
                        canonicalShape_,
                        D4Coordinate{x, y},
                        transform
                    );
                    const size_t canonicalIndex = static_cast<size_t>(c) * canonicalPlane +
                        static_cast<size_t>(y) * canonicalShape_.width + x;
                    const size_t transformedIndex = static_cast<size_t>(transformedCoordinate.y) *
                        transformedShape.width + transformedCoordinate.x;
                    sum_[canonicalIndex] += transformedChannel[transformedIndex];
                }
            }
        }
        ++passes_;
        return true;
    }

    bool finish(std::vector<float>& average) const {
        size_t elementCount = 0;
        if (passes_ != static_cast<int>(kD4Transforms.size()) ||
            !d4_planar_element_count(canonicalShape_, channels_, elementCount) ||
            sum_.size() != elementCount) {
            return false;
        }
        try {
            average = sum_;
        } catch (...) {
            return false;
        }
        const float divisor = static_cast<float>(kD4Transforms.size());
        for (float& value : average) {
            value /= divisor;
        }
        return true;
    }

    D4Shape canonical_shape() const {
        return canonicalShape_;
    }

    int channels() const {
        return channels_;
    }

private:
    D4Shape canonicalShape_{0, 0};
    int channels_ = 0;
    int passes_ = 0;
    std::vector<float> sum_;
};

bool run_d4_tta_runtime_checks();
