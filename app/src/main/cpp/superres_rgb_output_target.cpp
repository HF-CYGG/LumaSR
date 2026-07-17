#include "superres_rgb_output_target.h"

#include <algorithm>
#include <limits>
#include <new>
#include <utility>

namespace {

bool checked_rgb_row_bytes(int width, size_t& rowBytes) {
    if (width <= 0 ||
        static_cast<size_t>(width) > std::numeric_limits<size_t>::max() / 3u) {
        rowBytes = 0;
        return false;
    }
    rowBytes = static_cast<size_t>(width) * 3u;
    return true;
}

} // namespace

MemoryRgbOutput::MemoryRgbOutput(SuperResRgbRelease release) noexcept
    : release_(release == nullptr ? delete_superres_rgb_array : release),
      pixels_(nullptr, SuperResRgbDeleter{release_}) {}

SuperResNativeCode MemoryRgbOutput::initialize(
    const std::string&,
    int width,
    int height,
    int
) {
    pixels_.reset();
    width_ = 0;
    height_ = 0;
    rowBytes_ = 0;
    byteSize_ = 0;
    encodingDuration_ = {};
    if (!checked_rgb_row_bytes(width, rowBytes_) || height <= 0) {
        lastFailure_ = SuperResNativeCode::InvalidParams;
        return lastFailure_;
    }
    if (static_cast<size_t>(height) >
        std::numeric_limits<size_t>::max() / rowBytes_) {
        lastFailure_ = SuperResNativeCode::OutOfMemory;
        return lastFailure_;
    }
    byteSize_ = rowBytes_ * static_cast<size_t>(height);
    pixels_ = SuperResRgbPixels(
        new (std::nothrow) unsigned char[byteSize_],
        SuperResRgbDeleter{release_}
    );
    if (pixels_ == nullptr) {
        byteSize_ = 0;
        lastFailure_ = SuperResNativeCode::OutOfMemory;
        return lastFailure_;
    }
    std::fill_n(pixels_.get(), byteSize_, static_cast<unsigned char>(0));
    width_ = width;
    height_ = height;
    lastFailure_ = SuperResNativeCode::Ok;
    return lastFailure_;
}

bool MemoryRgbOutput::prepare_window(int destinationY, int windowHeight) {
    return destinationY >= 0 && windowHeight > 0 &&
        destinationY <= height_ - windowHeight;
}

unsigned char* MemoryRgbOutput::row(int destinationY, int destinationX) {
    if (destinationY < 0 || destinationY >= height_ ||
        destinationX < 0 || destinationX >= width_) {
        return nullptr;
    }
    return pixels_.get() + static_cast<size_t>(destinationY) * rowBytes_ +
        static_cast<size_t>(destinationX) * 3u;
}

SuperResNativeCode MemoryRgbOutput::finish_attempt(SuperResStagedOutput& stagedOutput) {
    if (pixels_ == nullptr || byteSize_ == 0) {
        lastFailure_ = SuperResNativeCode::InvalidParams;
        return lastFailure_;
    }
    const SuperResStageResult result = stagedOutput.stage_bytes(pixels_.get(), byteSize_);
    encodingDuration_ = std::chrono::milliseconds(result.stagingMs);
    lastFailure_ = result.status == SuperResStageStatus::Ok
        ? SuperResNativeCode::Ok
        : SuperResNativeCode::OutputFailed;
    return lastFailure_;
}

SuperResNativeCode MemoryRgbOutput::failure_code() const {
    return lastFailure_;
}

std::chrono::steady_clock::duration MemoryRgbOutput::encoding_duration() const {
    return encodingDuration_;
}

SuperResRgbImage MemoryRgbOutput::take_image() noexcept {
    if (width_ <= 0 || height_ <= 0 || rowBytes_ == 0 || byteSize_ == 0 ||
        pixels_ == nullptr) {
        return {};
    }
    const int width = width_;
    const int height = height_;
    const size_t rowBytes = rowBytes_;
    const size_t byteSize = byteSize_;
    width_ = 0;
    height_ = 0;
    rowBytes_ = 0;
    byteSize_ = 0;
    return SuperResRgbImage(
        width,
        height,
        rowBytes,
        byteSize,
        std::move(pixels_)
    );
}

StreamingPngOutput::StreamingPngOutput(StreamingPngOutputOps operations)
    : operations_(std::move(operations)) {}

StreamingPngOutput::~StreamingPngOutput() {
    if (operations_.abort) {
        operations_.abort();
    }
}

