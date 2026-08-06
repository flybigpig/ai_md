# App 调用 HAL（Android 14 / API 34）

> 承接 `vendor-hal-android14.md` 与 `hal-selinux-policy-android14.md`。HAL 已在 vendor 分区跑起来并注册到 servicemanager；本文讲**最末端：App 怎么调到它**。覆盖两条路径（经 system_server 中转 / App 直连 HAL）、每段真实代码、以及**三道 SELinux 闸 + 权限 + hidden API** 的完整闭环。以 `android-14.0.0_rXX` 为准。

---

## 一、结论前置

1. **推荐路径是「App → Framework Manager → system_server → HAL」四段式**。App 永远不直接碰 HAL：先拿 framework 的 Manager（@hide），Manager 经 Binder 调 system_server 里包裹 HAL 的 Service，该 Service 再经 Binder 调 vendor HAL。
2. 这条链路里有 **两个 AIDL 接口**，别搞混：
   - **HAL AIDL** `android.hardware.hello.IHello` —— vendor 侧，NDK 后端，App/framework 一般不直连。
   - **Service AIDL** `android.os.IHelloService` —— framework 侧，java 后端，是 App/Manager 与 system_server 的契约。
3. **SELinux 有三道闸**：App→system_server、system_server→HAL（上篇已讲）、以及（若直连）App→HAL。普通 App 直连 HAL 会被 Treble `neverallow` + 缺域权限双重拦死，**几乎只能走中转**。
4. **隐藏 API 问题**：自定义 Manager 与 `Context.HELLO` 常量是 `@hide`，系统/特权 App 用 `platform_apis:true` 编进 tree 才能引用；外部 App 需走 cookbook §6.2 的 hiddenapi 豁免（不推荐量产用）。

---

## 二、两条路径总览

```
【推荐 Path A】App → Manager(@hide) → system_server(IHelloService) → HAL(IHello)
   App 进程          framework            system_server 进程          vendor HAL 进程
   getSystemService → HelloManager ─Binder─→ HelloService ─Binder─→ BnHello

【受限 Path B】App → HAL 直连
   App 进程 ─Binder─→ BnHello (vendor HAL)
   仅限 system/privileged 域 + 明确 sepolicy，且绕过 framework 管控，车载量产不推荐
```

---

## 三、Path A 详解（四段 + 每跳 SELinux + 权限）

### 3.1 HAL 侧（已在 vendor 跑起来，回顾）
- `BnHello` 实现 `android.hardware.hello.IHello`，注册名 `android.hardware.hello.IHello/default`（见 `vendor-hal` 文档）。
- SELinux：`hal_hello_default` 域，`binder_use` + `allow ...:service_manager { add }` + `binder_call(system_server, hal_hello_default)`（上篇 §3.1）。

### 3.2 system_server 侧：`HelloService`（包裹 HAL）
> ⚠️ 这里就是《AOSP14 添加 HAL 文档》我标 🔴 的坑：**绝不在构造里 `waitForService(HAL)`**（会无限等、阻塞整机启动）。改用懒连接 + `linkToDeath` 重连。

```java
// frameworks/base/services/core/java/com/android/server/hello/HelloService.java
public class HelloService extends SystemService {
    private final IHelloService.Stub mStub = new IHelloService.Stub() {
        @Override
        public String getHello() throws RemoteException {
            IHello hal = getHal();                 // 懒取 HAL 代理
            if (hal == null) throw new RemoteException("HAL not ready");
            return hal.getHello();                 // ← 第二道 Binder：调 HAL
        }
    };
    private IHello mHal;
    private final IBinder.DeathRecipient mDeath = () -> { mHal = null; }; // HAL 死后置空，下次重连

    public HelloService(Context ctx) { super(ctx); }

    @Override
    public void onStart() {
        // 只注册 framework service，不在这里等 HAL
        publishBinderService(Context.HELLO_SERVICE, mStub);   // = ServiceManager.addService("hello", mStub)
    }

    private IHello getHal() {
        if (mHal == null) {
            IBinder b = ServiceManager.getService("android.hardware.hello.IHello/default");
            if (b != null) {
                mHal = IHello.Stub.asInterface(b);   // AIDL stable：asInterface
                try { mHal.asBinder().linkToDeath(mDeath, 0); } catch (RemoteException ignored) {}
            }
        }
        return mHal;
    }
}
```
- 在 `SystemServer.java` `startOtherServices()` 启动：
  ```java
  mSystemServiceManager.startService(HelloService.class);
  ```
- **第二道 SELinux 闸**（system_server → HAL）：见上篇 `binder_call(system_server, hal_hello_default)` + `allow system_server hal_hello_service:service_manager find`。缺了这里，system_server 调 HAL 会 avc denied（`tclass=binder` 或 `service_manager`）。

