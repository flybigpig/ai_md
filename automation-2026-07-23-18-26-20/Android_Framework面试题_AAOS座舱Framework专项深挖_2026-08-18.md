# Android Framework 面试题 · AAOS 座舱 Framework 专项深挖（CarService / Vehicle HAL / 多显示屏 / 车载音频焦点 / 仪表盘渲染 / 座舱交互）（2026-08-18）

> 系列第 **33 篇** / 累计约 **213 专题**。
> 落点：把被点到却从未独立成篇的 **AAOS 座舱主线** 焊成一条端到端链路 —— 从手机 Android 与车载 Android 的根本差异（多用户 + 多显示 + 多音区 + 整车电源），到 `CarService` 连接生命周期、`Vehicle HAL` 的发布-订阅双向通道、车载多显示屏的「乘员分区」模型、多音区音频焦点仲裁、仪表盘渲染与座舱交互输入。它正好命中用户显式列出的 WMS 多显示 / HAL / kernel 衔接，且与 8/17（HAL/Kernel）、8/16（Input）、8/8（多窗口）、8/4·8/5（座舱电源 CPMS）天然咬合。
> 横向衔接：第 8/17 篇讲过 VHAL 底层就是 AIDL for HAL（走 `/dev/vndbinder`，HAL 最终 `ioctl` 内核 `/dev`）；第 8/16 篇讲过 native Input 系统；第 8/8 篇讲过桌面模式多窗口；第 8/4·8/5 篇讲过 `CarPowerManagementService` 整车电源状态机。本篇是它们的「座舱应用层 + 车载专属服务」拼图，不是重复。

---

## 0. 当日热点锚定（为什么今天深挖 AAOS 座舱）

| 信号 | 内容 | 对面试的影响 |
| --- | --- | --- |
| **AAOS 25Q2 已对齐 Android 16（API 36）** | Google 2026 年起按 trunk-stable 在 Q2/Q4 向 AOSP 发源码；25Q2 与 Android 16 兼容。新特性：Audio Control HAL **用 API 取代 XML 配置**、HD Radio EAS 紧急广播 API、AAudio 支持 OEM 自定义 Audio Attribute 标签、向 **OEM 内置进程**（非 App）扩展电源状态通知、VHAL 属性 **min/max/支持值动态配置**、**8 个第三方可访问车辆属性**（导航/语音助手/天气/行驶状态）、Scalable UI 多窗口框架。 | 考官若问「AAOS 与手机 Android 最大区别」，答「多用户+多显示+多音区+整车电源」只是入门；能讲清 VHAL 发布-订阅、多音区焦点仲裁、乘员分区，才是车载岗分水岭。 |
| **AAOS 成为 SDV（软件定义汽车） backbone** | AAOS 从「信息娱乐系统」演进为中央计算 + 区域架构的统一软件平台；VHAL 标准化车辆信号（HVAC/座椅/传感器）降低 OEM 定制碎片化；OTA-first 全系统升级。 | 「车载 Framework 工程师」岗位 2026 暴涨，但能区分 `CarService`/`VHAL`/`CarAudioService` 的候选人极少。本篇即为此而生。 |
| **Updatable CarService（可更新车载服务）** | AAOS 把 `CarService` 做成 Mainline 风格的独立可更新模块（`com.android.car` 独立进程），与 system_server 解耦，可独立 OTA。 | 考官必问「CarService 跑在哪个进程、怎么启动、怎么跟 system_server 通信」—— 见 §2。 |
| 面试死亡陷阱题 | 第三方题库仍将「Binder/AMS/WMS」列为高频；但**能画出 CarService↔VHAL、能讲清多音区焦点互不影响、能说清乘客屏内容限制（防驾驶分心）**的候选人极少。 | 本篇把座舱三大专属子系统（VHAL / 多显示 / 多音区音频）一次讲透。 |

**结论**：当用户问 WMS 多显示 / HAL / 座舱时，真正拉开差距的是把「framework 车载专属服务 → VHAL（AIDL HAL）→ 内核字符设备」与「多用户 + 多显示 + 多音区 + 整车电源」讲成一条座舱主线。本篇即为此而生。

---

## 1. AAOS 架构总览：座舱是「多用户 + 多显示 + 多音区 + 整车电源」的特殊 Android

手机 Android 是「单用户、单显示、单音频流、电池供电」；AAOS 在这四个维度同时被改造：

