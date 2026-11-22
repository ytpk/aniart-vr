#pragma once

#include <android/log.h>
#include <cinttypes>
#include <dlfcn.h>
#include <jni.h>

namespace QnnHtpProbe {
// Stable prefix of the public QnnInterface_t v2 ABI. The bundled QAIRT
// exposes the function table through QnnInterface_getProviders.
using Error = uint64_t;
struct Version {
    uint32_t major, minor, patch;
};
struct BackendConfig;
using Slot = void (*)();
struct Interface {
    uint32_t backendId;
    const char* name;
    Version core, backend;
    Slot propertyHasCapability;
    Error (*backendCreate)(void* logger, const BackendConfig** config, void** handle);
    Slot backendSetConfig, backendGetApiVersion, backendGetBuildId;
    Slot backendRegisterOpPackage, backendGetSupportedOperations, backendValidateOpConfig;
    Error (*backendFree)(void* handle);
};
using GetProviders = Error (*)(const Interface***, uint32_t*);

constexpr auto tag = "AniartDepth";

inline bool run(const char* path) {
    void* rpc = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (rpc) {
        __android_log_print(ANDROID_LOG_INFO, tag, "QNN direct libcdsprpc: load OK");
        dlclose(rpc);
    } else {
        __android_log_print(ANDROID_LOG_WARN, tag, "QNN direct libcdsprpc: %s", dlerror());
    }

    // Retain the library for the process lifetime. It can own FastRPC state
    // which the subsequent ONNX Runtime tests may also use.
    static void* library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        __android_log_print(ANDROID_LOG_WARN, tag, "QNN direct dlopen: %s", dlerror());
        return false;
    }
    auto getProviders = reinterpret_cast<GetProviders>(dlsym(library, "QnnInterface_getProviders"));
    if (!getProviders) {
        __android_log_print(ANDROID_LOG_WARN, tag, "QNN direct dlsym: %s", dlerror());
        return false;
    }

    const Interface** providers = nullptr;
    uint32_t count = 0;
    const auto status = getProviders(&providers, &count);
    __android_log_print(ANDROID_LOG_INFO, tag, "QNN direct getProviders: status=%" PRIu64 " count=%u", status, count);
    if (status || !providers) return false;
    for (uint32_t i = 0; i < count; ++i) {
        const auto* provider = providers[i];
        if (!provider || provider->core.major != 2 || !provider->backendCreate || !provider->backendFree) continue;
        __android_log_print(ANDROID_LOG_INFO, tag, "QNN direct provider %u | core API %u.%u.%u",
                            provider->backendId, provider->core.major, provider->core.minor, provider->core.patch);
        void* backend = nullptr;
        const auto created = provider->backendCreate(nullptr, nullptr, &backend);
        __android_log_print(ANDROID_LOG_INFO, tag, "QNN direct backendCreate: status=%" PRIu64 " handle=%s",
                            created, backend ? "yes" : "no");
        if (created || !backend) return false;
        const auto freed = provider->backendFree(backend);
        __android_log_print(ANDROID_LOG_INFO, tag, "QNN direct backendFree: status=%" PRIu64, freed);
        return freed == 0;
    }
    __android_log_print(ANDROID_LOG_WARN, tag, "QNN direct: no compatible QNN v2 provider");
    return false;
}
} // namespace QnnHtpProbe

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_aniart_vr_DepthModels_runDirectHtp(JNIEnv* env, jclass, jstring path) {
    const char* nativePath = env->GetStringUTFChars(path, nullptr);
    if (!nativePath) return JNI_FALSE;
    const bool passed = QnnHtpProbe::run(nativePath);
    env->ReleaseStringUTFChars(path, nativePath);
    return passed ? JNI_TRUE : JNI_FALSE;
}
