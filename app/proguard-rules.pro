# Keep JNI entry points and native callbacks stable for the C++ bridge.
-keep class com.lumasr.processor.JniNativeProcessBridge {
    private native <methods>;
}

-keep class com.lumasr.processor.NativeProgressSink {
    *;
}