```
[手机 Android]                         [AAOS 座舱 Android]
单用户(system)     ->   多用户：司机主用户 + 乘客子用户(每个 occupant 独立 data/设置)
单 Display        ->   多显示：主驾屏 / 副驾屏 / 仪表盘(cluster) / 后座屏，按 occupant 分区
单音频流          ->   多音区(audio zone)：主舱/后排/副驾各自独立焦点与音量
电池供电          ->   整车电源(12V/48V)：CPMS 状态机 ON/SUSPEND/HIBERNATION(8/4·8/5 已讲)
```

- **多用户（Multi-User）**：AAOS 把 Android 的 `UserManager` 用到极致 —— 每个乘员（occupant）是一个 `UserHandle`，拥有独立应用数据、设置偏好。司机登录主用户，副驾/后排各自登录子用户（`android.car.user.CarUserManager`）。
- **多显示（Multi-Display）**：不同于 8/8 桌面模式的「freeform 窗口」，车载多显示是**按乘员分区**的 —— 哪个 occupant 看哪块屏由 `CarOccupantZoneManager` 决定，而不是用户随便拖。
- **多音区（Multi-Zone Audio）**：`CarAudioService` 按 audio zone 隔离焦点，主舱焦点变化**不会**打断后排娱乐系统（§5）。
- **整车电源**：`CarPowerManagementService`（CPMS）整车上下电，联动 VHAL `AP_POWER_STATE_*`（已在 8/4·8/5 深挖，本篇只点衔接）。

> 易错点：AAOS 的「多显示」和 8/8「A18 桌面模式 freeform」是**两套机制** —— 桌面模式是窗口自由布局（WM Shell + ActivityEmbedding），车载多显示是「occupant → display → user」的强绑定分区。考官混淆两者会直接露怯。

---

## 2. CarService 与 Car API：连接生命周期、ICar、权限、Updatable CarService

### 2.1 启动链路（system_server → com.android.car）

AAOS 启动骨架与手机一致（Bootloader→Kernel→init→Zygote→system_server），但在 system_server 阶段切入车载专属逻辑：

```
[system_server 进程]
  startOtherServices()
    if (PackageManager.FEATURE_AUTOMOTIVE)   // 有车载特性
      startService(CAR_SERVICE_HELPER_SERVICE_CLASS)
        -> CarServiceHelperService             // 跑在 system_server 内
            bindServiceAsUser("com.android.car", CarService)   // 跨进程 bind
[com.android.car 进程]   (Updatable CarService, 独立 apk/模块)
  CarService.onCreate()
    new ICarImpl(...)                          // 中枢，实例化所有子服务
      -> CarPropertyService / CarAudioService / CarPowerManagementService
         / CarOccupantZoneService / CarInputService / CarPackageManagerService ...
    ICarImpl.init()                            // 连接 VHAL(IVehicle)、订阅属性
    注册到 ServiceManager("car_service")
```

- `CarServiceHelperService` 位于 **`frameworks/base/services/core/java/com/android/server/CarServiceHelperService.java`**，运行在 system_server，职责是「拉起并看守」独立的 `com.android.car` 进程。
- `CarService` 位于 **`packages/services/Car/service/src/com/android/car/CarService.java`**，运行在独立进程 `com.android.car`（这就是为什么它能被独立 OTA——Updatable CarService）。
- `ICarImpl`（`packages/services/Car/service/src/com/android/car/ICarImpl.java`）是子服务总管，构造时实例化所有 `Car*Service` 子服务，`init()` 里建立 VHAL 连接并订阅车辆属性。

### 2.2 App 侧 Car API 连接生命周期

App 不能直接 `new` 车载管理器，必须经过 `Car` 这个门面，且**异步连接**：

```java
// packages/services/Car/car-lib/src/android/car/Car.java
Car car = Car.createCar(context, mHandler);   // 或 createCar(context, serviceConnection, handler)
car.connect();                                 // 异步！onConnected 之后才能 getCarManager
// CarConnectionCallback.onConnected() 回调后：
CarPropertyManager cpm = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
CarAudioManager am  = (CarAudioManager) car.getCarManager(Car.AUDIO_SERVICE);
```

- `Car.createCar()` 内部通过 `ICar` AIDL 接口（`packages/services/Car/car-lib/src/android/car/ICar.aidl`）跨进程拿到 `com.android.car` 的 `CarService`。
- `getCarManager(serviceName)` 返回对应 `Car*Manager`，每个 Manager 内部持有一个 `ICar*Stub` Binder 代理，最终调到 `com.android.car` 进程里的 `Car*Service`。
- **`connect()` 是异步的**：没 `onConnected` 就调 `getCarManager` 会抛 `IllegalStateException`。这是最高频易错点之一。

