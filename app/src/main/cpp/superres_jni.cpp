#include "superres_engine.h"

#include <jni.h>
#include <string>

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
    const std::string& message
) {
    if (progress_sink == nullptr || env->ExceptionCheck()) {
        return;
    }

    jclass sink_class = env->GetObjectClass(progress_sink);
    if (sink_class == nullptr) {
        return;
    }

    jmethodID method = env->GetMethodID(sink_class, "onProgress", "(IIIFLjava/lang/String;)V");
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
        j_message
    );
    env->DeleteLocalRef(j_message);
}
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
    jint acceleration_mode,
    jboolean tta,
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
        static_cast<int>(acceleration_mode),
        tta == JNI_TRUE
    };
    return static_cast<jint>(process_superres(
        params,
        [env, progress_sink](
            SuperResNativeStage stage,
            int current_tile,
            int total_tiles,
            float progress,
            const std::string& message
        ) {
            emit_progress(env, progress_sink, stage, current_tile, total_tiles, progress, message);
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
