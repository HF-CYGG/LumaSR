// Streaming PNG merger for extreme export mode.
//
// The normal image path allocates a full RGB output buffer. Extreme export instead
// produces cropped raw RGB tiles and streams them into a single PNG row by row.
// This implementation avoids opening every tile at once and avoids O(rows * tiles)
// coverage scans during the final merge.
#include "superres_engine.h"
#include "superres_png_writer.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <limits>
#include <string>
#include <vector>

namespace {

struct ActiveTile {
    SuperResRawTile spec;
    FILE* file = nullptr;
};

bool checked_rgb_size(int width, int height, uintmax_t& bytes) {
    if (width <= 0 || height <= 0) {
        return false;
    }
    const uintmax_t w = static_cast<uintmax_t>(width);
    const uintmax_t h = static_cast<uintmax_t>(height);
    if (w > std::numeric_limits<uintmax_t>::max() / h) {
        return false;
    }
    const uintmax_t pixels = w * h;
    if (pixels > std::numeric_limits<uintmax_t>::max() / 3u) {
        return false;
    }
    bytes = pixels * 3u;
    return true;
}

bool checked_row_size(int outputWidth, size_t& bytes) {
    bytes = streaming_png_rgb_row_bytes(outputWidth);
    return bytes != 0;
}

bool validate_tile(const SuperResRawTile& tile, int outputWidth, int outputHeight) {
    if (tile.path.empty() || tile.x < 0 || tile.y < 0 || tile.width <= 0 || tile.height <= 0) {
        return false;
    }
    if (tile.x > outputWidth - tile.width || tile.y > outputHeight - tile.height) {
        return false;
    }
    uintmax_t expectedSize = 0;
    if (!checked_rgb_size(tile.width, tile.height, expectedSize)) {
        return false;
    }
    std::error_code error;
    const uintmax_t actualSize = std::filesystem::file_size(tile.path, error);
    return !error && actualSize == expectedSize;
}

void close_active_tiles(std::vector<ActiveTile>& activeTiles) {
    for (ActiveTile& active : activeTiles) {
        if (active.file != nullptr) {
            std::fclose(active.file);
            active.file = nullptr;
        }
    }
    activeTiles.clear();
}

bool active_tile_x_order(const ActiveTile& left, const ActiveTile& right) {
    return left.spec.x < right.spec.x;
}

} // namespace

SuperResNativeCode merge_raw_tiles_to_png_streaming_impl(
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    const std::vector<SuperResRawTile>& tiles
) {
    if (outputPath.empty() || outputWidth <= 0 || outputHeight <= 0 || tiles.empty()) {
        return SuperResNativeCode::InvalidParams;
    }

    size_t pngRowBytes = 0;
    if (!checked_row_size(outputWidth, pngRowBytes)) {
        return SuperResNativeCode::InvalidParams;
    }

    std::vector<SuperResRawTile> sortedTiles = tiles;
    for (const SuperResRawTile& tile : sortedTiles) {
        if (!validate_tile(tile, outputWidth, outputHeight)) {
            return SuperResNativeCode::OutputFailed;
        }
    }
    std::sort(
        sortedTiles.begin(),
        sortedTiles.end(),
        [](const SuperResRawTile& left, const SuperResRawTile& right) {
            if (left.y != right.y) return left.y < right.y;
            return left.x < right.x;
        }
    );

    StreamingPngWriter writer;
    if (writer.open(outputPath, outputWidth, outputHeight) != StreamingPngStatus::Ok) {
        return SuperResNativeCode::OutputFailed;
    }

    std::vector<ActiveTile> activeTiles;
    activeTiles.reserve(16);
    size_t nextTile = 0;
    bool ok = true;

    std::vector<unsigned char> row;
    std::vector<unsigned char> tileRow;
    try {
        row.resize(pngRowBytes, 0);
    } catch (...) {
        ok = false;
    }

    for (int y = 0; ok && y < outputHeight; ++y) {
        bool activeChanged = false;
        for (size_t i = 0; i < activeTiles.size();) {
            if (y >= activeTiles[i].spec.y + activeTiles[i].spec.height) {
                if (activeTiles[i].file != nullptr) {
                    std::fclose(activeTiles[i].file);
                }
                activeTiles.erase(activeTiles.begin() + static_cast<long>(i));
                activeChanged = true;
            } else {
                ++i;
            }
        }

        while (nextTile < sortedTiles.size() && sortedTiles[nextTile].y <= y) {
            const SuperResRawTile& tile = sortedTiles[nextTile];
            if (tile.y != y) {
                ok = false;
                break;
            }
            FILE* tileFile = std::fopen(tile.path.c_str(), "rb");
            if (tileFile == nullptr) {
                ok = false;
                break;
            }
            activeTiles.push_back(ActiveTile{tile, tileFile});
            ++nextTile;
            activeChanged = true;
        }
        if (!ok) break;
        if (activeChanged) {
            std::sort(activeTiles.begin(), activeTiles.end(), active_tile_x_order);
        }

        int cursorX = 0;
        std::fill(row.begin(), row.end(), 0);
        for (ActiveTile& active : activeTiles) {
            const SuperResRawTile& tile = active.spec;
            if (tile.x != cursorX || active.file == nullptr) {
                ok = false;
                break;
            }
            const size_t rowBytes = static_cast<size_t>(tile.width) * 3u;
            try {
                tileRow.resize(rowBytes);
            } catch (...) {
                ok = false;
                break;
            }
            if (std::fread(tileRow.data(), 1, rowBytes, active.file) != rowBytes) {
                ok = false;
                break;
            }
            std::copy(
                tileRow.begin(),
                tileRow.end(),
                row.begin() + static_cast<size_t>(cursorX) * 3u
            );
            cursorX += tile.width;
        }
        if (!ok || cursorX != outputWidth) {
            ok = false;
            break;
        }

        ok = writer.write_rgb_rows(row.data(), 1, row.size()) == StreamingPngStatus::Ok;
    }

    if (ok && nextTile != sortedTiles.size()) {
        ok = false;
    }

    close_active_tiles(activeTiles);

    if (ok) {
        ok = writer.finish_temporary() == StreamingPngStatus::Ok &&
            writer.publish() == StreamingPngStatus::Ok;
    }

    if (!ok) {
        close_active_tiles(activeTiles);
        writer.abort();
        return SuperResNativeCode::OutputFailed;
    }
    return SuperResNativeCode::Ok;
}