### 2.3 权限模型：@SystemApi + Car.PERMISSION_*

车载 API 大量是 `@SystemApi` + 签名/特权权限，普通第三方 App 调不动：

- `Car.PERMISSION_SPEED` / `Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME` / `Car.PERMISSION_CONTROL_CAR_CLIMATE` 等，几乎都是 `signature|privileged` 或 `systemApi` 保护。
- 读取车速 `PERF_VEHICLE_SPEED` 需要 `Car.PERMISSION_SPEED`；改空调需要 `Car.PERMISSION_CONTROL_CAR_CLIMATE`。
- **25Q2 新变化**：8 个原本 `@SystemApi` 的车辆属性被「开放给第三方」（导航/语音/天气/行驶状态相关），但仍需对应权限，且只能读不能写整车控制类属性。

### 2.4 面试高频追问

- **Q：CarService 跑在哪个进程？为什么不在 system_server 里？**
  **A**：跑在独立进程 `com.android.car`。原因有二：① 稳定性 —— 车载服务崩溃不该拖垮 system_server（整车核心）；② **Updatable CarService** —— 独立模块可随车载特性独立 OTA，不必等 framework 整包升级。system_server 里只留一个轻量 `CarServiceHelperService` 负责 bind 与看守。
- **Q：为什么 App 要用 `Car.createCar().connect()` 异步拿 Manager，而不是直接构造？**
  **A**：`CarService` 在另一个进程，连接需要跨 Binder 握手；且车载服务可能尚未就绪。`connect()` 异步 + `onConnected` 回调保证拿到的是已建立好的 `ICar` 会话，避免竞态空指针。

---

## 3. Vehicle HAL（VHAL）：IVehicle AIDL、发布-订阅、CarPropertyManager 双向通道

### 3.1 VHAL 是什么，为什么是 AIDL for HAL

VHAL 让 framework 读/写整车信号（车速、续航、空调、灯光、档位、门窗…），是典型的 **AIDL for HAL**（见 8/17 §1/§2：走 `/dev/vndbinder`，受 VINTF 约束，`@VintfStability`）。接口定义在：

```
hardware/interfaces/automotive/vehicle/aidl/android/hardware/automotive/vehicle/IVehicle.aidl
```

核心方法（AIDL）：

```aidl
interface IVehicle {
    VehiclePropValue get(in int32_t propId, in int32_t areaId) = 0;
    void set(in VehiclePropValue value) = 0;
    // 订阅属性变更（发布-订阅的核心）
    void subscribe(in IVehicleCallback callback, in SubscriptionRequest request) = 0;
    void unsubscribe(in IVehicleCallback callback, in int32_t propId) = 0;
    // 25Q2 新增：属性的 min/max/支持值动态配置
    VehiclePropertyInfo getPropertyInfo(in int32_t propId) = 0;
}
```

- `VehiclePropValue` 是统一信封：`propId`（属性 ID，如 `PERF_VEHICLE_SPEED`）+ `areaId`（区域，如左前门/整车）+ `value`（int32/float/int64/bytes/string）+ `timestamp`。
- 属性 ID 常量集中在 `VehiclePropertyIds`（`hardware/interfaces/automotive/vehicle/aidl/.../VehiclePropertyIds.aidl`）。

### 3.2 双向通道：App ↔ CarPropertyManager ↔ CarPropertyService ↔ VHAL ↔ 硬件

**读（pull）**：
```
App -> CarPropertyManager.getProperty()  --Binder-->  CarPropertyService.getProperty()
   --vndbinder-->  VHAL.get()  ->  ECU/硬件
```
**写（push，如设空调温度）**：
```
App -> CarPropertyManager.setFloatProperty(HVAC_TEMPERATURE_SET, areaId, 22.5f)
   --Binder-->  CarPropertyService.set()  --vndbinder-->  VHAL.set()  ->  执行器
```
**订阅（发布-订阅，车辆信号变更主动上报）**：
```
App.registerCallback(cb, HVAC_TEMPERATURE_SET)
   -> CarPropertyService.subscribe() -> VHAL.subscribe()
      [硬件变更] -> VHAL 回调 IVehicleCallback.onPropertyEvent()
         -> CarPropertyService -> Binder 回调 -> App 的 CarPropertyEventCallback.onChangeEvent()
```

