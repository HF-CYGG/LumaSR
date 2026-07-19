#include "superres_atomic_publish.h"

#include <cstdio>
#include <filesystem>

#if !defined(_WIN32)
#include <fcntl.h>
#include <unistd.h>
#endif

namespace {

bool sync_parent_directory(const std::string& outputPath) {
#if defined(_WIN32)
    // Host checks exercise the production rename path. Android keeps the durable directory fsync
    // below; the Windows CRT has no portable equivalent for syncing a directory handle.
    (void)outputPath;
    return true;
#else
    try {
        std::filesystem::path parent = std::filesystem::path(outputPath).parent_path();
        if (parent.empty()) parent = ".";
        const int directory = ::open(parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (directory < 0) return false;
        const bool synced = ::fsync(directory) == 0;
        const bool closed = ::close(directory) == 0;
        return synced && closed;
    } catch (...) {
        return false;
    }
#endif
}

} // namespace

const SuperResAtomicPublishOps& default_atomic_publish_ops() {
    static const SuperResAtomicPublishOps operations{
        [](const char* from, const char* to) { return std::rename(from, to); },
        sync_parent_directory,
        [](const char* path) { return std::remove(path); }
    };
    return operations;
}

SuperResAtomicPublishStatus publish_atomic_file(
    const std::string& temporaryPath,
    const std::string& outputPath,
    const SuperResAtomicPublishOps& operations
) {
    if (temporaryPath.empty() || outputPath.empty() || temporaryPath == outputPath ||
        !operations.renameFile || !operations.syncParentDirectory || !operations.removeFile) {
        return SuperResAtomicPublishStatus::InvalidParams;
    }
    // POSIX rename replaces an existing regular file atomically. Never unlink the destination
    // first: a failed rename must leave the previously committed image available.
    if (operations.renameFile(temporaryPath.c_str(), outputPath.c_str()) != 0) {
        operations.removeFile(temporaryPath.c_str());
        return SuperResAtomicPublishStatus::IoError;
    }
    // At this point the complete file is committed. A directory fsync failure is reported, but
    // the fully written final must not be deleted because the previous name is no longer recoverable.
    return operations.syncParentDirectory(outputPath)
        ? SuperResAtomicPublishStatus::Ok
        : SuperResAtomicPublishStatus::IoError;
}