### 3.3 Framework Manager（`@hide`，系统侧门面）
```java
// frameworks/base/core/java/android/hello/HelloManager.java
public class HelloManager {
    private final IHelloService mService;
    public HelloManager(IHelloService service) { mService = service; }

    @RequiresPermission(android.Manifest.permission.HELLO_ACCESS)   // ← 权限闸门
    public String getHello() {
        try { return mService.getHello(); }
        catch (RemoteException e) { throw e.rethrowFromSystemServer(); }
    }
}
```
- 在 `SystemServiceRegistry` 注册，使 `getSystemService` 能拿到：
  ```java
  // frameworks/base/core/java/android/app/SystemServiceRegistry.java
  registerService(Context.HELLO_SERVICE, HelloManager.class,
      new CachedServiceFetcher<HelloManager>() {
          @Override public HelloManager createService(ContextImpl ctx) {
              IBinder b = ServiceManager.getService(Context.HELLO_SERVICE);
              return new HelloManager(IHelloService.Stub.asInterface(b));
          }
      });
  ```
- `Context.HELLO_SERVICE` 字符串常量（`"hello"`）定义在 `Context.java`（@hide）。
- Service 端权限校验（在 `IHelloService.Stub` 方法里 `enforcePermission` 或 `checkCallingPermission(HELLO_ACCESS)`），确保只有授权 App 能调。

### 3.4 App 侧（系统/特权 App）
```java
// 系统/特权 App（编进 tree，platform_apis:true 可见 @hide）
HelloManager mgr = (HelloManager) getSystemService(Context.HELLO_SERVICE);
String s = mgr.getHello();
```
- App 的 `Android.bp`：
  ```bp
  android_app {
      name: "HelloClientApp",
      platform_apis: true,        // 关键：可见 framework @hide API
      privileged: true,           // 若需特权权限
      srcs: ["src/**/*.java"],
      // 不要把 HAL 的 java 后端直接编进 app（Path A 不需要）
  }
  ```
- 若 App 是 **第三方/外部**，看不到 `@hide` Manager，只能走 hiddenapi 豁免（cookbook §6.2：`-greylist-max-o.txt` / `VMRuntime.setHiddenApiExemptions` / `@UnsupportedAppUsage`）——**量产不推荐**，且仍受权限 + SELinux 制约。

### 3.5 第一道 SELinux 闸（App → system_server）
- App 调 `getSystemService` → 内部 `ServiceManager.getService("hello")` → App 域需 `find` 该服务：
  ```te
  # 服务类型定义（framework sepolicy，public/service.te 衍生的 system_api_service）
  type hello_service, system_api_service;   # 而非 app_api_service（后者任意 app 可 find，暴露面过大）
  # 系统/特权 app 域对 system_api_service 的 find 由 framework 既有规则授予
  ```
- **权限闸门**在 service 方法里：`HELLO_ACCESS` 定义为 `signature|privileged` 权限，普通 App 调用会被 `SecurityException` 拦。
- 若 App 是非系统域且需直连（极少见），要显式：
  ```te
  allow <app_domain> hello_service:service_manager find;
  binder_call(<app_domain>, system_server);
  ```

---

## 四、Path B 详解：App 直连 HAL（何时用、怎么配）

仅当 App 是 **system/privileged 域**且确有绕过 framework 的低延迟需求（车载某些实时控制偶尔如此）才考虑。

```java
// 需 HAL 的 java 后端编进 app（Android.bp: libs: ["android.hardware.hello-V1-java"]）
IBinder b = ServiceManager.getService("android.hardware.hello.IHello/default"); // 本身 @hide
IHello hal = IHello.Stub.asInterface(b);
String s = hal.getHello();
```
SELinux（第三道闸）：
```te
allow <app_domain> hal_hello_service:service_manager find;
binder_call(<app_domain>, hal_hello_default);
```
限制：
- App 必须是特权/system 域（普通 app 域会被 `neverallow appdomain hal_<x>_default:binder` 拦）。
- 失去 framework 的权限/配额/统一管控，且 HAL 版本演进要 App 跟着重编。
- **车载量产默认走 Path A**；Path B 仅用于调试或极特殊低延迟场景，且必须在设计文档里说明。

---

## 五、AIDL 后端编译（java backend 去哪）

- `aidl_interface` 的 `java: { enabled: true }` 生成 `android.hardware.hello-V1-java`（HAL AIDL java 桩）。
- **Path A**：App/framework 只需 `IHelloService` 的 java 桩（framework 内置）；HAL 的 java 后端只在 system_server 侧 `getHal()` 用到（framework 编进 system_server，无需 app 自带）。
- **Path B**：App 需 `libs: ["android.hardware.hello-V1-java"]` 才能 `asInterface`。
- NDK 后端（`-ndk`）给 vendor HAL 进程用，与 java 后端互不相干但接口二进制兼容（AIDL stable）。