- `CarPropertyManager`（`packages/services/Car/car-lib/src/android/car/hardware/property/CarPropertyManager.java`）是 App 侧门面。
- `CarPropertyService`（`packages/services/Car/service/src/com/android/car/hardware/property/CarPropertyService.java`）是 `com.android.car` 进程内实现，是 VHAL 的客户端 + App 的服务端（双重身份）。
- **订阅是发布-订阅模型**：VHAL 持续推送属性变更，framework 通过标准 Binder 回调通知已注册 App，端到端延迟通常 < 100ms（座舱交互够用）。

### 3.3 25Q2 新特性：属性动态配置 + 第三方开放

- **动态配置**：VHAL 现在支持运行时上报某属性的 `min/max/支持值`（`getPropertyInfo`），framework 不再硬编码范围，OEM 改配置不必改 framework。
- **8 个第三方可访问属性**：导航、语音助手、天气、行驶状态相关的属性从 `@SystemApi` 开放给合规第三方 App（仍需权限），扩展了车机生态。

### 3.4 面试高频追问

- **Q：VHAL 和普通的 framework Binder 服务有什么区别？**
  **A**：VHAL 是 **AIDL for HAL**，走 `/dev/vndbinder`（不是 `/dev/binder`），受 VINTF 版本化约束、参与 OTA 兼容性校验（8/17 §2/§3）。它最终 `ioctl` 内核 `/dev` 跟 ECU 通信。所以它是「framework↔vendor 硬件」的隔离闸门，不是普通 App 服务。
- **Q：为什么车辆信号用「订阅」而不是 App 轮询？**
  **A**：车速/空调等信号变化频繁且由硬件驱动，轮询既浪费 Binder 带宽又增加延迟。发布-订阅让 VHAL 主动推变更，App 只在 `onChangeEvent` 里响应，省 CPU 且实时。

---

## 4. 车载多显示屏与乘员分区（Occupant Zone + Multi-Display）

### 4.1 核心模型：occupant → display → user 强绑定

车载多显示不是「窗口随便放」，而是**按乘员分区**：每个 occupant（DRIVER / FRONT_PASSENGER / REAR_LEFT …）绑定一块 display + 一个 user。

```
CarOccupantZoneManager
   getOccupantZoneForDriver()      ->  zoneId(主驾区)
   getDisplayForOccupant(zoneId)   ->  DisplayId(主驾屏)
   getUserIdForOccupant(zoneId)    ->  UserHandle(主驾用户)
```

- `CarOccupantZoneManager`（`packages/services/Car/car-lib/src/android/car/occupantzone/CarOccupantZoneManager.java`）+ `CarOccupantZoneService`（`.../service/src/com/android/car/occupantzone/`）管理服务端。
- 底层仍是 Android 标准多显示（`DisplayManager` / `Display` / `Context.createDisplayContext`），但「哪块屏属于谁」由 occupant zone 决定，而非用户拖拽。

### 4.2 把 Activity 启动到指定乘员屏

```java
// packages/services/Car/car-lib/src/android/car/app/CarActivityManager.java
CarActivityManager carAm = (CarActivityManager) car.getCarManager(Car.CAR_ACTIVITY_SERVICE);
// 在副驾屏启动一个 Activity（如副驾专属视频）
carAm.startCarApp(uid, /* displayId */ passengerDisplayId, intent);
```

- 跨用户启动 Activity 仍走标准 `ActivityManager` 多用户机制（`startActivityAsUser`），但 AAOS 用 `CarActivityManager` 封装了「按 occupant/display 选 user + display」的便捷入口。
- 与 8/8 桌面模式对比：桌面模式是 freeform 窗口自由布局（`WINDOWING_MODE_FREE_FORM` + WM Shell）；车载是 occupant 强分区，乘客屏内容受严格限制（见下）。

### 4.3 乘客屏内容限制（Driver Distraction 防驾驶分心）

AAOS 强制安全 UX：**行驶中，乘客屏可以放视频，但主驾屏（司机可见区域）禁止视频/复杂输入**。这是车载系统级刚需：

- 系统根据车辆状态（静止/行驶）自动切换交互规则：行驶中禁用主驾区视频播放、弹窗广告、手动文字输入、复杂二级菜单。
- `CarCabinManager` / UX 限制框架（`packages/services/Car/.../uxr/` 的 `CarUxRestrictionsManager`）下发 `CarUxRestrictions` 给 App，App 必须监听并自约束。
- **HUN（Heads-Up Notification）**：行驶中允许短暂抬头通知，但超时/被忽略必须自动收起，不能长时间占主驾注意力。

> 易错点：乘客屏能放视频 ≠ 主驾屏能放。考官问「车上能播视频吗」要答「分区 + 行驶状态」，而不是简单 yes/no。

### 4.4 面试高频追问

