#pragma once

#include <android/binder_status.h>
#include <android/hardware/demo/BnDemo.h>

#include <memory>

namespace aidl {
namespace android {
namespace hardware {
namespace demo {

class Demo : public BnDemo {
   public:
    ndk::ScopedAStatus getCount(int32_t* _aidl_return) override;
    ndk::ScopedAStatus setValue(int32_t in_value, DemoStatus* _aidl_return) override;
    ndk::ScopedAStatus setCallback(
        const std::shared_ptr<IDemoCallback>& in_cb) override;

   private:
    std::mutex mLock;
    int32_t mCount GUARDED_BY(mLock) = 0;
    std::shared_ptr<IDemoCallback> mCallback GUARDED_BY(mLock);
};

}  // namespace demo
}  // namespace hardware
}  // namespace android
}  // namespace aidl
