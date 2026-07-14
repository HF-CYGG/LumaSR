#pragma once

#include <cstddef>
#include <memory>
#include <string>

size_t streaming_png_rgb_row_bytes(int width);

enum class StreamingPngStatus {
    Ok,
    InvalidParams,
    InvalidState,
    OutOfMemory,
    IoError
};

class StreamingPngWriter {
public:
    StreamingPngWriter();
    ~StreamingPngWriter();

    StreamingPngWriter(const StreamingPngWriter&) = delete;
    StreamingPngWriter& operator=(const StreamingPngWriter&) = delete;

    StreamingPngStatus open(const std::string& outputPath, int width, int height);
    StreamingPngStatus write_rgb_rows(const unsigned char* rgb, int rowCount, size_t rowStride);
    StreamingPngStatus finish();
    void abort();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
