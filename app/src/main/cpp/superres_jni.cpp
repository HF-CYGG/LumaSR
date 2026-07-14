#include "superres_engine.h"

#include <android/bitmap.h>
#include <cstdint>
#include <jni.h>
#include <string>
#include <vector>

namespace {
std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
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
    if (progress_sink == nullptr || env->ExceptionCheck()) {
        return;
    }

    jclass sink_class = env->GetObjectClass(progress_sink);
    if (sink_class == nullptr) {
        return;
    }

    jmethodID method = env->GetMethodID(
        sink_class,
        "onProgress",
        "(IIIFLjava/lang/String;ZJJJJJJJZIIIIIJJJJJJIII)V"
    );
    env->DeleteLocalRef(sink_class);
    if (method == nullptr || env->ExceptionCheck()) {
        return;
    }

    jstring j_message = env->NewStringUTF(message.c_str());
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
        static_cast<jint>(performance.fallbackReason)
    );
    env->DeleteLocalRef(j_message);
}

bool bitmap_to_rgb(JNIEnv* env, jobject bitmap, std::vector<unsigned char>& rgb, int& width, int& height) {
    if (bitmap == nullptr) {
        return false;
    }
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.width <= 0 || info.height <= 0) {
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 &&
        info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        return false;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return false;
    }

    bool ok = true;
    try {
        width = static_cast<int>(info.width);
        height = static_cast<int>(info.height);
        rgb.resize(static_cast<size_t>(width) * height * 3);
        for (int y = 0; y < height; ++y) {
            const unsigned char* src = static_cast<const unsigned char*>(pixels) + static_cast<size_t>(y) * info.stride;
            for (int x = 0; x < width; ++x) {
                const size_t dst = (static_cast<size_t>(y) * width + x) * 3;
                if (info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
                    const unsigned char* px = src + static_cast<size_t>(x) * 4;
                    rgb[dst] = px[0];
                    rgb[dst + 1] = px[1];
                    rgb[dst + 2] = px[2];
                } else {
                    const uint16_t value = reinterpret_cast<const uint16_t*>(src)[x];
                    rgb[dst] = static_cast<unsigned char>(((value >> 11) & 0x1f) * 255 / 31);
                    rgb[dst + 1] = static_cast<unsigned char>(((value >> 5) & 0x3f) * 255 / 63);
                    rgb[dst + 2] = static_cast<unsigned char>((value & 0x1f) * 255 / 31);
                }
            }
        }
    } catch (...) {
        ok = false;
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok;
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
    jint command_batch_size,
    jint model_cache_capacity,
    jobject progress_sink
) {
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
        static_cast<int>(command_batch_size),
        static_cast<int>(model_cache_capacity)
    };
    return static_cast<jint>(process_superres(
        params,
        [env, progress_sink](
            SuperResNativeStage stage,
            int current_tile,
            int total_tiles,
            float progress,
            const std::string& message,
            const SuperResNativePerformance& performance
        ) {
            emit_progress(env, progress_sink, stage, current_tile, total_tiles, progress, message, performance);
        }
    ));
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
    jint command_batch_size,
    jint model_cache_capacity,
    jobject progress_sink
) {
    std::vector<unsigned char> rgb;
    int width = 0;
    int height = 0;
    if (!bitmap_to_rgb(env, bitmap, rgb, width, height)) {
        return static_cast<jint>(SuperResNativeCode::InvalidParams);
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
        static_cast<int>(command_batch_size),
        static_cast<int>(model_cache_capacity)
    };
    return static_cast<jint>(process_superres_rgb(
        params,
        width,
        height,
        rgb.data(),
        rgb.size(),
        [env, progress_sink](
            SuperResNativeStage stage,
            int current_tile,
            int total_tiles,
            float progress,
            const std::string& message,
            const SuperResNativePerformance& performance
        ) {
            emit_progress(env, progress_sink, stage, current_tile, total_tiles, progress, message, performance);
        }
    ));
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
