// vendor/<oem>/<device>/hello/service.cpp
// HAL 服务进程实现（NDK 后端，跑在 vendor 分区）
#define LOG_TAG "android.hardware.hello-service"
#include <aidl/android/hardware/hello/BnHello.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <binder/LazyServiceRegistrar.h>
#include <log/log.h>

using aidl::android::hardware::hello::BnHello;
using android::binder::Status;

namespace {

class HelloImpl : public BnHello {
public:
    Status getHello(std::string* _aidl_return) override {
        *_aidl_return = mMsg;
        return Status::ok();
    }
    Status setHello(const std::string& msg) override {
        mMsg = msg;
        return Status::ok();
    }
private:
    std::string mMsg = "hello from HAL";
};

}  // namespace

int main() {
    // NDK 后端默认连 /dev/binder 的 framework servicemanager（Treble 正确路径）
    ABinderProcess_setThreadPoolMaxThreadCount(4);

    std::shared_ptr<HelloImpl> service = ndk::SharedRefBase::make<HelloImpl>();

    // ===== 非 lazy：直接注册，进程常驻 =====
    // binder_status_t st = AServiceManager_addService(
    //         service->asBinder().get(),
    //         "android.hardware.hello.IHello/default");
    // if (st != STATUS_OK) {
    //     ALOGE("Failed to register HAL service: %d", st);
    //     return 1;
    // }

    // ===== lazy HAL（车载省电，推荐）：引用归零自动退出 =====
    // 需 rc 文件配合 interface aidl + disabled + oneshot
    auto registrar = android::binder::LazyServiceRegistrar::getInstance();
    if (!registrar.registerService(service,
            "android.hardware.hello.IHello/default").isOk()) {
        ALOGE("Failed to register lazy HAL service");
        return 1;
    }

    ABinderProcess_joinThreadPool();
    return 0;
}
