# Preserve JNI methods and native class structures
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.antigravity.deadframeremover.engine.** {
    *;
}
