# Keep JNI entry points and native callbacks stable for the C++ bridge.
-keep class com.lumasr.processor.JniNativeProcessBridge {
    private native <methods>;
}

-keepclassmembers class com.lumasr.processor.NativeProgressSink {
    public void onProgress(int, int, int, float, java.lang.String);
}
