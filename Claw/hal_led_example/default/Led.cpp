#include "Led.h"
#include <android/log.h>

#define LOG_TAG "LedHal"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace aidl::android::hardware::led {

ndk::ScopedAStatus Led::setBrightness(int brightness) {
    if (brightness < 0 || brightness > 255)
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);

    mBrightness = brightness;
    ALOGI("setBrightness -> %d", mBrightness);

    // 真实设备：这里 ioctl(/dev/led, ...) 或写 sysfs
    if (mCallback) mCallback->onBrightnessChanged(mBrightness);
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Led::getBrightness(int* _aidl_return) {
    *_aidl_return = mBrightness;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Led::registerCallback(
        const std::shared_ptr<ILedCallback>& callback) {
    mCallback = callback;
    return ndk::ScopedAStatus::ok();
}

}  // namespace aidl::android::hardware::led
