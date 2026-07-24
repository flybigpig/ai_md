# 新增纯系统服务（含 AIDL）深读笔记（AOSP 14）

## 1. 总体链路
AIDL 定义接口 → 服务端 `extends IMyService.Stub`（常同时 `extends SystemService`）→ `SystemServer` 里 `ServiceManager.addService()` 注册 → 客户端 `asInterface(ServiceManager.getService())`。

## 2. 定义 AIDL
`frameworks/base/core/java/android/os/IMyService.aidl`：
```aidl
package android.os;
/** @hide */
interface IMyService {
    void doSomething(String arg) throws RemoteException;
}
```
内部接口加 `/** @hide */`；若要进公开 SDK 则去掉 `@hide` 并走 `api` 审核（`make update-api`）。

## 3. 实现
`frameworks/base/services/core/java/com/android/server/MyService.java`：
```java
public class MyService extends SystemService {
    private final IMyService.Stub mBinder = new IMyService.Stub() {
        @Override
        public void doSomething(String arg) {
            enforcePermission();          // 校验调用方权限
            // ... 业务逻辑 ...
        }
    };
    public MyService(Context c) { super(c); }
    @Override public void onStart() {
        publishBinderService(Context.MY_SERVICE, mBinder); // 内部调 ServiceManager.addService
    }
}
```

## 4. 注册
在 `SystemServer`（重要性高放 `startBootstrapServices`，普通放 `startOtherServices`）：
```java
mMyService = new MyService(context);
ServiceManager.addService(Context.MY_SERVICE, mMyService);
// 或走生命周期: mSystemServiceManager.startService(MyService.class);
```
并在 `Context.java` 加 `public static final String MY_SERVICE = "myservice";`，`ContextImpl.getSystemService()` 里 case 返回封装 manager。

## 5. 权限校验
每个方法里 `mContext.enforceCallingPermission(android.Manifest.permission.MY_PERM, msg)`；权限在 `frameworks/base/core/res/AndroidManifest.xml` 定义 `<permission>`。

## 6. SELinux
见 `selinux_policy.md`：`service_contexts` 加 `myservice u:object_r:myservice_service:s0` + `.te` allow。

## 7. 客户端调用
```java
IBinder b = ServiceManager.getService(Context.MY_SERVICE);
IMyService svc = IMyService.Stub.asInterface(b);
svc.doSomething("hi");
```

## 8. 验证
```bash
adb shell service list | grep myservice
# 实现 dump() 后:
adb shell dumpsys myservice
```

## 9. 实战小项目
把 HAL-AIDL(`hal_led_example`) 的思路升级：做一个 Java 系统服务 `IMyService`，暴露一个方法给 app 查询"当前是否充电"，`addService` 注册并补 SELinux。