namespace {

bool write_test_file(const std::string& path, const std::vector<unsigned char>& bytes) {
    FILE* file = std::fopen(path.c_str(), "wb");
    if (file == nullptr) return false;
    const bool written = bytes.empty() ||
        std::fwrite(bytes.data(), 1, bytes.size(), file) == bytes.size();
    return std::fclose(file) == 0 && written;
}

bool test_file_equals(const std::string& path, const std::vector<unsigned char>& expected) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;
    std::vector<unsigned char> actual(expected.size() + 1u, 0);
    const size_t read = std::fread(actual.data(), 1, actual.size(), file);
    std::fclose(file);
    return read == expected.size() &&
        std::equal(expected.begin(), expected.end(), actual.begin());
}

bool has_png_signature(const std::string& path) {
    static const std::vector<unsigned char> signature{137, 80, 78, 71, 13, 10, 26, 10};
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;
    std::vector<unsigned char> actual(signature.size(), 0);
    const size_t read = std::fread(actual.data(), 1, actual.size(), file);
    std::fclose(file);
    return read == signature.size() && actual == signature;
}

bool run_png_streamer_runtime_checks(const std::string& outputPath) {
    const std::string base = outputPath + ".streamer-check";
    const std::string checkOutput = base + ".png";
    const std::string checkPart = checkOutput + ".part";
    const std::string gapLeft = base + ".gap-left.rgb";
    const std::string gapRight = base + ".gap-right.rgb";
    const std::string completeTile = base + ".complete.rgb";
    const std::string ioDestination = base + ".io-dir";
    const std::string ioDestinationPart = ioDestination + ".part";
    const std::string ioSentinel = ioDestination + "/keep";
    const std::vector<std::string> files{
        checkOutput, checkPart, gapLeft, gapRight, completeTile, ioDestinationPart
    };
    for (const std::string& file : files) std::remove(file.c_str());
    std::error_code error;
    std::filesystem::remove_all(ioDestination, error);

    const std::vector<unsigned char> oldFinal{'o', 'l', 'd'};
    const bool setup =
        write_test_file(checkOutput, oldFinal) &&
        write_test_file(gapLeft, {1, 2, 3}) &&
        write_test_file(gapRight, {4, 5, 6}) &&
        write_test_file(completeTile, {1, 2, 3, 4, 5, 6, 7, 8, 9});
    const std::vector<SuperResRawTile> gapTiles{
        SuperResRawTile{gapLeft, 0, 0, 1, 1},
        SuperResRawTile{gapRight, 2, 0, 1, 1}
    };
    const bool failedMergePreservesOldFinal = setup &&
        merge_raw_tiles_to_png_streaming_impl(checkOutput, 3, 1, gapTiles) ==
            SuperResNativeCode::OutputFailed &&
        test_file_equals(checkOutput, oldFinal) &&
        !std::filesystem::exists(checkPart);

    const std::vector<SuperResRawTile> completeTiles{
        SuperResRawTile{completeTile, 0, 0, 3, 1}
    };
    const bool successfulMergePublishesAtomically = failedMergePreservesOldFinal &&
        merge_raw_tiles_to_png_streaming_impl(checkOutput, 3, 1, completeTiles) ==
            SuperResNativeCode::Ok &&
        has_png_signature(checkOutput) &&
        !std::filesystem::exists(checkPart);

    error.clear();
    const bool ioDestinationReady =
        std::filesystem::create_directory(ioDestination, error) &&
        write_test_file(ioSentinel, oldFinal);
    const bool ioFailureCleansPartAndPreservesDestination =
        successfulMergePublishesAtomically && ioDestinationReady &&
        merge_raw_tiles_to_png_streaming_impl(ioDestination, 3, 1, completeTiles) ==
            SuperResNativeCode::OutputFailed &&
        std::filesystem::is_directory(ioDestination, error) &&
        test_file_equals(ioSentinel, oldFinal) &&
        !std::filesystem::exists(ioDestinationPart);

    for (const std::string& file : files) std::remove(file.c_str());
    std::remove(ioSentinel.c_str());
    error.clear();
    std::filesystem::remove(ioDestination, error);
    return ioFailureCleansPartAndPreservesDestination;
}

} // namespace

SuperResNativeCode merge_raw_tiles_to_png_streaming(
    const std::string& outputPath,
    int outputWidth,
    int outputHeight,
    const std::vector<SuperResRawTile>& tiles
) {
#ifndef NDEBUG
    static const bool checksPass = run_png_streamer_runtime_checks(outputPath);
    if (!checksPass) return SuperResNativeCode::OutputFailed;
#endif
    return merge_raw_tiles_to_png_streaming_impl(
        outputPath,
        outputWidth,
        outputHeight,
        tiles
    );
}
