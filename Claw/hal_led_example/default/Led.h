#pragma once
#include <aidl/android/hardware/led/BnLed.h>
#include <android/binder_interface_utils.h>

namespace aidl::android::hardware::led {

class Led : public BnLed {
  public:
    ndk::ScopedAStatus setBrightness(int brightness) override;
    ndk::ScopedAStatus getBrightness(int* _aidl_return) override;
    ndk::ScopedAStatus registerCallback(
        const std::shared_ptr<ILedCallback>& callback) override;

  private:
    int mBrightness = 0;
    std::shared_ptr<ILedCallback> mCallback;
};

}  // namespace aidl::android::hardware::led
