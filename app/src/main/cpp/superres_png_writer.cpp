#include "superres_png_writer.h"
#include "superres_atomic_publish.h"

#include <algorithm>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

#include <unistd.h>
#include <zlib.h>

namespace {

constexpr size_t kCompressedChunkBytes = 256u * 1024u;

bool write_all(FILE* file, const void* data, size_t size) {
    return size == 0 || (file != nullptr && std::fwrite(data, 1, size, file) == size);
}

void put_be32(unsigned char* output, uint32_t value) {
    output[0] = static_cast<unsigned char>((value >> 24) & 0xffu);
    output[1] = static_cast<unsigned char>((value >> 16) & 0xffu);
    output[2] = static_cast<unsigned char>((value >> 8) & 0xffu);
    output[3] = static_cast<unsigned char>(value & 0xffu);
}

bool write_png_chunk(FILE* file, const char type[4], const unsigned char* data, size_t size) {
    if (size > std::numeric_limits<uint32_t>::max()) {
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

bool write_png_header(FILE* file, int width, int height) {
    static const unsigned char signature[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    unsigned char ihdr[13] = {};
    put_be32(ihdr, static_cast<uint32_t>(width));
    put_be32(ihdr + 4, static_cast<uint32_t>(height));
    ihdr[8] = 8;
    ihdr[9] = 2;
    return write_all(file, signature, sizeof(signature)) &&
        write_png_chunk(file, "IHDR", ihdr, sizeof(ihdr));
}

StreamingPngStatus deflate_status(int result) {
    return result == Z_MEM_ERROR ? StreamingPngStatus::OutOfMemory : StreamingPngStatus::IoError;
}

} // namespace

size_t streaming_png_rgb_row_bytes(int width) {
    if (width <= 0) {
        return 0;
    }
    const size_t value = static_cast<size_t>(width);
    if (value > std::numeric_limits<size_t>::max() / 3u) {
        return 0;
    }
    return value * 3u;
}

struct StreamingPngWriter::Impl {
    FILE* file = nullptr;
    z_stream stream{};
    bool deflateInitialized = false;
    std::string outputPath;
    std::string temporaryPath;
    int width = 0;
    int height = 0;
    int writtenRows = 0;
    size_t rowBytes = 0;
    bool temporaryFinished = false;
    std::vector<unsigned char> compressed;

    StreamingPngStatus deflate_bytes(const unsigned char* bytes, size_t size, int flush) {
        size_t offset = 0;
        bool finished = false;
        do {
            const size_t remaining = size - offset;
            const uInt inputBytes = static_cast<uInt>(std::min<size_t>(
                remaining,
                std::numeric_limits<uInt>::max()
            ));
            stream.next_in = inputBytes == 0
                ? Z_NULL
                : const_cast<Bytef*>(reinterpret_cast<const Bytef*>(bytes + offset));
            stream.avail_in = inputBytes;

            do {
                stream.next_out = compressed.data();
                stream.avail_out = static_cast<uInt>(compressed.size());
                const int result = deflate(&stream, flush);
                if (result == Z_STREAM_END) {
                    finished = true;
                } else if (result != Z_OK) {
                    return deflate_status(result);
                }
                const size_t produced = compressed.size() - stream.avail_out;
                if (produced > 0 && !write_png_chunk(file, "IDAT", compressed.data(), produced)) {
                    return StreamingPngStatus::IoError;
                }
            } while (stream.avail_in > 0 || stream.avail_out == 0 || (flush == Z_FINISH && !finished));
            offset += inputBytes;
        } while (offset < size || (flush == Z_FINISH && !finished));
        return flush != Z_FINISH || finished
            ? StreamingPngStatus::Ok
            : StreamingPngStatus::IoError;
    }
};

StreamingPngWriter::StreamingPngWriter() : impl_(std::make_unique<Impl>()) {}

StreamingPngWriter::~StreamingPngWriter() {
    abort();
}

StreamingPngStatus StreamingPngWriter::open(
    const std::string& outputPath,
    int width,
    int height
) {
    abort();
    const size_t rowBytes = streaming_png_rgb_row_bytes(width);
    if (outputPath.empty() || rowBytes == 0 || height <= 0) {
        return StreamingPngStatus::InvalidParams;
    }

    impl_->outputPath = outputPath;
    impl_->temporaryPath = outputPath + ".part";
    impl_->width = width;
    impl_->height = height;
    impl_->rowBytes = rowBytes;
    impl_->writtenRows = 0;
    std::remove(impl_->temporaryPath.c_str());
    impl_->file = std::fopen(impl_->temporaryPath.c_str(), "wb");
    if (impl_->file == nullptr) {
        abort();
        return StreamingPngStatus::IoError;
    }
    try {
        impl_->compressed.resize(kCompressedChunkBytes);
    } catch (...) {
        abort();
        return StreamingPngStatus::OutOfMemory;
    }
    const int deflateResult = deflateInit(&impl_->stream, Z_BEST_SPEED);
    if (deflateResult != Z_OK) {
        abort();
        return deflate_status(deflateResult);
    }
    impl_->deflateInitialized = true;
    if (!write_png_header(impl_->file, width, height)) {
        abort();
        return StreamingPngStatus::IoError;
    }
    return StreamingPngStatus::Ok;
}

StreamingPngStatus StreamingPngWriter::write_rgb_rows(
    const unsigned char* rgb,
    int rowCount,
    size_t rowStride
) {
    if (impl_->file == nullptr || !impl_->deflateInitialized || rgb == nullptr ||
        rowCount <= 0 || rowStride < impl_->rowBytes ||
        rowCount > impl_->height - impl_->writtenRows) {
        return impl_->file == nullptr || !impl_->deflateInitialized
            ? StreamingPngStatus::InvalidState
            : StreamingPngStatus::InvalidParams;
    }
    static const unsigned char filterNone = 0;
    for (int row = 0; row < rowCount; ++row) {
        const StreamingPngStatus filterStatus = impl_->deflate_bytes(&filterNone, 1, Z_NO_FLUSH);
        if (filterStatus != StreamingPngStatus::Ok) {
            return filterStatus;
        }
        const StreamingPngStatus rowStatus = impl_->deflate_bytes(
            rgb + static_cast<size_t>(row) * rowStride,
            impl_->rowBytes,
            Z_NO_FLUSH
        );
        if (rowStatus != StreamingPngStatus::Ok) {
            return rowStatus;
        }
    }
    impl_->writtenRows += rowCount;
    return StreamingPngStatus::Ok;
}

StreamingPngStatus StreamingPngWriter::finish_temporary() {
    if (impl_->file == nullptr || !impl_->deflateInitialized ||
        impl_->writtenRows != impl_->height) {
        abort();
        return StreamingPngStatus::InvalidState;
    }
    StreamingPngStatus status = impl_->deflate_bytes(nullptr, 0, Z_FINISH);
    const int deflateEndResult = deflateEnd(&impl_->stream);
    if (status == StreamingPngStatus::Ok && deflateEndResult != Z_OK) {
        status = deflate_status(deflateEndResult);
    }
    impl_->deflateInitialized = false;
    if (status == StreamingPngStatus::Ok && !write_png_chunk(impl_->file, "IEND", nullptr, 0)) {
        status = StreamingPngStatus::IoError;
    }
    if (status == StreamingPngStatus::Ok && std::fflush(impl_->file) != 0) {
        status = StreamingPngStatus::IoError;
    }
    if (status == StreamingPngStatus::Ok && ::fsync(::fileno(impl_->file)) != 0) {
        status = StreamingPngStatus::IoError;
    }
    if (std::fclose(impl_->file) != 0 && status == StreamingPngStatus::Ok) {
        status = StreamingPngStatus::IoError;
    }
    impl_->file = nullptr;
    if (status != StreamingPngStatus::Ok) {
        std::remove(impl_->temporaryPath.c_str());
        abort();
        return status;
    }
    impl_->temporaryFinished = true;
    return StreamingPngStatus::Ok;
}

StreamingPngStatus StreamingPngWriter::publish() {
    if (!impl_->temporaryFinished || impl_->file != nullptr || impl_->deflateInitialized ||
        impl_->temporaryPath.empty() || impl_->outputPath.empty()) {
        return StreamingPngStatus::InvalidState;
    }
    const StreamingPngStatus status = publish_atomic_file(
        impl_->temporaryPath,
        impl_->outputPath
    ) == SuperResAtomicPublishStatus::Ok
        ? StreamingPngStatus::Ok
        : StreamingPngStatus::IoError;
    if (status != StreamingPngStatus::Ok) {
        abort();
        return status;
    }
    impl_->compressed.clear();
    impl_->outputPath.clear();
    impl_->temporaryPath.clear();
    impl_->width = 0;
    impl_->height = 0;
    impl_->writtenRows = 0;
    impl_->rowBytes = 0;
    impl_->temporaryFinished = false;
    impl_->stream = z_stream{};
    return StreamingPngStatus::Ok;
}

void StreamingPngWriter::abort() {
    if (!impl_) {
        return;
    }
    if (impl_->deflateInitialized) {
        deflateEnd(&impl_->stream);
        impl_->deflateInitialized = false;
    }
    if (impl_->file != nullptr) {
        std::fclose(impl_->file);
        impl_->file = nullptr;
    }
    if (!impl_->temporaryPath.empty()) {
        std::remove(impl_->temporaryPath.c_str());
    }
    impl_->compressed.clear();
    impl_->outputPath.clear();
    impl_->temporaryPath.clear();
    impl_->width = 0;
    impl_->height = 0;
    impl_->writtenRows = 0;
    impl_->rowBytes = 0;
    impl_->temporaryFinished = false;
    impl_->stream = z_stream{};
}