---

## 六、权限模型（Manager 暴露面）

| 元素 | 推荐定义 | 作用 |
|---|---|---|
| `HELLO_ACCESS` 权限 | `<permission android:name="android.permission.HELLO_ACCESS" android:protectionLevel="signature\|privileged"/>` | 限制仅系统/签名特权 App 调 Manager API |
| `hello_service` 类型 | `system_api_service`（非 `app_api_service`） | 限制仅系统 App 能 `find` 该 binder 服务 |
| App 声明 | `<uses-permission android:name="android.permission.HELLO_ACCESS"/>` + `privileged:true` | App 侧申请 |
| service 方法校验 | `enforceCallingPermission(HELLO_ACCESS)` | 运行时兜底拦截未授权调用 |

> 呼应《AOSP14 添加 HAL 文档》🟡 点：原文把 `hello_service` 标成 `app_api_service`（任意 app 可直连），量产应收紧为 `system_api_service` + 显式 permission。

---

## 七、Hidden API / @hide 处理（衔接 cookbook §6.2）

自定义 Manager 与 `Context.HELLO_SERVICE` 都是 `@hide`，三类解法：
1. **编进 tree 的系统/特权 App（`platform_apis:true`）** —— 直接引用，最干净，车载定制首选。
2. **`-greylist-max-o.txt`** 把符号加入框架灰名单，允许特定 App 访问。
3. **`VMRuntime.setHiddenApiExemptions`** / `@UnsupportedAppUsage` —— 运行时豁免，调试用，量产有合规风险。

---

## 八、排障

| 现象 | 根因层 | 排查 |
|---|---|---|
| `getSystemService` 返回 null | 服务没注册 / `Context.HELLO_SERVICE` 常量错 | `adb shell service list \| grep hello`；查 `SystemServer.startOtherServices` 是否调了 startService |
| `SecurityException: Permission` | 权限闸门 | App 是否声明 `HELLO_ACCESS` + `privileged`；service 方法是否 `enforcePermission` |
| `RemoteException: HAL not ready` | system_server 侧 `getHal()` 拿不到 HAL 代理 | `service list \| grep android.hardware.hello`；查 HAL 进程是否起、VINTF 是否声明 |
| `DeadObjectException` | HAL 进程崩溃/重启 | 查 HAL 日志 + `linkToDeath` 是否置空重连（3.2 已处理） |
| avc denied `tclass=binder` | 某跳缺 `binder_call` | `dmesg \| grep avc`，定位 scontext/tcontext 落在哪道闸 |
| avc denied `tclass=service_manager perm=find` | 服务名类型标错/缺 find | 查对应 `*_service` 类型 + `service_contexts`（上篇） |
| App 编译找不到 `HelloManager` | hidden API | `platform_apis:true` 或 greylist |

---

## 九、速查表（App→HAL 最小正确集）

| 段 | 产物 | 关键 |
|---|---|---|
| HAL | vendor AIDL | 上两篇（注册名、sepolicy、VINTF） |
| Service AIDL | `android.os.IHelloService` | java 后端，@hide，权限校验 |
| system_server | `HelloService extends SystemService` | `publishBinderService`；**懒连接 + linkToDeath**（修 🔴） |
| 注册 | `SystemServer` + `SystemServiceRegistry` | `startService` + `registerService` |
| Manager | `HelloManager` | @hide，`enforcePermission` |
| App | `platform_apis:true` 系统 App | `getSystemService` + 声明权限 |
| SELinux | 三道闸 | App→ss(`system_api_service`+find)、ss→HAL(上篇)、直连(App→HAL 极少见) |
| 权限 | `HELLO_ACCESS` signature\|privileged | 暴露面收紧 |

---

## 十、与全系列衔接

- **`hal-selinux-policy-android14.md`**：第二道闸（system_server→HAL）的全部 sepolicy 在此。
- **`vendor-hal-android14.md`**：HAL 注册名 / VINTF / `isDeclared` 在此；本文 `getHal()` 拿不到代理时，先回那篇查 VINTF + service_contexts。
- **《AOSP14 添加 HAL 文档》**：其 `HelloService`/`HelloManager` 结构正确，但构造里 `waitForService(HAL)` 是 🔴 阻塞 boot，本文 §3.2 给了懒连接修法；`hello_service` 用 `app_api_service` 过宽，本文 §六建议收紧。
- **《Android System Development Cookbook》**：§6.2 hiddenapi 三法（本文 §七）；其「系统服务调 HAL」思路对，但缺 SELinux 三道闸与权限模型细节。
- **servicemanager C++ 版（第二轮）**：`ServiceManager.getService("android.hardware.hello.IHello/default")` 落地的正是 servicemanager 的 `checkService` + `Access::canFind` 校验（上篇 §10.4）。
