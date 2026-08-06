// hardware/interfaces/hello/aidl/android/hardware/hello/IHello.aidl
// HAL AIDL 接口（NDK 后端，vendor 侧实现）
package android.hardware.hello;

interface IHello {
    // 取当前问候语
    String getHello();
    // 设置问候语
    void setHello(in String msg);
}
