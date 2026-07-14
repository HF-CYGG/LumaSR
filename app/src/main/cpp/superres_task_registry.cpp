#include "superres_task_registry.h"

bool SuperResTaskRegistry::register_task(const std::string& taskId) {
    if (taskId.empty()) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    if (!activeTasks_.insert(taskId).second) return false;
    cancelledTasks_.erase(taskId);
    return true;
}

void SuperResTaskRegistry::complete_task(const std::string& taskId) {
    std::lock_guard<std::mutex> lock(mutex_);
    activeTasks_.erase(taskId);
    cancelledTasks_.erase(taskId);
}

void SuperResTaskRegistry::cancel(const std::string& taskId) {
    if (taskId.empty()) return;
    std::lock_guard<std::mutex> lock(mutex_);
    if (activeTasks_.find(taskId) != activeTasks_.end()) {
        cancelledTasks_.insert(taskId);
    }
}

bool SuperResTaskRegistry::consume_cancelled(const std::string& taskId) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto found = cancelledTasks_.find(taskId);
    if (found == cancelledTasks_.end()) return false;
    cancelledTasks_.erase(found);
    return true;
}

SuperResTaskPublishOutcome SuperResTaskRegistry::publish_or_cancel(
    const std::string& taskId,
    const std::function<bool()>& publish,
    const std::function<void()>& abort
) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (activeTasks_.find(taskId) == activeTasks_.end() || !publish || !abort) {
        return SuperResTaskPublishOutcome::Failed;
    }
    const auto cancelled = cancelledTasks_.find(taskId);
    if (cancelled != cancelledTasks_.end()) {
        cancelledTasks_.erase(cancelled);
        abort();
        return SuperResTaskPublishOutcome::Cancelled;
    }
    return publish()
        ? SuperResTaskPublishOutcome::Published
        : SuperResTaskPublishOutcome::Failed;
}
