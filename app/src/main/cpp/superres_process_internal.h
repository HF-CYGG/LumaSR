#pragma once

#include "superres_engine.h"

#include <functional>
#include <string>

enum class SuperResProcessInternalEvent { ForceCpu, AutoCpuFallback };

struct SuperResProcessInternalDependencies {
    std::function<SuperResRgbImage()> loadImage;
    std::function<SuperResNativeCode(const SuperResNativeParams&,
                                     const SuperResProgressCallback&,
                                     const SuperResRuntimeControlCallback&,
                                     bool,
                                     const SuperResRgbImage&,
                                     long long)>
        runImage;
    std::function<bool(const std::string&)> registerTask;
    std::function<void(const std::string&)> completeTask;
    std::function<bool(const std::string&)> inputExists;
    std::function<void(SuperResProcessInternalEvent, SuperResNativeCode)> onEvent;
};

SuperResNativeCode
process_superres_internal(const SuperResNativeParams& params,
                          const SuperResProgressCallback& onProgress,
                          const SuperResRuntimeControlCallback& runtimeControl,
                          bool requireInputPath,
                          const SuperResProcessInternalDependencies& dependencies);
