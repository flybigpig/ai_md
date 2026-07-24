#include "Led.h"
#include <android/binder_process.h>
#include <android/binder_manager.h>

using aidl::android::hardware::led::Led;
using aidl::android::hardware::led::ILed;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(1);

    auto led = ndk::SharedRefBase::make<Led>();
    std::string name = std::string(ILed::descriptor) + "/default";
    // AIDL HAL 注册到标准 servicemanager（域 /dev/binder），非 hwservicemanager
    AIBinder* binder = led->asBinder().get();
    AServiceManager_addService(binder, name.c_str());

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;  // 不会走到这
}
