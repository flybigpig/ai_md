#define LOG_TAG "DemoHal"

#include "Demo.h"

#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/log.h>

using aidl::android::hardware::demo::Demo;
using aidl::android::hardware::demo::IDemo;

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(1);

    std::shared_ptr<Demo> demo = ndk::SharedRefBase::make<Demo>();

    // AIDL HAL 实例名规范: <descriptor>/default
    std::string instance = std::string(IDemo::descriptor) + "/default";
    binder_exception_t err =
        AServiceManager_addService(demo->asBinder().get(), instance.c_str());
    if (err != EX_NONE) {
        ALOGE("Failed to register demo HAL service (%d)", err);
        return 1;
    }
    ALOGI("demo HAL service registered: %s", instance.c_str());

    ABinderProcess_joinThreadPool();
    return 0;  // 不会走到这里
}
