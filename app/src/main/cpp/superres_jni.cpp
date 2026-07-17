#include "superres_engine.h"
#include "superres_extreme_session.h"
#include "superres_jni_safety.h"

#include <android/bitmap.h>
#include <cstdint>
#include <jni.h>
#include <limits>
#include <new>
#include <string>
#include <utility>
#include <vector>

namespace {
std::string to_string(JNIEnv* env, jstring value) {
    return superres_to_string_with(
        env,
        value,
        [](const char* chars) { return std::string(chars); }
    );
}

void emit_progress(
    JNIEnv* env,
    jobject progress_sink,
    SuperResNativeStage stage,
    int current_tile,
    int total_tiles,
    float progress,
    const std::string& message,
    const SuperResNativePerformance& performance
) {
    if (progress_sink == nullptr) {
        return;
    }
    throw_if_superres_jni_exception_pending(env);

    jclass sink_class = env->GetObjectClass(progress_sink);
    try {
        throw_if_superres_jni_exception_pending(env);
        if (sink_class == nullptr) {
            throw SuperResJniPendingException{};
        }
    } catch (...) {
        if (sink_class != nullptr) {
            env->DeleteLocalRef(sink_class);
        }
        throw;
    }

    jmethodID method = nullptr;
    try {
        method = env->GetMethodID(
            sink_class,
            "onProgress",
            "(IIIFLjava/lang/String;ZJJJJJJJZIIIIIJJJJJJIIII)V"
        );
        throw_if_superres_jni_exception_pending(env);
    } catch (...) {
        env->DeleteLocalRef(sink_class);
        throw;
    }
    env->DeleteLocalRef(sink_class);
    if (method == nullptr) {
        throw SuperResJniPendingException{};
    }

    jstring j_message = env->NewStringUTF(message.c_str());
    try {
        throw_if_superres_jni_exception_pending(env);
        if (j_message == nullptr) {
            throw SuperResJniPendingException{};
        }
        env->CallVoidMethod(
            progress_sink,
            method,
            static_cast<jint>(stage),
            static_cast<jint>(current_tile),
            static_cast<jint>(total_tiles),
            static_cast<jfloat>(progress),
            j_message,
            performance.hasValue ? JNI_TRUE : JNI_FALSE,
            static_cast<jlong>(performance.decodeMs),
            static_cast<jlong>(performance.modelLoadMs),
            static_cast<jlong>(performance.tileInputMs),
            static_cast<jlong>(performance.tileExtractMs),
            static_cast<jlong>(performance.tileCopyMs),
            static_cast<jlong>(performance.saveMs),
            static_cast<jlong>(performance.totalMs),
            performance.cacheHit ? JNI_TRUE : JNI_FALSE,
            static_cast<jint>(performance.accelerationMode),
            static_cast<jint>(performance.tileSize),
            static_cast<jint>(performance.cacheSize),
            static_cast<jint>(performance.retryCount),
            static_cast<jint>(performance.regionIndex),
            static_cast<jlong>(performance.preprocessMs),
            static_cast<jlong>(performance.uploadMs),
            static_cast<jlong>(performance.inferenceMs),
            static_cast<jlong>(performance.downloadMs),
            static_cast<jlong>(performance.postprocessMs),
            static_cast<jlong>(performance.encodeMs),
            static_cast<jint>(performance.effectiveBackend),
            static_cast<jint>(performance.effectivePrecision),
            static_cast<jint>(performance.fallbackReason),
            static_cast<jint>(performance.requestedMaxPipelineDepth)
        );
        throw_if_superres_jni_exception_pending(env);
    } catch (...) {
        if (j_message != nullptr) {
            env->DeleteLocalRef(j_message);
        }
        throw;
    }
    env->DeleteLocalRef(j_message);
}

bool read_runtime_control(
    JNIEnv* env,
    jobject progress_sink,
    SuperResRuntimeInferenceControl& control
) {
    if (progress_sink == nullptr) {
        return false;
    }
    if (env->ExceptionCheck()) {
        // Runtime-control sampling is best-effort: discard callback failures and
        // let the caller retain the last successfully sampled value.
        env->ExceptionClear();
        return false;
    }
    jclass sink_class = env->GetObjectClass(progress_sink);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (sink_class != nullptr) env->DeleteLocalRef(sink_class);
        return false;
    }
    if (sink_class == nullptr) {
        return false;
    }
    const jmethodID method = env->GetMethodID(sink_class, "currentRuntimeControl", "()[I");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(sink_class);
        return false;
    }
    env->DeleteLocalRef(sink_class);
    if (method == nullptr) {
        return false;
    }

    jintArray values = static_cast<jintArray>(env->CallObjectMethod(progress_sink, method));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        if (values != nullptr) env->DeleteLocalRef(values);
        return false;
    }
    if (values == nullptr) {
        return false;
    }
    const jsize value_count = env->GetArrayLength(values);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(values);
        return false;
    }
    if (value_count < 3) {
        env->DeleteLocalRef(values);
        return false;
    }
    jint raw[4] = {};
    const jsize fieldsToRead = value_count >= 4 ? 4 : 3;
    env->GetIntArrayRegion(values, 0, fieldsToRead, raw);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(values);
        return false;
    }
    env->DeleteLocalRef(values);
    control = decode_runtime_control_values(
        static_cast<int>(value_count),
        static_cast<int>(raw[0]),
        static_cast<int>(raw[1]),
        raw[2] != 0,
        static_cast<int>(raw[3])
    );
    return true;
}

