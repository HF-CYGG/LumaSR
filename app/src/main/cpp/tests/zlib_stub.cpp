#include "zlib.h"

#include <algorithm>
#include <new>
#include <vector>

namespace {

struct StoredDeflateState {
    std::vector<unsigned char> input;
    std::vector<unsigned char> encoded;
    size_t cursor = 0;
    bool encodedReady = false;
};

uint32_t adler32_bytes(const std::vector<unsigned char>& bytes) {
    constexpr uint32_t modulus = 65521u;
    uint32_t first = 1u;
    uint32_t second = 0u;
    for (unsigned char value : bytes) {
        first = (first + value) % modulus;
        second = (second + first) % modulus;
    }
    return (second << 16u) | first;
}

void append_stored_zlib_stream(StoredDeflateState& state) {
    state.encoded.clear();
    state.encoded.push_back(0x78u);
    state.encoded.push_back(0x01u);

    size_t offset = 0;
    do {
        const size_t remaining = state.input.size() - offset;
        const uint16_t blockSize = static_cast<uint16_t>(
            std::min<size_t>(remaining, 65535u)
        );
        const bool finalBlock = offset + blockSize == state.input.size();
        state.encoded.push_back(finalBlock ? 0x01u : 0x00u);
        state.encoded.push_back(static_cast<unsigned char>(blockSize & 0xffu));
        state.encoded.push_back(static_cast<unsigned char>((blockSize >> 8u) & 0xffu));
        const uint16_t inverse = static_cast<uint16_t>(~blockSize);
        state.encoded.push_back(static_cast<unsigned char>(inverse & 0xffu));
        state.encoded.push_back(static_cast<unsigned char>((inverse >> 8u) & 0xffu));
        state.encoded.insert(
            state.encoded.end(),
            state.input.begin() + static_cast<std::ptrdiff_t>(offset),
            state.input.begin() + static_cast<std::ptrdiff_t>(offset + blockSize)
        );
        offset += blockSize;
    } while (offset < state.input.size());

    const uint32_t checksum = adler32_bytes(state.input);
    state.encoded.push_back(static_cast<unsigned char>((checksum >> 24u) & 0xffu));
    state.encoded.push_back(static_cast<unsigned char>((checksum >> 16u) & 0xffu));
    state.encoded.push_back(static_cast<unsigned char>((checksum >> 8u) & 0xffu));
    state.encoded.push_back(static_cast<unsigned char>(checksum & 0xffu));
    state.encodedReady = true;
}

bool append_input(z_streamp stream, StoredDeflateState& state) {
    if (stream->avail_in == 0) {
        return true;
    }
    if (stream->next_in == nullptr) {
        return false;
    }
    try {
        state.input.insert(
            state.input.end(),
            stream->next_in,
            stream->next_in + stream->avail_in
        );
    } catch (...) {
        return false;
    }
    stream->next_in += stream->avail_in;
    stream->avail_in = 0;
    return true;
}

} // namespace

int deflateInit2(z_streamp stream, int, int method, int, int, int) {
    if (stream == nullptr || method != Z_DEFLATED) {
        return Z_STREAM_ERROR;
    }
    auto* state = new (std::nothrow) StoredDeflateState();
    if (state == nullptr) {
        return Z_MEM_ERROR;
    }
    stream->state = state;
    return Z_OK;
}

int deflateInit(z_streamp stream, int level) {
    return deflateInit2(
        stream,
        level,
        Z_DEFLATED,
        MAX_WBITS,
        MAX_MEM_LEVEL,
        Z_DEFAULT_STRATEGY
    );
}

int deflate(z_streamp stream, int flush) {
    if (stream == nullptr || stream->state == nullptr ||
        stream->next_out == nullptr || stream->avail_out == 0) {
        return Z_STREAM_ERROR;
    }
    auto& state = *static_cast<StoredDeflateState*>(stream->state);
    if (!append_input(stream, state)) {
        return Z_MEM_ERROR;
    }
    if (flush == Z_NO_FLUSH) {
        return Z_OK;
    }
    if (flush != Z_FINISH) {
        return Z_STREAM_ERROR;
    }
    if (!state.encodedReady) {
        try {
            append_stored_zlib_stream(state);
        } catch (...) {
            return Z_MEM_ERROR;
        }
    }
    const size_t remaining = state.encoded.size() - state.cursor;
    const size_t copied = std::min<size_t>(remaining, stream->avail_out);
    std::copy_n(
        state.encoded.data() + state.cursor,
        copied,
        stream->next_out
    );
    state.cursor += copied;
    stream->next_out += copied;
    stream->avail_out -= static_cast<uInt>(copied);
    return state.cursor == state.encoded.size() ? Z_STREAM_END : Z_OK;
}

int deflateEnd(z_streamp stream) {
    if (stream == nullptr || stream->state == nullptr) {
        return Z_STREAM_ERROR;
    }
    delete static_cast<StoredDeflateState*>(stream->state);
    stream->state = nullptr;
    return Z_OK;
}

uLong crc32(uLong crc, const Bytef* buffer, uInt length) {
    uint32_t value = static_cast<uint32_t>(crc) ^ 0xffffffffu;
    for (uInt index = 0; index < length; ++index) {
        value ^= buffer[index];
        for (int bit = 0; bit < 8; ++bit) {
            value = (value >> 1u) ^ (0xedb88320u & (0u - (value & 1u)));
        }
    }
    return static_cast<uLong>(value ^ 0xffffffffu);
}
