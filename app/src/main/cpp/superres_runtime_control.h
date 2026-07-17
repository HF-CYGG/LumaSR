#pragma once

struct SuperResRuntimeInferenceControl {
    int commandBatchSize = 1;
    int thermalHeadroomPercent = 0;
    bool shouldPause = false;
    // Telemetry-only for Wave2; command submission remains intentionally serial.
    int maxPipelineDepth = 1;
};

constexpr int clamp_runtime_control_value(int value, int minimum, int maximum) {
    return value < minimum ? minimum : value > maximum ? maximum : value;
}

constexpr SuperResRuntimeInferenceControl sanitize_runtime_control(
    const SuperResRuntimeInferenceControl& control
) {
    return SuperResRuntimeInferenceControl{
        clamp_runtime_control_value(control.commandBatchSize, 1, 8),
        clamp_runtime_control_value(control.thermalHeadroomPercent, 0, 25),
        control.shouldPause,
        clamp_runtime_control_value(control.maxPipelineDepth, 1, 2)
    };
}

constexpr SuperResRuntimeInferenceControl decode_runtime_control_values(
    int valueCount,
    int commandBatchSize,
    int thermalHeadroomPercent,
    bool shouldPause,
    int optionalMaxPipelineDepth
) {
    return sanitize_runtime_control(SuperResRuntimeInferenceControl{
        commandBatchSize,
        thermalHeadroomPercent,
        shouldPause,
        valueCount >= 4 ? optionalMaxPipelineDepth : 1
    });
}

constexpr int resolve_runtime_headroom_percent(
    int baselineGpuHeadroomPercent,
    int thermalHeadroomPercent,
    bool useVulkan
) {
    const int baseline = clamp_runtime_control_value(baselineGpuHeadroomPercent, 0, 25);
    const int thermal = clamp_runtime_control_value(thermalHeadroomPercent, 0, 25);
    return useVulkan ? (baseline > thermal ? baseline : thermal) : thermal;
}
