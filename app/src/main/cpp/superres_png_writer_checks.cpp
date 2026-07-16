#include "superres_png_writer_checks.h"

#include "superres_png_writer.h"

#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#include "stb_image.h"

namespace {

uint32_t read_be32(const unsigned char* bytes) {
    return (static_cast<uint32_t>(bytes[0]) << 24) |
        (static_cast<uint32_t>(bytes[1]) << 16) |
        (static_cast<uint32_t>(bytes[2]) << 8) |
        static_cast<uint32_t>(bytes[3]);
}

int count_idat_chunks(const std::string& path) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return 0;
    unsigned char signature[8];
    if (std::fread(signature, 1, sizeof(signature), file) != sizeof(signature)) {
        std::fclose(file);
        return 0;
    }
    int count = 0;
    while (true) {
        unsigned char header[8];
        if (std::fread(header, 1, sizeof(header), file) != sizeof(header)) break;
        const uint32_t size = read_be32(header);
        if (header[4] == 'I' && header[5] == 'D' && header[6] == 'A' && header[7] == 'T') {
            ++count;
        }
        if (std::fseek(file, static_cast<long>(size) + 4L, SEEK_CUR) != 0) break;
        if (header[4] == 'I' && header[5] == 'E' && header[6] == 'N' && header[7] == 'D') break;
    }
    std::fclose(file);
    return count;
}

bool write_sentinel(const std::string& path) {
    static const unsigned char sentinel[] = {'o', 'l', 'd'};
    FILE* file = std::fopen(path.c_str(), "wb");
    if (file == nullptr) return false;
    const bool ok = std::fwrite(sentinel, 1, sizeof(sentinel), file) == sizeof(sentinel);
    return std::fclose(file) == 0 && ok;
}

bool sentinel_is_intact(const std::string& path) {
    unsigned char bytes[4] = {};
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;
    const size_t read = std::fread(bytes, 1, sizeof(bytes), file);
    std::fclose(file);
    return read == 3 && bytes[0] == 'o' && bytes[1] == 'l' && bytes[2] == 'd';
}

bool file_exists(const std::string& path) {
    FILE* file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) return false;
    std::fclose(file);
    return true;
}

void remove_file(const std::string& path) {
    std::remove(path.c_str());
}

} // namespace

