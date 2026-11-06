# JNI 入口与 Shizuku AIDL 接口必须保留
-keep class io.github.lianxia233.shizukuinst.RustCore { *; }
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn moe.shizuku.server.**
