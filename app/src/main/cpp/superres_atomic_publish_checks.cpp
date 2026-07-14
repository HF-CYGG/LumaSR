#include "superres_atomic_publish_checks.h"

#include "superres_atomic_publish.h"

#include <string>
#include <vector>

bool run_atomic_publish_runtime_checks() {
    std::vector<std::string> removedPaths;
    bool oldFinalPreserved = true;
    const SuperResAtomicPublishOps failingRename{
        [](const char*, const char*) { return -1; },
        [](const std::string&) { return true; },
        [&removedPaths, &oldFinalPreserved](const char* path) {
            const std::string value = path == nullptr ? "" : path;
            removedPaths.push_back(value);
            if (value == "old-final.png") oldFinalPreserved = false;
            return 0;
        }
    };
    if (publish_atomic_file("old-final.png.part", "old-final.png", failingRename) !=
            SuperResAtomicPublishStatus::IoError ||
        !oldFinalPreserved ||
        removedPaths != std::vector<std::string>{"old-final.png.part"}) {
        return false;
    }

    bool committedFinalRemoved = false;
    const SuperResAtomicPublishOps failingDirectorySync{
        [](const char*, const char*) { return 0; },
        [](const std::string&) { return false; },
        [&committedFinalRemoved](const char* path) {
            if (path != nullptr && std::string(path) == "output.png") {
                committedFinalRemoved = true;
            }
            return 0;
        }
    };
    if (publish_atomic_file("output.png.part", "output.png", failingDirectorySync) !=
            SuperResAtomicPublishStatus::IoError ||
        committedFinalRemoved) {
        return false;
    }

    bool renamed = false;
    bool parentSynced = false;
    const SuperResAtomicPublishOps successful{
        [&renamed](const char*, const char*) {
            renamed = true;
            return 0;
        },
        [&parentSynced](const std::string&) {
            parentSynced = true;
            return true;
        },
        [](const char*) { return 0; }
    };
    return publish_atomic_file("output.png.part", "output.png", successful) ==
            SuperResAtomicPublishStatus::Ok &&
        renamed && parentSynced;
}