bool run_streaming_png_writer_runtime_checks(const char* outputPath) {
    if (streaming_png_rgb_row_bytes(1) != 3u ||
        streaming_png_rgb_row_bytes(4096) != 12288u ||
        streaming_png_rgb_row_bytes(0) != 0u ||
        streaming_png_rgb_row_bytes(-1) != 0u ||
        outputPath == nullptr || outputPath[0] == '\0') {
        return false;
    }

    constexpr int width = 257;
    constexpr int height = 400;
    const size_t rowBytes = streaming_png_rgb_row_bytes(width);
    const size_t stride = rowBytes + 5u;
    std::vector<unsigned char> source;
    try {
        source.resize(stride * height, 0xa5u);
    } catch (...) {
        return false;
    }
    uint32_t random = 0x4c554d41u;
    for (int y = 0; y < height; ++y) {
        for (size_t x = 0; x < rowBytes; ++x) {
            random = random * 1664525u + 1013904223u;
            source[static_cast<size_t>(y) * stride + x] = static_cast<unsigned char>(random >> 24);
        }
    }

    const std::string checkPath = std::string(outputPath) + ".writer-check.png";
    const std::string partPath = checkPath + ".part";
    remove_file(checkPath);
    remove_file(partPath);
    StreamingPngWriter writer;
    if (writer.open(checkPath, width, height) != StreamingPngStatus::Ok ||
        writer.write_rgb_rows(source.data(), 137, stride) != StreamingPngStatus::Ok ||
        writer.write_rgb_rows(source.data() + 137u * stride, height - 137, stride) != StreamingPngStatus::Ok ||
        writer.finish_temporary() != StreamingPngStatus::Ok ||
        !file_exists(partPath) ||
        file_exists(checkPath) ||
        writer.publish() != StreamingPngStatus::Ok ||
        file_exists(partPath) ||
        count_idat_chunks(checkPath) < 2) {
        writer.abort();
        remove_file(checkPath);
        remove_file(partPath);
        return false;
    }

    int decodedWidth = 0;
    int decodedHeight = 0;
    int decodedChannels = 0;
    unsigned char* decoded = stbi_load(
        checkPath.c_str(),
        &decodedWidth,
        &decodedHeight,
        &decodedChannels,
        3
    );
    bool pixelsMatch = decoded != nullptr && decodedWidth == width && decodedHeight == height;
    for (int y = 0; pixelsMatch && y < height; ++y) {
        const unsigned char* expected = source.data() + static_cast<size_t>(y) * stride;
        const unsigned char* actual = decoded + static_cast<size_t>(y) * rowBytes;
        pixelsMatch = std::equal(expected, expected + rowBytes, actual);
    }
    stbi_image_free(decoded);
    remove_file(checkPath);
    if (!pixelsMatch || !write_sentinel(checkPath)) {
        remove_file(checkPath);
        return false;
    }

    std::vector<unsigned char> incomplete(rowBytes, 0x7fu);
    const bool incompleteRejected =
        writer.open(checkPath, width, height) == StreamingPngStatus::Ok &&
        writer.write_rgb_rows(incomplete.data(), 1, rowBytes) == StreamingPngStatus::Ok &&
        writer.finish_temporary() == StreamingPngStatus::InvalidState;
    const bool cleanupPassed = incompleteRejected &&
        sentinel_is_intact(checkPath) &&
        !file_exists(partPath);
    if (!cleanupPassed) {
        remove_file(checkPath);
        remove_file(partPath);
        return false;
    }

    std::vector<unsigned char> complete(rowBytes * height, 0x55u);
    const bool cancellationBeforePublishPreservesOldOutput =
        writer.open(checkPath, width, height) == StreamingPngStatus::Ok &&
        writer.write_rgb_rows(complete.data(), height, rowBytes) == StreamingPngStatus::Ok &&
        writer.finish_temporary() == StreamingPngStatus::Ok &&
        file_exists(partPath) &&
        sentinel_is_intact(checkPath);
    writer.abort();
    const bool cancellationCleanupPassed = cancellationBeforePublishPreservesOldOutput &&
        sentinel_is_intact(checkPath) &&
        !file_exists(partPath) &&
        writer.publish() == StreamingPngStatus::InvalidState;
    if (!cancellationCleanupPassed) {
        remove_file(checkPath);
        remove_file(partPath);
        return false;
    }

    const std::string ioFailurePath = checkPath + ".io-failure-dir";
    const std::string ioFailurePartPath = ioFailurePath + ".part";
    const std::string ioFailureSentinel = ioFailurePath + "/keep";
    std::error_code filesystemError;
    std::filesystem::remove_all(ioFailurePath, filesystemError);
    filesystemError.clear();
    const bool ioDirectoryReady = std::filesystem::create_directory(ioFailurePath, filesystemError) &&
        write_sentinel(ioFailureSentinel);
    const bool ioFailureCleansPartAndPreservesDestination = ioDirectoryReady &&
        writer.open(ioFailurePath, width, height) == StreamingPngStatus::Ok &&
        writer.write_rgb_rows(complete.data(), height, rowBytes) == StreamingPngStatus::Ok &&
        writer.finish_temporary() == StreamingPngStatus::Ok &&
        writer.publish() == StreamingPngStatus::IoError &&
        !file_exists(ioFailurePartPath) &&
        std::filesystem::is_directory(ioFailurePath, filesystemError) &&
        sentinel_is_intact(ioFailureSentinel);
    remove_file(checkPath);
    remove_file(partPath);
    remove_file(ioFailureSentinel);
    std::filesystem::remove(ioFailurePath, filesystemError);
    remove_file(ioFailurePartPath);
    return ioFailureCleansPartAndPreservesDestination;
}
