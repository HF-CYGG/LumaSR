#include "superres_extreme_session.h"

#include <chrono>
#include <limits>
#include <new>
#include <utility>

namespace {

bool checked_add_int(int left, int right, int& result) {
    result = 0;
    if (left < 0 || right < 0 || left > std::numeric_limits<int>::max() - right) {
        return false;
    }
    result = left + right;
    return true;
}

bool dependencies_are_valid(const SuperResExtremeSessionDependencies& dependencies) {
    return dependencies.makeOutput && dependencies.registerTask &&
        dependencies.completeTask && dependencies.consumeCancelled &&
        dependencies.publishOutput;
}

bool config_is_valid(const SuperResExtremeSessionConfig& config) {
    if (config.taskId.empty() || config.outputPath.empty() || config.outputWidth <= 0 ||
        config.outputHeight <= 0 || config.maximumBandHeight <= 0 || config.tiles.empty()) {
        return false;
    }

    int nextX = 0;
    int nextBandY = 0;
    int bandY = -1;
    int bandHeight = 0;
    for (size_t position = 0; position < config.tiles.size(); ++position) {
        const SuperResExtremeTileConfig& tile = config.tiles[position];
        if (position > static_cast<size_t>(std::numeric_limits<int>::max()) ||
            tile.index != static_cast<int>(position) || tile.outputX < 0 || tile.outputY < 0 ||
            tile.outputWidth <= 0 || tile.outputHeight <= 0 ||
            tile.outputHeight > config.maximumBandHeight ||
            tile.outputX > config.outputWidth - tile.outputWidth ||
            tile.outputY > config.outputHeight - tile.outputHeight) {
            return false;
        }

        if (nextX == 0) {
            if (tile.outputX != 0 || tile.outputY != nextBandY) {
                return false;
            }
            bandY = tile.outputY;
            bandHeight = tile.outputHeight;
        } else if (tile.outputX != nextX || tile.outputY != bandY ||
                   tile.outputHeight != bandHeight) {
            return false;
        }

        if (!checked_add_int(nextX, tile.outputWidth, nextX) ||
            nextX > config.outputWidth) {
            return false;
        }
        if (nextX == config.outputWidth) {
            if (!checked_add_int(nextBandY, bandHeight, nextBandY) ||
                nextBandY > config.outputHeight) {
                return false;
            }
            nextX = 0;
        }
    }
    return nextX == 0 && nextBandY == config.outputHeight;
}

class ExtremeBandOutput final : public RgbOutputTarget {
public:
    ExtremeBandOutput(
        RgbOutputTarget& shared,
        const SuperResExtremeTileConfig& tile
    ) noexcept : shared_(shared), tile_(tile) {}

    SuperResNativeCode initialize(
        const std::string&,
        int width,
        int height,
        int
    ) override {
        initialized_ = width == tile_.outputWidth && height == tile_.outputHeight;
        lastFailure_ = initialized_
            ? SuperResNativeCode::Ok
            : SuperResNativeCode::TileOutputMismatch;
        return lastFailure_;
    }

    bool prepare_window(int destinationY, int windowHeight) override {
        if (!initialized_ || destinationY < 0 || windowHeight <= 0 ||
            destinationY > tile_.outputHeight - windowHeight) {
            lastFailure_ = SuperResNativeCode::TileOutputMismatch;
            return false;
        }
        return true;
    }

    unsigned char* row(int destinationY, int destinationX) override {
        if (!initialized_ || destinationY < 0 || destinationY >= tile_.outputHeight ||
            destinationX < 0 || destinationX >= tile_.outputWidth) {
            lastFailure_ = SuperResNativeCode::TileOutputMismatch;
            return nullptr;
        }
        int globalY = 0;
        int globalX = 0;
        if (!checked_add_int(tile_.outputY, destinationY, globalY) ||
            !checked_add_int(tile_.outputX, destinationX, globalX)) {
            lastFailure_ = SuperResNativeCode::TileOutputMismatch;
            return nullptr;
        }
        unsigned char* result = shared_.row(globalY, globalX);
        if (result == nullptr) {
            lastFailure_ = shared_.failure_code() == SuperResNativeCode::Ok
                ? SuperResNativeCode::TileOutputMismatch
                : shared_.failure_code();
        }
        return result;
    }