- **Q：车载多显示和桌面模式 freeform 多窗口是一回事吗？**
  **A**：不是。桌面模式是窗口自由布局（用户拖拽、ActivityEmbedding 应用侧分栏）；车载多显示是 occupant→display→user 强绑定分区，内容按乘员与行驶状态受系统级约束。两者底层都复用 `DisplayManager`/`WMS`，但策略层完全不同。

---

## 5. 车载音频：多音区 + 音频焦点仲裁（CarAudioService / CarAudioFocus）

### 5.1 多音区（audio zone）隔离

车载音频按 **audio zone** 划分（主舱/副驾/后排各一区），每区有独立音量组与焦点：

- 配置来自 **`car_audio_configuration.xml`**（OEM 提供），`CarAudioService`（`packages/services/Car/service/src/com/android/car/audio/CarAudioService.java`）解析它生成 `CarAudioZone` / `CarVolumeGroup`。
- `CarAudioManager`（`packages/services/Car/car-lib/src/android/car/media/CarAudioManager.java`）是 App 侧 API：`getAudioZoneIds()` / `setVolumeGroupVolume()` / `setFadeTowardFront()` / `setBalanceTowardRight()`。

### 5.2 焦点按 zone 独立仲裁（核心易错）

```
主舱 zone:  导航播报 -> 夺焦点, 音乐 ducking(鸭音)
后排 zone:  后排电影 -> 持有自己 zone 的焦点, 不受主舱影响
=> 主舱焦点变化不会打断后排播放(互不影响)
```

- `CarAudioService` 对所有 App **自动**管理焦点，焦点归属 zone 由其关联 `UserId`/`UID` 判定（多区域音频路由）。
- 若 App 想同时在多个 zone 播放，必须在 `AudioAttributes` 里带 `CarAudioManager.AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID` 分别请求：

```java
Bundle b = new Bundle();
b.putInt(CarAudioManager.AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID, zoneId);
AudioAttributes attr = new AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).addBundle(b).build();
// 用这个 attr 请求焦点 = 指定 zone 而非自动映射
```

- `CarAudioFocus`（`.../audio/CarAudioFocus.java`）做仲裁：导航/电话/紧急警报的优先级规则（紧急警报强制授予、其他静音）。

### 5.3 Audio Control HAL + HAL 主动请求焦点

- Audio Control HAL（Android 9 引入，A14 起支持 fade/balance/焦点请求/静音/ducking/设备增益）在 AAOS 25Q2 改为**用 API 取代 XML 配置**。
- `CarAudioManager.setFadeTowardFront(float)` / `setBalanceTowardRight(float)` 最终调到 Audio Control HAL 的 `IAudioControl.setFadeTowardFront()` / `setBalanceTowardRight()`（AIDL HAL 全版本支持）。
- **HAL 主动焦点（Android 11+）**：外部音源（如车机收音机硬件）可通过 `IAudioControl.registerFocusListener` + `IFocusListener.requestAudioFocus` 参与焦点。无论 HAL 是否拿到焦点，**紧急/安全关键音效必须播放**（政府法规要求），HAL 应主动 ducking Android 流。

> 易错点：多音区焦点**互不影响**是 AAOS 相对手机 Android（单一全局焦点）最反直觉的点。考官问「后排放电影，主舱来导航会怎样」——答「各自 zone 独立，主舱 ducking 音乐、后排不受影响」。

### 5.4 面试高频追问

- **Q：车载音频焦点和手机 AudioFocus 有什么区别？**
  **A**：手机是单一全局焦点；AAOS 按 audio zone **分别管理**，每个 zone 独立仲裁，跨 zone 互不剥夺。底层 `CarAudioService` 自动按 UserId/UID 映射 zone，HAL 还能主动请求焦点（外部音源）。

---

## 6. 仪表盘渲染（Instrument Cluster）：导航投影与隐私安全

### 6.1 仪表盘是独立 display + 独立渲染进程

仪表盘（cluster）是一块独立 `DisplayId`，内容由**独立 cluster 渲染进程**（如 `packages/apps/Car/Cluster`）经 `ClusterRenderingService` / `InstrumentClusterRenderer`（`packages/services/Car/service/src/com/android/car/cluster/`）注入：

- 导航 App 把「转弯提示/下一个路口」通过 `CarNavigationStatusManager`（`android.car.navigation`）或 `CarClusterManager` 推给 cluster 渲染进程，cluster 进程把精简导航信息画到仪表盘。
- 底层仍是 `SurfaceFlinger` 多显示合成（8/24 图形篇讲过 BufferQueue/HWC）—— 仪表盘就是 SF 管理的一块逻辑 display。

