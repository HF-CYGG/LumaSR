#pragma once

#include "superres_engine.h"
#include "superres_output_finalizer.h"
#include "superres_png_writer.h"

#include <chrono>
#include <cstddef>
#include <functional>
#include <memory>
#include <string>
#include <vector>

class RgbOutputTarget {
public:
    virtual ~RgbOutputTarget() = default;

    virtual SuperResNativeCode initialize(
        const std::string& outputPath,
        int width,
        int height,
        int maximumBandHeight
    ) = 0;
    virtual bool prepare_window(int destinationY, int windowHeight) = 0;
    virtual unsigned char* row(int destinationY, int destinationX) = 0;
    virtual SuperResNativeCode finish_attempt(SuperResStagedOutput& stagedOutput) = 0;
    virtual SuperResNativeCode failure_code() const = 0;
    virtual std::chrono::steady_clock::duration encoding_duration() const = 0;
};

class MemoryRgbOutput final : public RgbOutputTarget {
public:
    explicit MemoryRgbOutput(
        SuperResRgbRelease release = delete_superres_rgb_array
    ) noexcept;

    SuperResNativeCode initialize(
        const std::string& outputPath,
        int width,
        int height,
        int maximumBandHeight
    ) override;
    bool prepare_window(int destinationY, int windowHeight) override;
    unsigned char* row(int destinationY, int destinationX) override;
    SuperResNativeCode finish_attempt(SuperResStagedOutput& stagedOutput) override;
    SuperResNativeCode failure_code() const override;
    std::chrono::steady_clock::duration encoding_duration() const override;
    SuperResRgbImage take_image() noexcept;

private:
    SuperResRgbRelease release_ = delete_superres_rgb_array;
    int width_ = 0;
    int height_ = 0;
    size_t rowBytes_ = 0;
    size_t byteSize_ = 0;
    SuperResRgbPixels pixels_;
    std::chrono::steady_clock::duration encodingDuration_{};
    SuperResNativeCode lastFailure_ = SuperResNativeCode::Ok;
};

struct StreamingPngOutputOps {
    std::function<StreamingPngStatus(const std::string&, int, int)> open;
    std::function<StreamingPngStatus(const unsigned char*, int, size_t)> writeRows;
    std::function<StreamingPngStatus()> finishTemporary;
    std::function<void()> abort;
};

class StreamingPngOutput final : public RgbOutputTarget {
public:
    explicit StreamingPngOutput(StreamingPngOutputOps operations);
    ~StreamingPngOutput() override;

    StreamingPngOutput(const StreamingPngOutput&) = delete;
    StreamingPngOutput& operator=(const StreamingPngOutput&) = delete;

    SuperResNativeCode initialize(
        const std::string& outputPath,
        int width,
        int height,
        int maximumBandHeight
    ) override;
    bool prepare_window(int destinationY, int windowHeight) override;
    unsigned char* row(int destinationY, int destinationX) override;
    SuperResNativeCode finish_attempt(SuperResStagedOutput& stagedOutput) override;
    SuperResNativeCode failure_code() const override;
    std::chrono::steady_clock::duration encoding_duration() const override;

private:
    bool flush_band();
    static SuperResNativeCode native_code(StreamingPngStatus status);

    StreamingPngOutputOps operations_;
    int width_ = 0;
    int height_ = 0;
    int writtenRows_ = 0;
    int bandStartY_ = -1;
    int bandHeight_ = 0;
    size_t rowBytes_ = 0;
    std::vector<unsigned char> bytes_;
    std::chrono::steady_clock::duration encodingDuration_{};
    SuperResNativeCode lastFailure_ = SuperResNativeCode::Ok;
};

std::unique_ptr<RgbOutputTarget> make_streaming_png_output();