    SuperResNativeCode finish_attempt(SuperResStagedOutput&) override {
        lastFailure_ = SuperResNativeCode::InvalidParams;
        return lastFailure_;
    }

    SuperResNativeCode failure_code() const override {
        return lastFailure_ == SuperResNativeCode::Ok
            ? shared_.failure_code()
            : lastFailure_;
    }

    std::chrono::steady_clock::duration encoding_duration() const override {
        return shared_.encoding_duration();
    }

    bool initialized() const noexcept {
        return initialized_;
    }

private:
    RgbOutputTarget& shared_;
    const SuperResExtremeTileConfig& tile_;
    bool initialized_ = false;
    SuperResNativeCode lastFailure_ = SuperResNativeCode::Ok;
};

} // namespace

bool checked_extreme_tile_config_array_length(
    size_t tileCount,
    size_t stride,
    size_t& flatLength
) {
    flatLength = 0;
    if (tileCount == 0 || stride == 0 ||
        tileCount > std::numeric_limits<size_t>::max() / stride) {
        return false;
    }
    flatLength = tileCount * stride;
    return true;
}

SuperResExtremePngSession::SuperResExtremePngSession(
    SuperResExtremeSessionConfig config,
    SuperResExtremeSessionDependencies dependencies,
    std::unique_ptr<RgbOutputTarget> output
) : config_(std::move(config)),
    dependencies_(std::move(dependencies)),
    output_(std::move(output)),
    stagedOutput_(config_.outputPath) {}

std::unique_ptr<SuperResExtremePngSession> SuperResExtremePngSession::create(
    const SuperResExtremeSessionConfig& config,
    SuperResExtremeSessionDependencies dependencies,
    SuperResNativeCode& code
) {
    code = SuperResNativeCode::InvalidParams;
    if (!config_is_valid(config) || !dependencies_are_valid(dependencies)) {
        return nullptr;
    }

    const std::function<void(const std::string&)> completeOnConstructionFailure =
        dependencies.completeTask;
    bool registeredWithoutOwner = false;
    try {
        std::unique_ptr<RgbOutputTarget> output = dependencies.makeOutput();
        if (output == nullptr) {
            code = SuperResNativeCode::OutOfMemory;
            return nullptr;
        }
        if (!dependencies.registerTask(config.taskId)) {
            return nullptr;
        }
        registeredWithoutOwner = true;
        auto session = std::unique_ptr<SuperResExtremePngSession>(
            new SuperResExtremePngSession(config, std::move(dependencies), std::move(output))
        );
        registeredWithoutOwner = false;
        code = session->output_->initialize(
            session->config_.outputPath,
            session->config_.outputWidth,
            session->config_.outputHeight,
            session->config_.maximumBandHeight
        );
        if (code != SuperResNativeCode::Ok) {
            session->abort();
            return nullptr;
        }
        return session;
    } catch (const std::bad_alloc&) {
        code = SuperResNativeCode::OutOfMemory;
    } catch (...) {
        code = SuperResNativeCode::InvalidParams;
    }
    if (registeredWithoutOwner) {
        try {
            completeOnConstructionFailure(config.taskId);
        } catch (...) {
        }
    }
    return nullptr;
}

SuperResExtremePngSession::~SuperResExtremePngSession() {
    abort();
}

bool SuperResExtremePngSession::consume_cancellation_locked() {
    if (cancelled_) {
        return true;
    }
    try {
        cancelled_ = dependencies_.consumeCancelled(config_.taskId);
    } catch (...) {
        cancelled_ = true;
    }
    return cancelled_;
}

SuperResNativeCode SuperResExtremePngSession::process_tile(
    int tileIndex,
    int croppedOutputWidth,
    int croppedOutputHeight,
    const SuperResExtremeTileRunner& runner
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != State::Active || !runner || cursor_ >= config_.tiles.size()) {
        return cancelled_ ? SuperResNativeCode::Cancelled : SuperResNativeCode::InvalidParams;
    }
    const SuperResExtremeTileConfig& tile = config_.tiles[cursor_];
    if (tileIndex != tile.index || croppedOutputWidth != tile.outputWidth ||
        croppedOutputHeight != tile.outputHeight) {
        return SuperResNativeCode::InvalidParams;
    }
    if (consume_cancellation_locked()) {
        return SuperResNativeCode::Cancelled;
    }
    if (tile.outputX == 0 && !output_->prepare_window(tile.outputY, tile.outputHeight)) {
        return output_->failure_code() == SuperResNativeCode::Ok
            ? SuperResNativeCode::TileOutputMismatch
            : output_->failure_code();
    }

    ExtremeBandOutput adapter(*output_, tile);
    SuperResNativeCode code = SuperResNativeCode::InvalidParams;
    try {
        code = runner(tile, adapter);
    } catch (const std::bad_alloc&) {
        code = SuperResNativeCode::OutOfMemory;
    } catch (...) {
        code = SuperResNativeCode::Unsupported;
    }
    if (code != SuperResNativeCode::Ok) {
        return code;
    }
    if (!adapter.initialized()) {
        return SuperResNativeCode::TileOutputMismatch;
    }
    if (consume_cancellation_locked()) {
        return SuperResNativeCode::Cancelled;
    }
    ++cursor_;
    return SuperResNativeCode::Ok;
}

