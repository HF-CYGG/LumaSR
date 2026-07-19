#pragma once

#include "superres_output_finalizer.h"
#include "superres_rgb_output_target.h"

#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

struct SuperResExtremeTileConfig {
    int index = -1;
    int outputX = 0;
    int outputY = 0;
    int outputWidth = 0;
    int outputHeight = 0;
};

struct SuperResExtremeSessionConfig {
    std::string taskId;
    std::string outputPath;
    int outputWidth = 0;
    int outputHeight = 0;
    int maximumBandHeight = 0;
    std::vector<SuperResExtremeTileConfig> tiles;
};

struct SuperResExtremeSessionDependencies {
    std::function<std::unique_ptr<RgbOutputTarget>()> makeOutput;
    std::function<bool(const std::string&)> registerTask;
    std::function<void(const std::string&)> completeTask;
    std::function<bool(const std::string&)> consumeCancelled;
    std::function<SuperResTaskPublishOutcome(
        SuperResStagedOutput&,
        const std::string&
    )> publishOutput;
};

using SuperResExtremeTileRunner = std::function<SuperResNativeCode(
    const SuperResExtremeTileConfig&,
    RgbOutputTarget&
)>;

bool checked_extreme_tile_config_array_length(
    size_t tileCount,
    size_t stride,
    size_t& flatLength
);

class SuperResExtremePngSession {
public:
    static std::unique_ptr<SuperResExtremePngSession> create(
        const SuperResExtremeSessionConfig& config,
        SuperResExtremeSessionDependencies dependencies,
        SuperResNativeCode& code
    );

    ~SuperResExtremePngSession();

    SuperResExtremePngSession(const SuperResExtremePngSession&) = delete;
    SuperResExtremePngSession& operator=(const SuperResExtremePngSession&) = delete;

    SuperResNativeCode process_tile(
        int tileIndex,
        int croppedOutputWidth,
        int croppedOutputHeight,
        const SuperResExtremeTileRunner& runner
    );
    SuperResNativeCode finish();
    void abort();

    size_t completed_tiles() const;
    bool terminal() const;
    std::string task_id() const;

private:
    enum class State {
        Active,
        Finished,
        Aborted
    };

    SuperResExtremePngSession(
        SuperResExtremeSessionConfig config,
        SuperResExtremeSessionDependencies dependencies,
        std::unique_ptr<RgbOutputTarget> output
    );

    bool consume_cancellation_locked();
    void complete_task_once_locked() noexcept;
    void abort_locked() noexcept;

    SuperResExtremeSessionConfig config_;
    SuperResExtremeSessionDependencies dependencies_;
    std::unique_ptr<RgbOutputTarget> output_;
    SuperResStagedOutput stagedOutput_;
    mutable std::mutex mutex_;
    size_t cursor_ = 0;
    State state_ = State::Active;
    bool cancelled_ = false;
    bool taskCompleted_ = false;
};

class SuperResExtremeSessionHandleRegistry {
public:
    uint64_t insert(std::shared_ptr<SuperResExtremePngSession> session);
    std::shared_ptr<SuperResExtremePngSession> find(uint64_t handle) const;
    std::shared_ptr<SuperResExtremePngSession> remove(uint64_t handle);

private:
    mutable std::mutex mutex_;
    std::unordered_map<uint64_t, std::shared_ptr<SuperResExtremePngSession>> sessions_;
    uint64_t nextHandle_ = 1u;
};
