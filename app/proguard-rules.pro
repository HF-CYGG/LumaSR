# Keep JNI entry points and native callbacks stable for the C++ bridge.
-keep class com.lumasr.processor.JniNativeProcessBridge {
    private native <methods>;
}

-keep class com.lumasr.processor.NativeProgressSink {
    *;
}

# Native capability JNI uses name-based binding and constructs the snapshot via FindClass/GetMethodID.
-keep class com.lumasr.data.NativeCapabilityJni {
    native <methods>;
}

-keep class com.lumasr.data.NativeCapabilitySnapshot {
    public <init>(java.lang.Integer,java.lang.Integer,java.lang.Integer,java.lang.String,java.lang.String,java.lang.String,java.lang.Integer,long,long,long,long,long,java.lang.String,int,int,int,int,long,boolean,boolean,boolean,boolean,boolean,boolean,boolean,boolean,boolean,boolean);
}

# Optional QAIRT adapter uses name-based JNI only when the external QNN package is enabled.
-keep class com.lumasr.data.QnnCapabilityJni {
    native <methods>;
}

-keep class com.lumasr.data.NativeQnnCapabilityRecord {
    public <init>(java.lang.String,java.lang.String,boolean,boolean);
}

-keep class com.lumasr.processor.JniQnnProcessBridge {
    private native <methods>;
}

-keep class com.lumasr.processor.QnnProgressSink {
    *;
}
