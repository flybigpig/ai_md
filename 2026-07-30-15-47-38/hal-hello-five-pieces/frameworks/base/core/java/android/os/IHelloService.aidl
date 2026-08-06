// frameworks/base/core/java/android/os/IHelloService.aidl
// Service AIDL 接口（java 后端）：App/Manager 与 system_server 的契约
// 注意：这是 framework 侧服务契约，与 HAL 的 android.hardware.hello.IHello 是两个不同接口
package android.os;

interface IHelloService {
    String getHello();
    void setHello(in String msg);
}