### 6.2 隐私与安全约束

- 仪表盘**只该显示驾驶必要信息**（车速/转速/导航简图/ADAS），**不能投敏感内容**（消息正文、联系人、支付）。这是法规 + 安全驱动。
- cluster 渲染进程通常是特权系统应用，普通 App 不能直接往仪表盘画，必须经 `Car*`Manager 受控通道。
- 行驶中仪表盘信息密度受 `CarUxRestrictions` 约束（与 §4.3 同源）。

### 6.3 面试高频追问

- **Q：导航怎么投到仪表盘？**
  **A**：导航 App 经 `CarNavigationStatusManager`/`CarClusterManager` 把导航事件（转弯/距离）发给 `com.android.car` 的 cluster 渲染服务，后者在仪表盘独立 display 上由专用 cluster 进程绘制。本质是「App → CarService → cluster display（SF 多显示合成）」，App 不能直接持有仪表盘 Surface。

---

## 7. 座舱交互输入：Rotary / 旋钮 / 方向盘按键 → CarInputManager（联动 8/16）

### 7.1 车载专属输入外设

车载没有触屏全覆盖，大量交互靠**旋钮（rotary）、方向盘按键、触摸板**：

- `CarInputManager`（`packages/services/Car/car-lib/src/android/car/input/CarInputManager.java`）暴露车载输入事件（如 rotary 旋转/按压、自定义按键）。
- `RotaryService` / `CarInputService`（`packages/services/Car/service/src/com/android/car/input/`）在 `com.android.car` 进程内处理 rotary 焦点导航（把旋钮旋转映射成「焦点移动」，`rotate` → `focus move` → `click`）。

### 7.2 与 Input 系统的衔接（8/16）

- 方向盘按键本质是标准 `KeyEvent`（`KEYCODE_MEDIA_*` / 自定义 `KeyEvent`）经内核 `evdev` → `EventHub` → `InputReader` → `InputDispatcher`（8/16 全链路），到 `Activity` 的 `onKeyDown`。
- 旋钮（rotary）则是「非标准输入」：经 `CarInputManager` 进入 `RotaryService`，由它做「焦点导航」语义，再注入到 View 焦点系统 —— 这是车载对 8/16 Input 管道的**上层扩展**，不是替代。
- 车载还常做 **capture**：行驶中系统可能拦截某些触摸输入（driver distraction），与 8/16 的 `InputDispatcher` focus/intercept 机制同源但策略更严。

### 7.3 面试高频追问

- **Q：方向盘按键和旋钮在 Android 输入系统里怎么区分处理？**
  **A**：方向盘按键是标准 `KeyEvent`，走 8/16 的 `evdev→EventHub→InputDispatcher→onKeyDown` 全链路；旋钮（rotary）是非标准车载输入，经 `CarInputManager`/`RotaryService` 做焦点导航语义后注入 View 焦点系统，是 Input 管道的车载上层扩展。

---

## 8. 座舱电源（衔接 8/4·8/5，本篇不重述）

整车上下电（`CarPowerManagementService` / CPMS 状态机 ON→SHUTDOWN_PREPARE→SUSPEND→HIBERNATION→OFF、VHAL `AP_POWER_STATE_*`、Garage Mode）已在 **第 8/4·8/5 篇** 深挖。本篇只点衔接：

- VHAL 的 `AP_POWER_STATE_REQ` / `BOOT_COMPLETE` 是 CPMS 与整车 ECU 协商上下电的通道 —— 正是 §3 VHAL 双向通道的「整车电源」实例。
- 25Q2 新特性「向 OEM 内置进程扩展电源状态通知」就是让非 App 的车载守护进程也能收到 CPMS 的电源状态，提前做挂起/恢复。

---

## 9. 易错红榜 TOP20（AAOS 座舱专版）

