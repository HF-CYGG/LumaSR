// Streaming PNG merger for extreme export mode.
//
// The normal image path allocates a full RGB output buffer. Extreme export instead
// produces cropped raw RGB tiles and streams them into a single PNG row by row.
// This implementation avoids opening every tile at once and avoids O(rows * tiles)
// coverage scans during the final merge.
#include "superres_engine.h"

#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <filesystem>
#include <limits>
#include <string>
#include <vector>

#include "zlib.h"

namespace {

struct ActiveTile {
    SuperResRawTile spec;
    FILE* file = nullptr;
};

bool write_all(FILE* file, const void* data, size_t size) {
    return size == 0 || std::fwrite(data, 1, size, file) == size;
}

void put_be32(unsigned char* output, uint32_t value) {
    output[0] = static_cast<unsigned char>((value >> 24) & 0xff);
    output[1] = static_cast<unsigned char>((value >> 16) & 0xff);
    output[2] = static_cast<unsigned char>((value >> 8) & 0xff);
    output[3] = static_cast<unsigned char>(value & 0xff);
}

bool write_png_chunk(FILE* file, const char type[4], const unsigned char* data, size_t size) {
    if (size > 0xffffffffu) {
        return false;
    }
    unsigned char length[4];
    put_be32(length, static_cast<uint32_t>(size));
    if (!write_all(file, length, sizeof(length)) ||
        !write_all(file, type, 4) ||
        !write_all(file, data, size)) {
        return false;
    }

    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, reinterpret_cast<const Bytef*>(type), 4);
    if (size > 0) {
        crc = crc32(crc, reinterpret_cast<const Bytef*>(data), static_cast<uInt>(size));
    }
    unsigned char crcBytes[4];
    put_be32(crcBytes, static_cast<uint32_t>(crc));
    return write_all(file, crcBytes, sizeof(crcBytes));
}

bool write_png_signature_and_header(FILE* file, int width, int height) {
    static const unsigned char signature[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    unsigned char ihdr[13] = {};
    put_be32(ihdr, static_cast<uint32_t>(width));
    put_be32(ihdr + 4, static_cast<uint32_t>(height));
    ihdr[8] = 8; // bit depth
    ihdr[9] = 2; // truecolor RGB
    return write_all(file, signature, sizeof(signature)) &&
        write_png_chunk(file, "IHDR", ihdr, sizeof(ihdr));
}

bool deflate_png_bytes(
    FILE* file,
    z_stream& stream,
    const unsigned char* data,
    size_t size,
    int flush,
    std::vector<unsigned char>& compressed,
    bool& finished
) {
    stream.next_in = const_cast<Bytef*>(reinterpret_cast<const Bytef*>(data));
    stream.avail_in = static_cast<uInt>(size);
    do {
        stream.next_out = compressed.data();
        stream.avail_out = static_cast<uInt>(compressed.size());
        const int result = deflate(&stream, flush);
        if (result == Z_STREAM_END) {
            finished = true;
        } else if (result != Z_OK) {
            return false;
        }
        const size_t produced = compressed.size() - stream.avail_out;
        if (produced > 0 && !write_png_chunk(file, "IDAT", compressed.data(), produced)) {
            return false;
        }
    } while (stream.avail_out == 0);
    return stream.avail_in == 0;
}

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
    if (outputWidth <= 0) {
        return false;
    }
    const size_t width = static_cast<size_t>(outputWidth);
    if (width > (std::numeric_limits<size_t>::max() - 1u) / 3u) {
        return false;
    }
    bytes = width * 3u + 1u; // one PNG filter byte + RGB row
    return true;
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

std::string temporary_output_path(const std::string& outputPath) {
    return outputPath + ".part";
}

bool replace_file(const std::string& temporaryPath, const std::string& outputPath) {
    std::error_code error;
    std::filesystem::remove(outputPath, error);
    error.clear();
    std::filesystem::rename(temporaryPath, outputPath, error);
    return !error;
}

bool active_tile_x_order(const ActiveTile& left, const ActiveTile& right) {
    return left.spec.x < right.spec.x;
}

} // namespace

SuperResNativeCode merge_raw_tiles_to_png_streaming(
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

    const std::string temporaryPath = temporary_output_path(outputPath);
    std::error_code removeError;
    std::filesystem::remove(temporaryPath, removeError);

    FILE* output = std::fopen(temporaryPath.c_str(), "wb");
    if (output == nullptr) {
        return SuperResNativeCode::OutputFailed;
    }

    z_stream stream{};
    if (deflateInit(&stream, Z_BEST_SPEED) != Z_OK) {
        std::fclose(output);
        std::filesystem::remove(temporaryPath, removeError);
        return SuperResNativeCode::OutputFailed;
    }

    std::vector<ActiveTile> activeTiles;
    activeTiles.reserve(16);
    size_t nextTile = 0;
    bool ok = true;
    bool finished = false;

    std::vector<unsigned char> row;
    std::vector<unsigned char> tileRow;
    std::vector<unsigned char> compressed;
    try {
        row.resize(pngRowBytes, 0);
        compressed.resize(256 * 1024);
    } catch (...) {
        ok = false;
    }

    ok = ok && write_png_signature_and_header(output, outputWidth, outputHeight);

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
        row[0] = 0; // PNG filter type: None
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
                row.begin() + 1u + static_cast<size_t>(cursorX) * 3u
            );
            cursorX += tile.width;
        }
        if (!ok || cursorX != outputWidth) {
            ok = false;
            break;
        }

        ok = deflate_png_bytes(output, stream, row.data(), row.size(), Z_NO_FLUSH, compressed, finished);
    }

    if (ok && nextTile != sortedTiles.size()) {
        ok = false;
    }

    while (ok && !finished) {
        ok = deflate_png_bytes(output, stream, nullptr, 0, Z_FINISH, compressed, finished);
    }

    ok = deflateEnd(&stream) == Z_OK && ok;
    ok = ok && write_png_chunk(output, "IEND", nullptr, 0);
    ok = std::fclose(output) == 0 && ok;
    output = nullptr;
    close_active_tiles(activeTiles);

    if (ok) {
        ok = replace_file(temporaryPath, outputPath);
    }

    if (!ok) {
        if (output != nullptr) {
            std::fclose(output);
        }
        close_active_tiles(activeTiles);
        std::filesystem::remove(temporaryPath, removeError);
        return SuperResNativeCode::OutputFailed;
    }
    return SuperResNativeCode::Ok;
}
