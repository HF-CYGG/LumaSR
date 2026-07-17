#pragma once

#include <cstddef>
#include <cstdint>

using Bytef = unsigned char;
using uInt = unsigned int;
using uLong = unsigned long;

struct z_stream_s {
    Bytef* next_in = nullptr;
    uInt avail_in = 0;
    Bytef* next_out = nullptr;
    uInt avail_out = 0;
    void* state = nullptr;
};

using z_stream = z_stream_s;
using z_streamp = z_stream*;

#define Z_NULL 0

constexpr int Z_NO_FLUSH = 0;
constexpr int Z_FINISH = 4;
constexpr int Z_OK = 0;
constexpr int Z_STREAM_END = 1;
constexpr int Z_STREAM_ERROR = -2;
constexpr int Z_MEM_ERROR = -4;
constexpr int Z_BUF_ERROR = -5;
constexpr int Z_DEFAULT_COMPRESSION = -1;
constexpr int Z_BEST_SPEED = 1;
constexpr int Z_DEFLATED = 8;
constexpr int MAX_WBITS = 15;
constexpr int MAX_MEM_LEVEL = 9;
constexpr int Z_DEFAULT_STRATEGY = 0;

int deflateInit2(
    z_streamp stream,
    int level,
    int method,
    int windowBits,
    int memLevel,
    int strategy
);
int deflateInit(z_streamp stream, int level);
int deflate(z_streamp stream, int flush);
int deflateEnd(z_streamp stream);
uLong crc32(uLong crc, const Bytef* buffer, uInt length);