1. **AAOS ≠ 装在车里的手机 Android**：它是多用户 + 多显示 + 多音区 + 整车电源的「车规级座舱 OS」。
2. **CarService 跑在独立进程 `com.android.car`**，不在 system_server；system_server 里只有轻量 `CarServiceHelperService`（bind 看守）。
3. **Updatable CarService**：独立模块可随车载特性独立 OTA，与 framework 解耦。
4. **`Car.connect()` 是异步的**：没 `onConnected` 就 `getCarManager` 必抛 `IllegalStateException`。
5. **App 不能直接 `new` Car*Manager**，必须经过 `Car.createCar().connect()` 门面。
6. **车载 API 大量 `@SystemApi` + `Car.PERMISSION_*` 签名/特权权限**，第三方 App 调不动。
7. **VHAL 是 AIDL for HAL，走 `/dev/vndbinder`**，受 VINTF 约束（联动 8/17）。
8. **车辆信号是发布-订阅**，不是 App 轮询；`registerCallback` 订阅，`onChangeEvent` 收变更。
9. **VHAL 双向**：`get/set`（pull/push）+ `subscribe`（push 变更），信封是 `VehiclePropValue`（propId+areaId+value+timestamp）。
10. **`CarPropertyService` 是双重身份**：VHAL 的客户端 + App 的服务端。
11. **车载多显示 = occupant → display → user 强绑定分区**，不是自由窗口布局。
12. **车载多显示 ≠ 桌面模式 freeform**（8/8）：策略层完全不同。
13. **乘客屏能放视频 ≠ 主驾屏能放**：行驶中主驾区禁视频/复杂输入（Driver Distraction）。
14. **`CarUxRestrictions` 下发行驶状态约束**，App 必须监听并自约束（含 HUN 超时收起）。
15. **多音区音频焦点互不影响**：主舱 ducking 音乐，后排电影照播。
16. **跨 zone 播放必须带 `AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID`** 分别请求焦点。
17. **Audio Control HAL 用 API 取代 XML 配置**（25Q2 新），fade/balance/焦点/ducking 经 `IAudioControl` AIDL。
18. **HAL 主动焦点（Android 11+）**：外部音源可请求焦点，但紧急/安全音效**必须播放**（法规）。
19. **仪表盘是独立 display + 独立渲染进程**，App 不能直接持有其 Surface，须经 `CarClusterManager` 受控通道。
20. **方向盘按键 = 标准 KeyEvent（走 8/16 Input 全链路）；旋钮 = CarInputManager/RotaryService 焦点导航扩展**。

---

## 10. 三条高频追问链（AAOS 座舱专版）

### 链 A：司机调一下空调，到底发生了什么（全链路溯源）
追问：App 调 `setFloatProperty(HVAC_TEMPERATURE_SET)` 走哪条 Binder？→（CarPropertyManager→Binder→CarPropertyService）→ `com.android.car` 怎么跟硬件说话？→（VHAL.set() 走 `/dev/vndbinder`，AIDL for HAL，最终 `ioctl` 内核 `/dev`，联动 8/17）→ VHAL 怎么知道空调设成功没？→（set 后硬件回 `onPropertyEvent` 订阅回调）→ 这条链在 GKI 下内核驱动必须是 `.ko` 且只调 KMI 符号吗？→（是，联动 8/17 §5）

### 链 B：后排看电影，主舱来导航会怎样（多音区 + 多显示）
追问：后排电影占哪个 audio zone？→（car_audio_configuration.xml 解析出的后排 zone）→ 主舱导航夺焦点影响后排吗？→（不影响，zone 独立仲裁）→ 后排电影投在哪块屏？→（CarOccupantZoneManager 把 REAR occupant 映射到后座 display + 后座 user）→ 主驾屏为什么不能同步放这个电影？→（Driver Distraction 约束，CarUxRestrictions 禁用主驾区视频）→ 这些 display 底层谁合成？→（SurfaceFlinger 多显示，联动 8/24）

### 链 C：车上旋钮/方向盘按键怎么变成 App 操作（输入衔接）
追问：方向盘按键是什么事件？→（标准 KeyEvent，evdev→EventHub→InputDispatcher→onKeyDown，联动 8/16）→ 旋钮呢？→（非标准，CarInputManager→RotaryService 做焦点导航语义）→ 为什么车载要这套扩展？→（触屏不全覆盖，旋钮是主交互）→ 行驶中系统会拦截某些触摸吗？→（会，driver distraction capture，与 InputDispatcher intercept 同源但更严）

---

## 11. AOSP 14 源码路径清单（AAOS 座舱）

