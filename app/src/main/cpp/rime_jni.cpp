// Rime JNI 桥接层
//
// 设计要点：
// 1. 本文件不直接链接 librime，而是运行时用 dlopen 打开 librime.so，
//    再通过 rime_get_api() 取函数表。这样即使设备上没有 librime，
//    本库也能正常加载，只是 isAvailable() 返回 false，由上层降级为「只写配置文件」。
// 2. 默认搜索路径与顺序：
//    a) 本 APK 的 nativeLibraryDir（把 librime.so 放进 jniLibs 时命中）
//    b) 小企鹅输入法的 nativeLibraryDir（通常不可访问，仅作兜底尝试）
//    实际路径由 Kotlin 侧探测后传入，避免 JNI 里硬编码包名。
// 3. 只暴露「初始化 / 部署 / 选择方案 / 查版本」四个能力，不参与按键处理，
//    避免与输入法自身的 librime 实例争抢用户目录（Rime 的 user data 不是多进程安全的）。

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "rime_api.h"

#define LOG_TAG "PtRimeJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

RimeApi* g_api = nullptr;
void* g_handle = nullptr;
bool g_initialized = false;
std::string g_shared_dir;
std::string g_user_dir;

// 依次尝试若干候选路径，返回第一个成功加载且能取到 API 表的句柄
bool LoadLibrime(const std::vector<std::string>& candidates) {
  if (g_api != nullptr) return true;
  for (const auto& path : candidates) {
    if (path.empty()) continue;
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
      LOGI("dlopen failed: %s (%s)", path.c_str(), dlerror());
      continue;
    }
    // 部分构建导出的是带 flavor 前缀的符号，两个都试
    auto get_api = reinterpret_cast<RimeApi* (*)()>(
        dlsym(handle, "rime_get_api"));
    if (get_api == nullptr) {
      get_api = reinterpret_cast<RimeApi* (*)()>(
          dlsym(handle, "RimeGetApi"));
    }
    if (get_api == nullptr) {
      LOGE("rime_get_api not found in %s", path.c_str());
      dlclose(handle);
      continue;
    }
    g_api = get_api();
    if (g_api == nullptr) {
      LOGE("rime_get_api returned null for %s", path.c_str());
      dlclose(handle);
      continue;
    }
    g_handle = handle;
    LOGI("librime loaded from %s", path.c_str());
    return true;
  }
  return false;
}

std::string ToString(JNIEnv* env, jstring value) {
  if (value == nullptr) return std::string();
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars == nullptr ? std::string() : std::string(chars);
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return result;
}

void FillTraits(RimeTraits* traits, const char* shared_dir, const char* user_dir) {
  RIME_STRUCT(RimeTraits, *traits);
  traits->shared_data_dir = shared_dir;
  traits->user_data_dir = user_dir;
  traits->distribution_name = "Rime";
  traits->distribution_code_name = "fcitx-rime";
  traits->distribution_version = "5.1.14";
  traits->app_name = "rime.ptfastrime";
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pt_fastrime_manager_rime_RimeNative_nativeLoad(
    JNIEnv* env, jobject /*thiz*/, jobjectArray candidatePaths) {
  std::vector<std::string> candidates;
  const jsize count = env->GetArrayLength(candidatePaths);
  for (jsize i = 0; i < count; ++i) {
    auto item = static_cast<jstring>(env->GetObjectArrayElement(candidatePaths, i));
    candidates.push_back(ToString(env, item));
    env->DeleteLocalRef(item);
  }
  return LoadLibrime(candidates) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pt_fastrime_manager_rime_RimeNative_nativeVersion(
    JNIEnv* env, jobject /*thiz*/) {
  if (g_api == nullptr) return env->NewStringUTF("");
  // RimeApi 里没有直接的版本号字段，用 schema_open 之外的配置接口拿不到，
  // 因此这里返回「已加载」标记，具体版本由 installation.yaml 提供。
  return env->NewStringUTF(g_initialized ? "librime-loaded" : "librime-available");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pt_fastrime_manager_rime_RimeNative_nativeInitialize(
    JNIEnv* env, jobject /*thiz*/, jstring sharedDir, jstring userDir) {
  if (g_api == nullptr || g_api->initialize == nullptr) return JNI_FALSE;
  if (g_initialized) return JNI_TRUE;

  g_shared_dir = ToString(env, sharedDir);
  g_user_dir = ToString(env, userDir);

  RimeTraits traits;
  FillTraits(&traits, g_shared_dir.c_str(), g_user_dir.c_str());
  g_api->initialize(&traits);
  g_initialized = true;
  LOGI("librime initialized: shared=%s user=%s", g_shared_dir.c_str(),
       g_user_dir.c_str());
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pt_fastrime_manager_rime_RimeNative_nativeDeploy(
    JNIEnv* env, jobject /*thiz*/) {
  if (g_api == nullptr || !g_initialized) return JNI_FALSE;
  // 完整部署：先尝试 maintenance，再执行 deploy。两者失败都视为失败。
  bool ok = true;
  if (g_api->start_maintenance != nullptr) {
    if (!g_api->start_maintenance(True)) {
      LOGE("start_maintenance failed");
      ok = false;
    } else if (g_api->join_maintenance_thread != nullptr) {
      g_api->join_maintenance_thread();
    }
  }
  if (g_api->deploy != nullptr) {
    if (!g_api->deploy()) {
      LOGE("deploy failed");
      ok = false;
    }
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pt_fastrime_manager_rime_RimeNative_nativeSelectSchema(
    JNIEnv* env, jobject /*thiz*/, jstring schemaId) {
  if (g_api == nullptr || !g_initialized || g_api->create_session == nullptr ||
      g_api->select_schema == nullptr) {
    return JNI_FALSE;
  }
  const std::string id = ToString(env, schemaId);
  RimeSessionId session = g_api->create_session();
  if (session == 0) {
    LOGE("create_session failed");
    return JNI_FALSE;
  }
  const Bool ok = g_api->select_schema(session, id.c_str());
  g_api->destroy_session(session);
  if (!ok) LOGE("select_schema failed: %s", id.c_str());
  return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pt_fastrime_manager_rime_RimeNative_nativeFinalize(
    JNIEnv* /*env*/, jobject /*thiz*/) {
  if (g_api != nullptr && g_initialized) {
    if (g_api->cleanup_all_sessions != nullptr) g_api->cleanup_all_sessions();
    if (g_api->finalize != nullptr) g_api->finalize();
    g_initialized = false;
  }
  if (g_handle != nullptr) {
    dlclose(g_handle);
    g_handle = nullptr;
    g_api = nullptr;
  }
}
