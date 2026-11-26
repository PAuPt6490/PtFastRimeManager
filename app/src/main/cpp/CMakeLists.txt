# Rime JNI 桥接库构建脚本
#
# 注意：本工程默认 **不启用** externalNativeBuild，原因见 README「关于 JNI」一节。
# 只有在你本机装好 NDK 并显式传入 -PenableNativeRime=true 时才会走到这里：
#   .\gradlew assembleDebug -PenableNativeRime=true
#
# 同时需要把 librime.so（arm64-v8a）放到 app/src/main/jniLibs/arm64-v8a/，
# 否则库能编译出来，但运行时 nativeLoad 会失败并自动降级为「仅写配置文件」。

cmake_minimum_required(VERSION 3.22.1)

project(pt_rime_jni CXX)

add_library(pt_rime_jni SHARED rime_jni.cpp)

target_compile_features(pt_rime_jni PRIVATE cxx_std_17)

# librime 未随工程分发，这里不 link 它，运行时 dlopen。
target_link_libraries(pt_rime_jni PRIVATE log dl)
