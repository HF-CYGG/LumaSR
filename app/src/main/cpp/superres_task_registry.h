#pragma once

#include <functional>
#include <mutex>
#include <string>
#include <unordered_set>

enum class SuperResTaskPublishOutcome {
    Published,
    Cancelled,
    Failed
};

class SuperResTaskRegistry {
public:
    bool register_task(const std::string& taskId);
    void complete_task(const std::string& taskId);
    void cancel(const std::string& taskId);
    bool consume_cancelled(const std::string& taskId);

    SuperResTaskPublishOutcome publish_or_cancel(
        const std::string& taskId,
        const std::function<bool()>& publish,
        const std::function<void()>& abort
    );

private:
    std::mutex mutex_;
    std::unordered_set<std::string> activeTasks_;
    std::unordered_set<std::string> cancelledTasks_;
};