int unlock_bitmap_pixels(JNIEnv* env, jobject bitmap) noexcept {
    return AndroidBitmap_unlockPixels(env, bitmap);
}

SuperResNativeCode bitmap_to_rgb(
    JNIEnv* env,
    jobject bitmap,
    SuperResRgbImage& image
) noexcept {
    if (bitmap == nullptr) {
        return SuperResNativeCode::InvalidParams;
    }
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.width == 0 || info.height == 0 ||
        info.width > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        info.height > static_cast<uint32_t>(std::numeric_limits<int>::max())) {
        return SuperResNativeCode::InvalidParams;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 &&
        info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        return SuperResNativeCode::InvalidParams;
    }
    const size_t sourcePixelBytes =
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 ? 4u : 2u;
    if (static_cast<size_t>(info.width) >
            std::numeric_limits<size_t>::max() / sourcePixelBytes ||
        info.stride < static_cast<size_t>(info.width) * sourcePixelBytes ||
        static_cast<size_t>(info.height) >
            std::numeric_limits<size_t>::max() / static_cast<size_t>(info.stride)) {
        return SuperResNativeCode::InvalidParams;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return SuperResNativeCode::InvalidParams;
    }
    SuperResBitmapUnlockGuard unlockGuard(env, bitmap, unlock_bitmap_pixels);

    if (pixels == nullptr) {
        unlockGuard.unlock();
        return SuperResNativeCode::InvalidParams;
    }
    image = convert_superres_bitmap_pixels_to_rgb(
        static_cast<const unsigned char*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<size_t>(info.stride),
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888
            ? SuperResBitmapFormat::Rgba8888
            : SuperResBitmapFormat::Rgb565
    );
    const int unlockResult = unlockGuard.unlock();
    if (unlockResult != ANDROID_BITMAP_RESULT_SUCCESS) {
        image = {};
        return SuperResNativeCode::InvalidParams;
    }
    return image.valid()
        ? SuperResNativeCode::Ok
        : SuperResNativeCode::OutOfMemory;
}

jobject box_nullable_int(
    JNIEnv* env,
    jclass integer_class,
    jmethodID integer_value_of,
    int value,
    bool present
) {
    if (!present || env->ExceptionCheck()) {
        return nullptr;
    }
    return env->CallStaticObjectMethod(
        integer_class,
        integer_value_of,
        static_cast<jint>(value)
    );
}

