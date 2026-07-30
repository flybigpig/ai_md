#define LOG_TAG "DemoHal"

#include "Demo.h"

#include <android-base/logging.h>
#include <android/log.h>

#include <mutex>

namespace aidl {
namespace android {
namespace hardware {
namespace demo {

ndk::ScopedAStatus Demo::getCount(int32_t* _aidl_return) {
    std::lock_guard<std::mutex> lk(mLock);
    *_aidl_return = mCount;
    ALOGI("getCount() -> %d", mCount);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Demo::setValue(int32_t in_value, DemoStatus* _aidl_return) {
    {
        std::lock_guard<std::mutex> lk(mLock);
        mCount = in_value;
    }
    _aidl_return->value = in_value;
    _aidl_return->ok = true;
    _aidl_return->description = "set ok from HAL";
    ALOGI("setValue(%d)", in_value);

    // 演示 HAL 主动上报:通过已注册的回调把事件推给 Framework 层
    std::shared_ptr<IDemoCallback> cb;
    {
        std::lock_guard<std::mutex> lk(mLock);
        cb = mCallback;
    }
    if (cb) {
        cb->onEvent(1, "value changed to " + std::to_string(in_value));
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Demo::setCallback(
    const std::shared_ptr<IDemoCallback>& in_cb) {
    std::lock_guard<std::mutex> lk(mLock);
    mCallback = in_cb;
    ALOGI("setCallback() registered");
    return ndk::ScopedAStatus::ok();
}

}  // namespace demo
}  // namespace hardware
}  // namespace android
}  // namespace aidl
