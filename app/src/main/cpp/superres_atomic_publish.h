#pragma once

#include <functional>
#include <string>

enum class SuperResAtomicPublishStatus {
    Ok,
    InvalidParams,
    IoError
};

struct SuperResAtomicPublishOps {
    std::function<int(const char*, const char*)> renameFile;
    std::function<bool(const std::string&)> syncParentDirectory;
    std::function<int(const char*)> removeFile;
};

const SuperResAtomicPublishOps& default_atomic_publish_ops();

SuperResAtomicPublishStatus publish_atomic_file(
    const std::string& temporaryPath,
    const std::string& outputPath,
    const SuperResAtomicPublishOps& operations = default_atomic_publish_ops()
);