jstring new_nullable_string(JNIEnv* env, const std::string& value) {
    if (value.empty() || env->ExceptionCheck()) {
        return nullptr;
    }
    return env->NewStringUTF(value.c_str());
}

void delete_local_ref(JNIEnv* env, jobject reference) {
    if (reference != nullptr) {
        env->DeleteLocalRef(reference);
    }
}
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_lumasr_data_NativeCapabilityJni_readSnapshot(
    JNIEnv* env,
    jobject
) {
    SuperResNativeCapabilitySnapshot snapshot;
    try {
        snapshot = read_superres_native_capability_snapshot();
    } catch (...) {
        return nullptr;
    }

    jclass snapshot_class = nullptr;
    jclass integer_class = nullptr;
    jobject physical_cores = nullptr;
    jobject big_cores = nullptr;
    jobject little_cores = nullptr;
    jobject rough_score = nullptr;
    jstring gpu_vendor = nullptr;
    jstring gpu_device = nullptr;
    jstring gpu_driver = nullptr;
    jobject result = nullptr;

    do {
        snapshot_class = env->FindClass("com/lumasr/data/NativeCapabilitySnapshot");
        if (snapshot_class == nullptr || env->ExceptionCheck()) {
            break;
        }
        const jmethodID snapshot_constructor = env->GetMethodID(
            snapshot_class,
            "<init>",
            "(Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;ZZZ)V"
        );
        if (snapshot_constructor == nullptr || env->ExceptionCheck()) {
            break;
        }

        integer_class = env->FindClass("java/lang/Integer");
        if (integer_class == nullptr || env->ExceptionCheck()) {
            break;
        }
        const jmethodID integer_value_of = env->GetStaticMethodID(
            integer_class,
            "valueOf",
            "(I)Ljava/lang/Integer;"
        );
        if (integer_value_of == nullptr || env->ExceptionCheck()) {
            break;
        }

        physical_cores = box_nullable_int(
            env,
            integer_class,
            integer_value_of,
            snapshot.physicalCoreCount,
            snapshot.physicalCoreCount > 0
        );
        big_cores = box_nullable_int(
            env,
            integer_class,
            integer_value_of,
            snapshot.bigCoreCount,
            snapshot.bigCoreCount > 0
        );
        little_cores = box_nullable_int(
            env,
            integer_class,
            integer_value_of,
            snapshot.littleCoreCount,
            snapshot.littleCoreCount > 0
        );
        rough_score = box_nullable_int(
            env,
            integer_class,
            integer_value_of,
            snapshot.gpuRoughScore,
            snapshot.gpuRoughScore >= 0
        );
        gpu_vendor = new_nullable_string(env, snapshot.gpuVendor);
        gpu_device = new_nullable_string(env, snapshot.gpuDevice);
        gpu_driver = new_nullable_string(env, snapshot.gpuDriverVersion);
        if (env->ExceptionCheck()) {
            break;
        }

        result = env->NewObject(
            snapshot_class,
            snapshot_constructor,
            physical_cores,
            big_cores,
            little_cores,
            gpu_vendor,
            gpu_device,
            gpu_driver,
            rough_score,
            snapshot.supportsVulkan ? JNI_TRUE : JNI_FALSE,
            snapshot.supportsFp16Storage ? JNI_TRUE : JNI_FALSE,
            snapshot.supportsFp16Arithmetic ? JNI_TRUE : JNI_FALSE
        );
    } while (false);

    delete_local_ref(env, physical_cores);
    delete_local_ref(env, big_cores);
    delete_local_ref(env, little_cores);
    delete_local_ref(env, rough_score);
    delete_local_ref(env, gpu_vendor);
    delete_local_ref(env, gpu_device);
    delete_local_ref(env, gpu_driver);
    delete_local_ref(env, integer_class);
    delete_local_ref(env, snapshot_class);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_processNative(
    JNIEnv* env,
    jobject,
    jstring task_id,
    jstring input_path,
    jstring output_path,
    jint engine_type,
    jstring model_dir,
    jstring model_file_base,
    jint scale,
    jint noise,
    jint tile_size,
    jint gpu_headroom_percent,
    jint acceleration_mode,
    jboolean tta,
    jint output_mode,
    jint output_crop_left,
    jint output_crop_top,
    jint output_crop_width,
    jint output_crop_height,
    jint retry_count,
    jint region_index,
    jint native_backend,
    jint inference_precision,
    jint cpu_threads,
    jint openmp_blocktime_ms,
    jint postprocess_threads,
    jint command_batch_size,
    jint model_cache_capacity,
    jobject progress_sink
) {
    return static_cast<jint>(run_superres_process_jni_boundary(env, [&]() -> int {
        SuperResNativeParams params{
            to_string(env, task_id),
            to_string(env, input_path),
            to_string(env, output_path),
            static_cast<int>(engine_type),
            to_string(env, model_dir),
            to_string(env, model_file_base),
            static_cast<int>(scale),
            static_cast<int>(noise),
            static_cast<int>(tile_size),
            static_cast<int>(gpu_headroom_percent),
            static_cast<int>(acceleration_mode),
            tta == JNI_TRUE,
            static_cast<int>(output_mode),
            static_cast<int>(output_crop_left),
            static_cast<int>(output_crop_top),
            static_cast<int>(output_crop_width),
            static_cast<int>(output_crop_height),
            static_cast<int>(retry_count),
            static_cast<int>(region_index),
            static_cast<int>(native_backend),
            static_cast<int>(inference_precision),
            static_cast<int>(cpu_threads),
            static_cast<int>(openmp_blocktime_ms),
            static_cast<int>(postprocess_threads),
            static_cast<int>(command_batch_size),
            static_cast<int>(model_cache_capacity)
        };
        const SuperResProgressCallback progress_callback = [env, progress_sink](
                SuperResNativeStage stage,
                int current_tile,
                int total_tiles,
                float progress,
                const std::string& message,
                const SuperResNativePerformance& performance
            ) {
                emit_progress(env, progress_sink, stage, current_tile, total_tiles, progress, message, performance);
            };
        const SuperResRuntimeControlCallback runtime_control = [
            env,
            progress_sink,
            last_control = sanitize_runtime_control(
                SuperResRuntimeInferenceControl{params.commandBatchSize, 0, false, 1}
            )
        ]() mutable {
            SuperResRuntimeInferenceControl next_control;
            if (read_runtime_control(env, progress_sink, next_control)) {
                last_control = next_control;
            }
            return last_control;
        };
        return static_cast<int>(process_superres(params, progress_callback, runtime_control));
    }));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_processMemoryPipelineNative(
    JNIEnv* env,
    jobject,
    jstring task_id,
    jstring input_path,
    jstring output_path,
    jobjectArray model_dirs,
    jobjectArray model_file_bases,
    jintArray pass_configs,
    jlong transport_budget_bytes,
    jobject progress_sink
) {
    constexpr jsize kPassConfigStride = 21;
    return static_cast<jint>(run_superres_process_jni_boundary(env, [&]() -> int {
        if (task_id == nullptr || input_path == nullptr || output_path == nullptr ||
            model_dirs == nullptr || model_file_bases == nullptr || pass_configs == nullptr ||
            transport_budget_bytes <= 0) {
            return static_cast<int>(SuperResNativeCode::InvalidParams);
        }
        const jsize passCount = env->GetArrayLength(model_dirs);
        throw_if_superres_jni_exception_pending(env);
        const jsize modelBaseCount = env->GetArrayLength(model_file_bases);
        throw_if_superres_jni_exception_pending(env);
        const jsize configCount = env->GetArrayLength(pass_configs);
        throw_if_superres_jni_exception_pending(env);
        if (passCount < 2 || modelBaseCount != passCount ||
            passCount > std::numeric_limits<jsize>::max() / kPassConfigStride ||
            configCount != passCount * kPassConfigStride ||
            static_cast<unsigned long long>(transport_budget_bytes) >
                static_cast<unsigned long long>(std::numeric_limits<size_t>::max())) {
            return static_cast<int>(SuperResNativeCode::InvalidParams);
        }

        std::vector<jint> configs(static_cast<size_t>(configCount));
        env->GetIntArrayRegion(pass_configs, 0, configCount, configs.data());
        throw_if_superres_jni_exception_pending(env);

        const std::string taskId = to_string(env, task_id);
        const std::string inputPath = to_string(env, input_path);
        const std::string outputPath = to_string(env, output_path);
        std::vector<SuperResNativeParams> passes;
        passes.reserve(static_cast<size_t>(passCount));
        for (jsize index = 0; index < passCount; ++index) {
            jstring modelDir = static_cast<jstring>(
                env->GetObjectArrayElement(model_dirs, index)
            );
            throw_if_superres_jni_exception_pending(env);
            jstring modelFileBase = static_cast<jstring>(
                env->GetObjectArrayElement(model_file_bases, index)
            );
            throw_if_superres_jni_exception_pending(env);
            if (modelDir == nullptr || modelFileBase == nullptr) {
                if (modelDir != nullptr) env->DeleteLocalRef(modelDir);
                if (modelFileBase != nullptr) env->DeleteLocalRef(modelFileBase);
                return static_cast<int>(SuperResNativeCode::InvalidParams);
            }
            const size_t base = static_cast<size_t>(index) *
                static_cast<size_t>(kPassConfigStride);
            SuperResNativeParams params{};
            params.taskId = taskId;
            params.inputPath = inputPath;
            params.outputPath = outputPath;
            params.modelDir = to_string(env, modelDir);
            params.modelFileBase = to_string(env, modelFileBase);
            env->DeleteLocalRef(modelDir);
            env->DeleteLocalRef(modelFileBase);
            params.engineType = static_cast<int>(configs[base]);
            params.scale = static_cast<int>(configs[base + 1u]);
            params.noise = static_cast<int>(configs[base + 2u]);
            params.tileSize = static_cast<int>(configs[base + 3u]);
            params.gpuHeadroomPercent = static_cast<int>(configs[base + 4u]);
            params.accelerationMode = static_cast<int>(configs[base + 5u]);
            params.tta = configs[base + 6u] != 0;
            params.outputMode = static_cast<int>(configs[base + 7u]);
            params.outputCropLeft = static_cast<int>(configs[base + 8u]);
            params.outputCropTop = static_cast<int>(configs[base + 9u]);
            params.outputCropWidth = static_cast<int>(configs[base + 10u]);
            params.outputCropHeight = static_cast<int>(configs[base + 11u]);
            params.retryCount = static_cast<int>(configs[base + 12u]);
            params.regionIndex = static_cast<int>(configs[base + 13u]);
            params.nativeBackend = static_cast<int>(configs[base + 14u]);
            params.inferencePrecision = static_cast<int>(configs[base + 15u]);
            params.cpuThreads = static_cast<int>(configs[base + 16u]);
            params.openmpBlocktimeMs = static_cast<int>(configs[base + 17u]);
            params.postprocessThreads = static_cast<int>(configs[base + 18u]);
            params.commandBatchSize = static_cast<int>(configs[base + 19u]);
            params.modelCacheCapacity = static_cast<int>(configs[base + 20u]);
            passes.push_back(std::move(params));
        }

        const SuperResProgressCallback progressCallback = [env, progress_sink](
            SuperResNativeStage stage,
            int currentTile,
            int totalTiles,
            float progress,
            const std::string& message,
            const SuperResNativePerformance& performance
        ) {
            emit_progress(
                env,
                progress_sink,
                stage,
                currentTile,
                totalTiles,
                progress,
                message,
                performance
            );
        };
        const SuperResRuntimeControlCallback runtimeControl = [
            env,
            progress_sink,
            lastControl = sanitize_runtime_control(
                SuperResRuntimeInferenceControl{
                    passes.front().commandBatchSize,
                    0,
                    false,
                    1
                }
            )
        ]() mutable {
            SuperResRuntimeInferenceControl nextControl;
            if (read_runtime_control(env, progress_sink, nextControl)) {
                lastControl = nextControl;
            }
            return lastControl;
        };
        return static_cast<int>(process_superres_pipeline(
            taskId,
            passes,
            static_cast<size_t>(transport_budget_bytes),
            progressCallback,
            runtimeControl
        ));
    }));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_processBitmapRegionNative(
    JNIEnv* env,
    jobject,
    jstring task_id,
    jobject bitmap,
    jstring output_path,
    jint engine_type,
    jstring model_dir,
    jstring model_file_base,
    jint scale,
    jint noise,
    jint tile_size,
    jint gpu_headroom_percent,
    jint acceleration_mode,
    jboolean tta,
    jint output_mode,
    jint output_crop_left,
    jint output_crop_top,
    jint output_crop_width,
    jint output_crop_height,
    jint retry_count,
    jint region_index,
    jint native_backend,
    jint inference_precision,
    jint cpu_threads,
    jint openmp_blocktime_ms,
    jint postprocess_threads,
    jint command_batch_size,
    jint model_cache_capacity,
    jobject progress_sink
) {
    return static_cast<jint>(run_superres_bitmap_jni_boundary(env, [&]() -> int {
        SuperResRgbImage image;
        const SuperResNativeCode conversionCode = bitmap_to_rgb(env, bitmap, image);
        if (conversionCode != SuperResNativeCode::Ok) {
            return static_cast<int>(conversionCode);
        }

        SuperResNativeParams params{
            to_string(env, task_id),
            "",
            to_string(env, output_path),
            static_cast<int>(engine_type),
            to_string(env, model_dir),
            to_string(env, model_file_base),
            static_cast<int>(scale),
            static_cast<int>(noise),
            static_cast<int>(tile_size),
            static_cast<int>(gpu_headroom_percent),
            static_cast<int>(acceleration_mode),
            tta == JNI_TRUE,
            static_cast<int>(output_mode),
            static_cast<int>(output_crop_left),
            static_cast<int>(output_crop_top),
            static_cast<int>(output_crop_width),
            static_cast<int>(output_crop_height),
            static_cast<int>(retry_count),
            static_cast<int>(region_index),
            static_cast<int>(native_backend),
            static_cast<int>(inference_precision),
            static_cast<int>(cpu_threads),
            static_cast<int>(openmp_blocktime_ms),
            static_cast<int>(postprocess_threads),
            static_cast<int>(command_batch_size),
            static_cast<int>(model_cache_capacity)
        };
        const SuperResProgressCallback progress_callback = [env, progress_sink](
            SuperResNativeStage stage,
            int current_tile,
            int total_tiles,
            float progress,
            const std::string& message,
            const SuperResNativePerformance& performance
        ) {
            emit_progress(env, progress_sink, stage, current_tile, total_tiles, progress, message, performance);
        };
        const SuperResRuntimeControlCallback runtime_control = [
            env,
            progress_sink,
            last_control = sanitize_runtime_control(
                SuperResRuntimeInferenceControl{params.commandBatchSize, 0, false, 1}
            )
        ]() mutable {
            SuperResRuntimeInferenceControl next_control;
            if (read_runtime_control(env, progress_sink, next_control)) {
                last_control = next_control;
            }
            return last_control;
        };
        return static_cast<int>(process_superres_rgb(
            params,
            std::move(image),
            progress_callback,
            runtime_control
        ));
    }));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_beginExtremePngSessionNative(
    JNIEnv* env,
    jobject,
    jstring task_id,
    jstring output_path,
    jint output_width,
    jint output_height,
    jint maximum_band_height,
    jintArray tile_configs
) {
    if (env == nullptr || task_id == nullptr || output_path == nullptr ||
        tile_configs == nullptr) {
        return 0;
    }
    uint64_t handle = 0u;
    try {
        throw_if_superres_jni_exception_pending(env);
        const jsize flat_count = env->GetArrayLength(tile_configs);
        throw_if_superres_jni_exception_pending(env);
        constexpr size_t kTileStride = 5u;
        if (flat_count <= 0 || flat_count % static_cast<jsize>(kTileStride) != 0) {
            return 0;
        }
        const size_t tile_count = static_cast<size_t>(flat_count) / kTileStride;
        size_t checked_flat_count = 0;
        if (!checked_extreme_tile_config_array_length(
                tile_count,
                kTileStride,
                checked_flat_count
            ) || checked_flat_count != static_cast<size_t>(flat_count)) {
            return 0;
        }

        std::vector<jint> flat(checked_flat_count);
        env->GetIntArrayRegion(tile_configs, 0, flat_count, flat.data());
        throw_if_superres_jni_exception_pending(env);
        std::vector<SuperResExtremePngTile> tiles;
        tiles.reserve(tile_count);
        for (size_t index = 0; index < tile_count; ++index) {
            const size_t base = index * kTileStride;
            tiles.push_back(SuperResExtremePngTile{
                static_cast<int>(flat[base]),
                static_cast<int>(flat[base + 1u]),
                static_cast<int>(flat[base + 2u]),
                static_cast<int>(flat[base + 3u]),
                static_cast<int>(flat[base + 4u])
            });
        }
        handle = begin_extreme_png_session(
            to_string(env, task_id),
            to_string(env, output_path),
            static_cast<int>(output_width),
            static_cast<int>(output_height),
            static_cast<int>(maximum_band_height),
            tiles
        );
        throw_if_superres_jni_exception_pending(env);
        if (handle == 0u ||
            handle > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
            abort_extreme_png_session(handle);
            return 0;
        }
        return static_cast<jlong>(handle);
    } catch (...) {
        if (handle != 0u) {
            abort_extreme_png_session(handle);
        }
        clear_superres_jni_exception_if_pending(env);
        return 0;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_processExtremePngSessionBitmapNative(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap,
    jint engine_type,
    jstring model_dir,
    jstring model_file_base,
    jint scale,
    jint noise,
    jint tile_size,
    jint gpu_headroom_percent,
    jint acceleration_mode,
    jboolean tta,
    jint output_mode,
    jint output_crop_left,
    jint output_crop_top,
    jint output_crop_width,
    jint output_crop_height,
    jint retry_count,
    jint region_index,
    jint native_backend,
    jint inference_precision,
    jint cpu_threads,
    jint openmp_blocktime_ms,
    jint postprocess_threads,
    jint command_batch_size,
    jint model_cache_capacity,
    jobject progress_sink
) {
    return static_cast<jint>(run_superres_bitmap_jni_boundary(env, [&]() -> int {
        if (handle <= 0) {
            return static_cast<int>(SuperResNativeCode::InvalidParams);
        }
        SuperResRgbImage image;
        const SuperResNativeCode conversionCode = bitmap_to_rgb(env, bitmap, image);
        if (conversionCode != SuperResNativeCode::Ok) {
            return static_cast<int>(conversionCode);
        }
        SuperResNativeParams params{
            "",
            "",
            "",
            static_cast<int>(engine_type),
            to_string(env, model_dir),
            to_string(env, model_file_base),
            static_cast<int>(scale),
            static_cast<int>(noise),
            static_cast<int>(tile_size),
            static_cast<int>(gpu_headroom_percent),
            static_cast<int>(acceleration_mode),
            tta == JNI_TRUE,
            static_cast<int>(output_mode),
            static_cast<int>(output_crop_left),
            static_cast<int>(output_crop_top),
            static_cast<int>(output_crop_width),
            static_cast<int>(output_crop_height),
            static_cast<int>(retry_count),
            static_cast<int>(region_index),
            static_cast<int>(native_backend),
            static_cast<int>(inference_precision),
            static_cast<int>(cpu_threads),
            static_cast<int>(openmp_blocktime_ms),
            static_cast<int>(postprocess_threads),
            static_cast<int>(command_batch_size),
            static_cast<int>(model_cache_capacity)
        };
        const SuperResProgressCallback progress_callback = [env, progress_sink](
            SuperResNativeStage stage,
            int current_tile,
            int total_tiles,
            float progress,
            const std::string& message,
            const SuperResNativePerformance& performance
        ) {
            emit_progress(
                env,
                progress_sink,
                stage,
                current_tile,
                total_tiles,
                progress,
                message,
                performance
            );
        };
        const SuperResRuntimeControlCallback runtime_control = [
            env,
            progress_sink,
            last_control = sanitize_runtime_control(
                SuperResRuntimeInferenceControl{params.commandBatchSize, 0, false, 1}
            )
        ]() mutable {
            SuperResRuntimeInferenceControl next_control;
            if (read_runtime_control(env, progress_sink, next_control)) {
                last_control = next_control;
            }
            return last_control;
        };
        return static_cast<int>(process_extreme_png_session_rgb(
            static_cast<uint64_t>(handle),
            params,
            std::move(image),
            progress_callback,
            runtime_control
        ));
    }));
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_finishExtremePngSessionNative(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    return static_cast<jint>(run_superres_process_jni_boundary(env, [handle]() {
        return handle > 0
            ? static_cast<int>(finish_extreme_png_session(static_cast<uint64_t>(handle)))
            : static_cast<int>(SuperResNativeCode::InvalidParams);
    }));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_abortExtremePngSessionNative(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    clear_superres_jni_exception_if_pending(env);
    if (handle > 0) {
        abort_extreme_png_session(static_cast<uint64_t>(handle));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_cancelNative(
    JNIEnv* env,
    jobject,
    jstring task_id
) {
    cancel_superres(to_string(env, task_id));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_lumasr_data_NativeRuntimeResourceJni_readGpuHeapBudgetMiB(
    JNIEnv*,
    jobject
) {
    return static_cast<jlong>(read_superres_gpu_heap_budget_mib());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_clearCacheNative(
    JNIEnv*,
    jobject
) {
    clear_superres_cache();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_lumasr_processor_JniNativeProcessBridge_mergeRawTilesToPngNative(
    JNIEnv* env,
    jobject,
    jstring output_path,
    jint output_width,
    jint output_height,
    jobjectArray tile_paths,
    jintArray tile_rects
) {
    if (tile_paths == nullptr || tile_rects == nullptr) {
        return static_cast<jint>(SuperResNativeCode::InvalidParams);
    }
    const jsize tile_count = env->GetArrayLength(tile_paths);
    const jsize rect_count = env->GetArrayLength(tile_rects);
    if (tile_count <= 0 || rect_count != tile_count * 4) {
        return static_cast<jint>(SuperResNativeCode::InvalidParams);
    }

    std::vector<jint> rects(static_cast<size_t>(rect_count));
    env->GetIntArrayRegion(tile_rects, 0, rect_count, rects.data());
    if (env->ExceptionCheck()) {
        return static_cast<jint>(SuperResNativeCode::InvalidParams);
    }

    std::vector<SuperResRawTile> tiles;
    tiles.reserve(static_cast<size_t>(tile_count));
    for (jsize i = 0; i < tile_count; ++i) {
        jstring path = static_cast<jstring>(env->GetObjectArrayElement(tile_paths, i));
        if (path == nullptr) {
            return static_cast<jint>(SuperResNativeCode::InvalidParams);
        }
        const size_t base = static_cast<size_t>(i) * 4;
        tiles.push_back(SuperResRawTile{
            to_string(env, path),
            static_cast<int>(rects[base]),
            static_cast<int>(rects[base + 1]),
            static_cast<int>(rects[base + 2]),
            static_cast<int>(rects[base + 3])
        });
        env->DeleteLocalRef(path);
    }

    return static_cast<jint>(merge_raw_tiles_to_png_streaming(
        to_string(env, output_path),
        static_cast<int>(output_width),
        static_cast<int>(output_height),
        tiles
    ));
}