```
# CarService / Car API（车载中枢）
packages/services/Car/car-lib/src/android/car/Car.java                 # 门面: createCar/connect/getCarManager
packages/services/Car/car-lib/src/android/car/ICar.aidl               # ICar Binder 接口
packages/services/Car/service/src/com/android/car/CarService.java     # com.android.car 进程入口
packages/services/Car/service/src/com/android/car/ICarImpl.java       # 子服务总管(实例化+init)
frameworks/base/services/core/java/com/android/server/CarServiceHelperService.java  # system_server 内 bind 看守

# Vehicle HAL（AIDL for HAL, vndbinder）
hardware/interfaces/automotive/vehicle/aidl/android/hardware/automotive/vehicle/IVehicle.aidl
hardware/interfaces/automotive/vehicle/aidl/.../VehiclePropertyIds.aidl
packages/services/Car/car-lib/src/android/car/hardware/property/CarPropertyManager.java   # App 门面
packages/services/Car/service/src/com/android/car/hardware/property/CarPropertyService.java  # 双重身份

# 多显示 / 乘员分区
packages/services/Car/car-lib/src/android/car/occupantzone/CarOccupantZoneManager.java
packages/services/Car/service/src/com/android/car/occupantzone/CarOccupantZoneService.java
packages/services/Car/car-lib/src/android/car/app/CarActivityManager.java   # 按 occupant/display 启动
frameworks/base/services/core/java/com/android/server/wm/                    # 标准多显示(WMS)

# 车载音频（多音区 + 焦点）
packages/services/Car/car-lib/src/android/car/media/CarAudioManager.java
packages/services/Car/service/src/com/android/car/audio/CarAudioService.java
packages/services/Car/service/src/com/android/car/audio/CarAudioFocus.java   # 焦点仲裁
packages/services/Car/service/src/com/android/car/audio/CarDucking.java      # 鸭音策略
device/<oem>/car_audio_configuration.xml                                     # OEM 音区配置
hardware/interfaces/audio/.../IAudioControl.aidl                             # Audio Control HAL(AIDL)

# 仪表盘渲染
packages/services/Car/service/src/com/android/car/cluster/InstrumentClusterRenderer.java
packages/apps/Car/Cluster/                                                   # 独立 cluster 渲染进程
packages/services/Car/car-lib/src/android/car/navigation/CarNavigationStatusManager.java

# 座舱交互输入
packages/services/Car/car-lib/src/android/car/input/CarInputManager.java
packages/services/Car/service/src/com/android/car/input/RotaryService.java
packages/services/Car/service/src/com/android/car/input/CarInputService.java

# 座舱电源（联动 8/4·8/5）
packages/services/Car/service/src/com/android/car/power/CarPowerManagementService.java
```

---

## 12. 32 → 33 篇交叉索引（AAOS 座舱视角）

| 主题 | 本篇衔接点 | 关联篇 |
| --- | --- | --- |
| VHAL 是 AIDL for HAL（vndbinder / VINTF / ioctl 内核） | §3 | 第 8/17 篇（HAL/Kernel/GKI/MTK 全链路） |
| HAL 最终 `ioctl` 内核 `/dev` 字符设备 | §3.2、链 A | 第 8/17 篇（§4 platform_driver/cdev/miscdevice/ioctl） |
| 多显示底层 SurfaceFlinger 合成 | §4.1、§6、链 B | 第 8/24 篇（图形渲染合成）、第 8/8 篇（桌面模式多窗口） |
| 标准 Input 全链路（KeyEvent） | §7、链 C | 第 8/16 篇（输入系统全链路源码走读） |
| 整车电源 CPMS 状态机 / VHAL AP_POWER_STATE | §8 | 第 8/4·8/5 篇（AAOS 座舱电源状态机/收官补遗） |
| 跨设备/多用户（UserManager） | §1、§4 | 第 8/8 篇（A18 跨设备协同/CDM） |
| binderized HAL 与三大 Binder 上下文 | §3.1 | 第 8/17 篇（§2）、第 8/20 篇（Binder 一次事务） |

---

> 本篇把「CarService（独立进程 + 异步连接 + 权限门面）→ VHAL（AIDL for HAL 发布-订阅双向通道）→ 车载多显示（occupant→display→user 强分区 + Driver Distraction）→ 多音区音频焦点（zone 独立仲裁 + HAL 主动焦点）→ 仪表盘渲染（独立 display + 受控通道）→ 座舱交互输入（Rotary/KeyEvent 衔接 Input 系统）」焊成一条完整座舱主线，补齐了此前 Java 侧（8/17 VHAL 底层）与车载电源侧（8/4·8/5 CPMS）之间「座舱应用层 + 车载专属服务」的真空。系列至此 **33 篇 / 约 213 专题**：主线 + 盲区 + 深水区 + 智能层 + 安全世界 + 座舱（电源 8/4·8/5 + 本篇应用层全貌）+ 端侧 AI + 源码 walk + Perfetto SQL + 基础八股 + 两版真题大乱斗 + Native 稳定性 + Compose 编译/运行时 + 输入系统 + HAL/Kernel/GKI/MTK 全链路 + AAOS 座舱专项，完整闭环。