SuperResNativeCode StreamingPngOutput::initialize(
    const std::string& outputPath,
    int width,
    int height,
    int maximumBandHeight
) {
    if (outputPath.empty() || !checked_rgb_row_bytes(width, rowBytes_) || height <= 0 ||
        !operations_.open || !operations_.writeRows || !operations_.finishTemporary ||
        !operations_.abort) {
        lastFailure_ = SuperResNativeCode::InvalidParams;
        return lastFailure_;
    }
    const int allocatedRows = std::min(height, maximumBandHeight);
    if (allocatedRows <= 0 || static_cast<size_t>(allocatedRows) >
        std::numeric_limits<size_t>::max() / rowBytes_) {
        lastFailure_ = SuperResNativeCode::OutOfMemory;
        return lastFailure_;
    }
    try {
        bytes_.resize(rowBytes_ * static_cast<size_t>(allocatedRows));
    } catch (...) {
        lastFailure_ = SuperResNativeCode::OutOfMemory;
        return lastFailure_;
    }
    width_ = width;
    height_ = height;
    const auto start = std::chrono::steady_clock::now();
    const StreamingPngStatus status = operations_.open(outputPath, width, height);
    encodingDuration_ += std::chrono::steady_clock::now() - start;
    lastFailure_ = native_code(status);
    return lastFailure_;
}

bool StreamingPngOutput::prepare_window(int destinationY, int windowHeight) {
    if (destinationY == bandStartY_) {
        return windowHeight == bandHeight_;
    }
    if (bandStartY_ >= 0 && !flush_band()) {
        return false;
    }
    if (destinationY != writtenRows_ || windowHeight <= 0 ||
        destinationY > height_ - windowHeight ||
        static_cast<size_t>(windowHeight) > bytes_.size() / rowBytes_) {
        return false;
    }
    bandStartY_ = destinationY;
    bandHeight_ = windowHeight;
    std::fill(
        bytes_.begin(),
        bytes_.begin() + rowBytes_ * static_cast<size_t>(windowHeight),
        static_cast<unsigned char>(0)
    );
    return true;
}

unsigned char* StreamingPngOutput::row(int destinationY, int destinationX) {
    if (destinationX < 0 || destinationX >= width_) {
        return nullptr;
    }
    const int localY = destinationY - bandStartY_;
    if (localY < 0 || localY >= bandHeight_) {
        return nullptr;
    }
    return bytes_.data() + static_cast<size_t>(localY) * rowBytes_ +
        static_cast<size_t>(destinationX) * 3u;
}

SuperResNativeCode StreamingPngOutput::finish_attempt(SuperResStagedOutput& stagedOutput) {
    if (!flush_band() || writtenRows_ != height_) {
        return lastFailure_ == SuperResNativeCode::Ok
            ? SuperResNativeCode::TileOutputMismatch
            : lastFailure_;
    }
    const auto start = std::chrono::steady_clock::now();
    const StreamingPngStatus status = operations_.finishTemporary();
    encodingDuration_ += std::chrono::steady_clock::now() - start;
    lastFailure_ = native_code(status);
    if (lastFailure_ == SuperResNativeCode::Ok &&
        !stagedOutput.adopt_finished_temporary()) {
        lastFailure_ = SuperResNativeCode::OutputFailed;
    }
    return lastFailure_;
}

SuperResNativeCode StreamingPngOutput::failure_code() const {
    return lastFailure_;
}

std::chrono::steady_clock::duration StreamingPngOutput::encoding_duration() const {
    return encodingDuration_;
}

bool StreamingPngOutput::flush_band() {
    if (bandStartY_ < 0) {
        return true;
    }
    const auto start = std::chrono::steady_clock::now();
    const StreamingPngStatus status = operations_.writeRows(
        bytes_.data(),
        bandHeight_,
        rowBytes_
    );
    encodingDuration_ += std::chrono::steady_clock::now() - start;
    if (status != StreamingPngStatus::Ok) {
        lastFailure_ = native_code(status);
        return false;
    }
    writtenRows_ += bandHeight_;
    bandStartY_ = -1;
    bandHeight_ = 0;
    return true;
}

SuperResNativeCode StreamingPngOutput::native_code(StreamingPngStatus status) {
    switch (status) {
        case StreamingPngStatus::Ok:
            return SuperResNativeCode::Ok;
        case StreamingPngStatus::OutOfMemory:
            return SuperResNativeCode::OutOfMemory;
        case StreamingPngStatus::InvalidParams:
        case StreamingPngStatus::InvalidState:
            return SuperResNativeCode::InvalidParams;
        case StreamingPngStatus::IoError:
            return SuperResNativeCode::OutputFailed;
    }
    return SuperResNativeCode::OutputFailed;
}