SuperResNativeCode SuperResExtremePngSession::finish() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != State::Active) {
        return cancelled_ ? SuperResNativeCode::Cancelled : SuperResNativeCode::InvalidParams;
    }
    if (cursor_ != config_.tiles.size()) {
        return SuperResNativeCode::InvalidParams;
    }
    if (consume_cancellation_locked()) {
        abort_locked();
        return SuperResNativeCode::Cancelled;
    }

    const SuperResNativeCode finishCode = output_->finish_attempt(stagedOutput_);
    if (finishCode != SuperResNativeCode::Ok) {
        abort_locked();
        return finishCode;
    }

    SuperResTaskPublishOutcome outcome = SuperResTaskPublishOutcome::Failed;
    try {
        outcome = dependencies_.publishOutput(stagedOutput_, config_.taskId);
    } catch (...) {
        outcome = SuperResTaskPublishOutcome::Failed;
    }
    output_.reset();
    if (outcome == SuperResTaskPublishOutcome::Published) {
        state_ = State::Finished;
        complete_task_once_locked();
        return SuperResNativeCode::Ok;
    }
    cancelled_ = outcome == SuperResTaskPublishOutcome::Cancelled;
    abort_locked();
    return cancelled_ ? SuperResNativeCode::Cancelled : SuperResNativeCode::OutputFailed;
}

void SuperResExtremePngSession::complete_task_once_locked() noexcept {
    if (taskCompleted_) {
        return;
    }
    taskCompleted_ = true;
    try {
        dependencies_.completeTask(config_.taskId);
    } catch (...) {
    }
}

void SuperResExtremePngSession::abort_locked() noexcept {
    if (state_ != State::Active) {
        return;
    }
    output_.reset();
    try {
        stagedOutput_.abort();
    } catch (...) {
    }
    state_ = State::Aborted;
    complete_task_once_locked();
}

void SuperResExtremePngSession::abort() {
    std::lock_guard<std::mutex> lock(mutex_);
    abort_locked();
}

size_t SuperResExtremePngSession::completed_tiles() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return cursor_;
}

bool SuperResExtremePngSession::terminal() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return state_ != State::Active;
}

std::string SuperResExtremePngSession::task_id() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return config_.taskId;
}

uint64_t SuperResExtremeSessionHandleRegistry::insert(
    std::shared_ptr<SuperResExtremePngSession> session
) {
    if (session == nullptr) {
        return 0u;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    for (size_t attempt = 0; attempt <= sessions_.size(); ++attempt) {
        if (nextHandle_ == 0u ||
            nextHandle_ > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
            nextHandle_ = 1u;
        }
        uint64_t candidate = nextHandle_++;
        if (candidate != 0u && sessions_.find(candidate) == sessions_.end()) {
            sessions_.emplace(candidate, std::move(session));
            return candidate;
        }
    }
    return 0u;
}

std::shared_ptr<SuperResExtremePngSession> SuperResExtremeSessionHandleRegistry::find(
    uint64_t handle
) const {
    if (handle == 0u) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = sessions_.find(handle);
    return found == sessions_.end() ? nullptr : found->second;
}

std::shared_ptr<SuperResExtremePngSession> SuperResExtremeSessionHandleRegistry::remove(
    uint64_t handle
) {
    if (handle == 0u) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = sessions_.find(handle);
    if (found == sessions_.end()) {
        return nullptr;
    }
    std::shared_ptr<SuperResExtremePngSession> result = std::move(found->second);
    sessions_.erase(found);
    return result;
}
