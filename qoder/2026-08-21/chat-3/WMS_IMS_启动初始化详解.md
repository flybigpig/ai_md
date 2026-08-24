# WMS 与 IMS 启动初始化核心方法详解

---

## 一、系统服务启动总览

```
═══════════════════════════════════════════════════════════════════════════════════
  Android 系统服务启动链
═══════════════════════════════════════════════════════════════════════════════════

  init 进程 (PID 1)
       │
       │ fork + exec
       ▼
  Zygote 进程
       │
       │ fork
       ▼
  System Server 进程
       │
       ▼
  SystemServer.main()
       │
       ▼
  SystemServer.run()
       │
       ├──▶ startBootstrapServices()     ← 启动引导服务
       │       │
       │       ├── Installer
       │       ├── PowerManagerService
       │       ├── ActivityManagerService (AMS)
       │       └── ...
       │
       ├──▶ startCoreServices()          ← 启动核心服务
       │       │
       │       ├── DropBoxManagerService
       │       └── ...
       │
       ├──▶ startOtherServices()         ← 启动其他服务
       │       │
       │       ├── ★ WindowManagerService (WMS)
       │       ├── ★ InputManagerService (IMS 的 InputManager)
       │       ├── ★ InputMethodManagerService (输入法管理服务)
       │       └── ...
       │
       └──▶ startApexServices()          ← 启动 APEX 服务
```

---

## 二、WMS 启动初始化

### 1. SystemServer 中创建 WMS

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: SystemServer.java
// 方法: startOtherServices()
// 作用: 在 system_server 进程中创建 WMS 实例
// ═══════════════════════════════════════════════════════════════════════

private void startOtherServices() {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 创建 InputManager (输入事件管理器)                        │
    // │ 注意: InputManager ≠ InputMethodManagerService                   │
    // │ InputManager 负责底层输入事件 (触摸/按键) 的分发                   │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 创建 InputManager
    // InputManager 是 Native 层的服务，负责:
    //   - 从内核读取触摸/按键事件
    //   - 通过 InputDispatcher 分发到目标窗口
    //   - 管理输入设备
    inputManager = new InputManagerService(context);
    // 内部逻辑:
    //   // 创建 Native 层的 InputManager
    //   mNative = nativeInit(this, context, mHandler.getLooper());
    //   // nativeInit() 在 C++ 层:
    //   //   - 创建 InputReader (读取 /dev/input/eventX)
    //   //   - 创建 InputDispatcher (分发事件到窗口)
    //   //   - 启动 InputReaderThread 和 InputDispatcherThread

    // ② 启动 InputManager
    inputManager.start();
    // 内部逻辑:
    //   // 启动两个后台线程:
    //   // 1. InputReaderThread: 循环读取输入设备事件
    //   //    → 解析触摸坐标、按键码
    //   //    → 将事件放入 InputDispatcher 的队列
    //   //
    //   // 2. InputDispatcherThread: 循环分发事件
    //   //    → 从队列取出事件
    //   //    → 查询 WMS 确定目标窗口
    //   //    → 通过 InputChannel 发送到客户端进程

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: 创建 WindowManagerService                               │
    // └─────────────────────────────────────────────────────────────────┘

    // ③ 创建 WMS
    // 注意: WMS 的构造需要多个依赖:
    //   - context: 系统上下文
    //   - inputManager: 刚创建的 InputManager (WMS 需要它来分发输入事件)
    //   - haveInputMethods: 是否支持输入法 (通常 true)
    //   - mFactoryTest: 工厂测试模式
    //   - showBootMsgs: 是否显示启动消息
    wm = WindowManagerService.main(context, inputManager,
            mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL,
            !mFirstBoot, mOnlyCore);
    // ★ main() 是 WMS 的入口方法 (见下方详解)

    // ④ 将 WMS 添加到 ServiceManager
    // ServiceManager 是 Android 的全局服务注册表
    // 其他进程通过 ServiceManager 查找并获取 WMS 的 Binder 代理
    ServiceManager.addService(Context.WINDOW_SERVICE, wm,
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // "window" → IWindowManager Binder 代理
    // 应用进程通过以下方式获取:
    //   IWindowManager wm = IWindowManager.Stub.asInterface(
    //       ServiceManager.getService("window"));

    // 同时注册 "input" 服务
    ServiceManager.addService(Context.INPUT_SERVICE,
            inputManager, /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 3: 创建 InputMethodManagerService                          │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑤ 创建 IMS (输入法管理服务)
    // 注意: 此时 InputManager 和 WMS 都已创建
    // IMS 需要引用两者
    imm = new InputMethodManagerService(context, wm);
    // ★ 构造函数见下方详解

    // ⑥ 将 IMS 添加到 ServiceManager
    ServiceManager.addService(Context.INPUT_METHOD_SERVICE, imm,
            /* allowIsolated= */ false,
            DUMP_FLAG_PRIORITY_CRITICAL);
    // "input_method" → IInputMethodManager Binder 代理
    // 应用进程通过以下方式获取:
    //   InputMethodManager imm = (InputMethodManager)
    //       context.getSystemService(Context.INPUT_METHOD_SERVICE);
}
```

### 2. WindowManagerService.main() — WMS 入口方法

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: WindowManagerService.java
// 方法: main() — 静态工厂方法
// 作用: 创建 WMS 实例并执行初始化
// 调用方: SystemServer.startOtherServices()
// ═══════════════════════════════════════════════════════════════════════

public static WindowManagerService main(final Context context,
        final InputManagerService im,
        final boolean haveInputMethods,
        final boolean showBootMsgs,
        final boolean onlyCore) {

    // ① 在独立的线程中创建 WMS
    // 使用 DisplayThread 的原因:
    //   - DisplayThread 是系统专用的单线程
    //   - 负责所有与显示相关的操作 (SurfaceFlinger 交互)
    //   - 保证窗口操作与 Surface 操作的顺序一致性
    //   - 避免与 system_server 主线程互相阻塞
    DisplayThread.getHandler().runWithScissors(() -> {
        // runWithScissors(): 在 DisplayThread 上同步执行
        // 调用方会阻塞等待执行完成

        // ② 创建 WMS 实例
        // ★ 这是 WMS 的核心构造过程 (见下方详解)
        new WindowManagerService(context, im, haveInputMethods,
                showBootMsgs, onlyCore);
    }, 0);  // timeout = 0 表示无限等待

    // ③ 返回 WMS 实例
    // 注意: 由于 runWithScissors 是同步的
    // 到这里时 WMS 已经完全初始化
    return WindowManagerService.sInstance;
    // sInstance 在构造函数中被赋值
}
```

### 3. WindowManagerService 构造函数 — 核心初始化

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: WindowManagerService.java
// 方法: 构造函数
// 作用: 初始化 WMS 的所有核心组件和数据结构
// ═══════════════════════════════════════════════════════════════════════

private WindowManagerService(Context context, InputManagerService im,
        boolean haveInputMethods, boolean showBootMsgs, boolean onlyCore) {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 基础成员初始化                                            │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 保存全局单例引用
    // WMS 是单例模式，整个系统只有一个实例
    sInstance = this;

    // ② 保存上下文和依赖
    mContext = context;
    mAllowTheaterModeWakeFromLayout = context.getResources().getBoolean(
            com.android.internal.R.bool.config_allowTheaterModeWakeFromLayout);

    // ③ 保存 InputManager 引用
    // WMS 通过 InputManager 来:
    //   - 查询触摸事件的目标窗口
    //   - 注册/注销输入通道 (InputChannel)
    //   - 获取输入焦点信息
    mInputManager = im;

    // ④ 是否有输入法支持
    mHaveInputMethods = haveInputMethods;
    // 通常为 true，某些特殊设备可能没有输入法

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: 核心数据结构初始化                                        │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑤ 全局锁
    // WMS 的所有状态操作都需要持有此锁
    // 这是 Android 中最"重"的锁之一
    // 很多系统操作 (屏幕旋转、窗口添加) 都需要等待此锁
    mGlobalLock = new Object();

    // ⑥ 窗口映射表
    // key: IBinder (客户端的 IWindow.asBinder())
    // value: WindowState (窗口状态)
    // 用于快速根据客户端查找对应的窗口
    mWindowMap = new HashMap<>();

    // ⑦ 隐藏窗口列表
    // 被隐藏的窗口 (不可见但仍然存在)
    mHiddenWindowList = new ArrayList<>();

    // ⑧ 待处理的窗口列表
    // 正在动画中或等待布局的窗口
    mOpeningApps = new ArrayList<>();
    mClosingApps = new ArrayList<>();

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 3: 输入法相关字段初始化                                      │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑨ 输入法窗口引用
    // 指向当前输入法窗口的 WindowState
    // 当输入法显示时，此字段非 null
    mInputMethodWindow = null;
    // 初始为 null，当输入法窗口被 addWindow 时赋值

    // ⑩ 输入法目标窗口
    // 输入法应该覆盖在哪个窗口之上
    // 通常是当前焦点窗口
    mInputMethodTarget = null;

    // ⑪ 输入法连接状态
    mInputMethodConnection = null;
    // InputConnection (WMS 与输入法进程之间的 Binder 连接)

    // ⑫ 输入法会话
    mInputMethodSession = null;

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 4: 窗口容器层级树初始化                                      │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑬ 创建 RootWindowContainer
    // 这是窗口层级树的根节点
    // 结构: Root → DisplayContent → TaskStack → Task → AppWindowToken → WindowState
    mRoot = new RootWindowContainer(this);
    // 内部逻辑:
    //   // 创建默认的 DisplayContent (代表主屏幕)
    //   // 每个物理屏幕/虚拟屏幕对应一个 DisplayContent
    //   mDisplayContent = new DisplayContent(this, displayId);
    //
    //   DisplayContent 内部包含:
    //   ┌──────────────────────────────────────────────────────┐
    //   │  DisplayContent                                      │
    //   │                                                      │
    //   │  mTaskStackContainers: 管理所有任务栈                  │
    //   │  ├── TaskStack (前台栈)                              │
    //   │  │     └── Task (当前应用)                           │
    //   │  │           └── AppWindowToken (当前 Activity)      │
    //   │  │                 └── WindowState (当前窗口)        │
    //   │  ├── TaskStack (后台栈)                              │
    //   │  │     └── ...                                      │
    //   │  │                                                   │
    //   │  mImeContainer: 管理输入法窗口                        │
    //   │  └── WindowState (输入法窗口)                        │
    //   │                                                      │
    //   │  mWindows: 按 Z-Order 排序的所有窗口列表              │
    //   │  mWallpaperController: 壁纸控制器                     │
    //   │  mDockedDivider: 分屏分割条                           │
    //   └──────────────────────────────────────────────────────┘

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 5: 动画和策略初始化                                          │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑭ 窗口动画控制器
    mAnimator = new WindowAnimator(this);
    // 管理所有窗口动画:
    //   - Activity 转场动画 (打开/关闭/切换)
    //   - 窗口属性动画 (位置/大小/透明度)
    //   - 屏幕旋转动画

    // ⑮ 窗口策略
    mPolicy = new PhoneWindowManager();
    // PhoneWindowManager 实现了 WindowManagerPolicy 接口
    // 负责:
    //   - 确定窗口层级 (layoutWindowLw)
    //   - 处理系统按键 (HOME/BACK/MENU/POWER)
    //   - 管理状态栏/导航栏的行为
    //   - 决定窗口的 insets (状态栏/导航栏占用的区域)
    mPolicy.setup(mContext, this, mInputManager);
    // setup() 内部:
    //   // 读取系统配置
    //   mStatusBarHeight = ...;      // 状态栏高度
    //   mNavigationBarHeight = ...;  // 导航栏高度
    //   mHasNavigationBar = ...;     // 是否有导航栏
    //   mHasStatusBar = ...;         // 是否有状态栏

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 6: Surface 和显示初始化                                      │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑯ 创建 Surface 会话
    // SurfaceSession 是与 SurfaceFlinger 通信的桥梁
    // 通过它创建/销毁窗口的 Surface
    mSurfaceSession = new SurfaceSession();
    // 内部:
    //   // 调用 Native 方法创建与 SurfaceFlinger 的连接
    //   mNative = nativeCreate();
    //   // 此后通过此 session 创建的 Surface 都会被 SurfaceFlinger 管理

    // ⑰ 初始化显示信息
    mDisplaySettings = new DisplaySettings(context);
    // 存储每个 Display 的配置:
    //   - 分辨率
    //   - 刷新率
    //   - 密度 (DPI)
    //   - 旋转角度

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 7: 注册系统广播监听                                          │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑱ 监听配置变更 (屏幕旋转、语言切换等)
    // 当配置变更时，WMS 需要重新布局所有窗口
    mContext.registerReceiver(
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // 处理配置变更
                synchronized (mGlobalLock) {
                    mRoot.performLayoutAndPlaceSurfaces();
                }
            }
        },
        new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
    );

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 8: 初始化完成                                                │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑲ 标记 WMS 已就绪
    mSystemReady = true;
    // 此后 WMS 开始接受窗口操作请求

    // ⑳ 打印初始化信息
    Slog.i(TAG, "WindowManagerService initialized");
    // 此时 WMS 已经可以:
    //   - 接受 addWindow() 请求
    //   - 管理窗口层级
    //   - 与 SurfaceFlinger 交互
    //   - 与 InputManager 协作分发输入事件
    //   - 与 IMS 协作管理输入法
}
```

---

## 三、IMS 启动初始化

### 4. InputMethodManagerService 构造函数

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodManagerService.java
// 方法: 构造函数
// 作用: 初始化输入法管理服务
// 调用方: SystemServer.startOtherServices()
// ═══════════════════════════════════════════════════════════════════════

public InputMethodManagerService(Context context,
        WindowManagerService windowManager) {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 基础初始化                                               │
    // └─────────────────────────────────────────────────────────────────┘

    // ① 保存上下文
    mContext = context;

    // ② 保存 WMS 引用
    // IMS 需要通过 WMS 来:
    //   - 查询当前焦点窗口
    //   - 获取窗口信息
    //   - 监听焦点变化
    mWindowManager = windowManager;

    // ③ 获取 InputManager 引用
    // 用于查询输入设备信息
    mIInputManager = IInputManager.Stub.asInterface(
            ServiceManager.getService(Context.INPUT_SERVICE));

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: 输入法列表初始化                                          │
    // └─────────────────────────────────────────────────────────────────┘

    // ④ 创建输入法映射表
    // key: 输入法 ID (组件名，如 "com.sohu.inputmethod/.SogouIME")
    // value: InputMethodInfo (输入法元数据)
    mMethodMap = new HashMap<>();
    // InputMethodInfo 包含:
    //   - id: 组件名
    //   - packageName: 包名
    //   - serviceName: 服务名
    //   - settingsActivity: 设置界面 Activity
    //   - isDefault: 是否默认输入法
    //   - supportsSwitchingToNextInputMethod: 是否支持切换

    // ⑤ 创建输入法列表
    mMethodList = new ArrayList<>();
    // 按优先级排序的输入法列表

    // ⑥ 扫描已安装的输入法
    buildInputMethodListLocked();
    // 内部逻辑:
    //   // 通过 PackageManager 查找所有声明了
    //   // android.view.InputMethod 服务的组件
    //   List<ResolveInfo> services = mPm.queryIntentServices(
    //       new Intent(InputMethodService.SERVICE_INTERFACE),
    //       PackageManager.GET_META_DATA);
    //
    //   for (ResolveInfo ri : services) {
    //       // 解析 AndroidManifest.xml 中的 <input-method> 标签
    //       // 获取输入法的元数据:
    //       //   - 名称、图标
    //       //   - 设置界面
    //       //   - 支持的输入类型
    //       //   - 是否默认
    //       ServiceInfo si = ri.serviceInfo;
    //       XmlPullParser parser = ...;
    //       InputMethodInfo info = new InputMethodInfo(si, parser);
    //
    //       mMethodList.add(info);
    //       mMethodMap.put(info.getId(), info);
    //   }
    //
    //   // 确定默认输入法
    //   // 优先使用用户设置的，否则使用系统默认
    //   String defaultIme = Settings.Secure.getString(
    //       mContext.getContentResolver(),
    //       Settings.Secure.DEFAULT_INPUT_METHOD);
    //   if (defaultIme == null || !mMethodMap.containsKey(defaultIme)) {
    //       // 没有设置或设置的输入法不存在 → 使用系统默认
    //       defaultIme = "com.android.inputmethod.latin/.LatinIME";
    //   }
    //   mCurId = defaultIme;

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 3: 状态字段初始化                                            │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑦ 当前状态
    mCurClient = null;          // 当前绑定的客户端
    mCurMethod = null;          // 当前绑定的输入法 (IInputMethod Binder)
    mCurId = null;              // 当前选中的输入法 ID
    mInputShown = false;        // 输入法是否正在显示
    mShowRequested = false;     // 是否请求了显示
    mSystemReady = false;       // 系统是否就绪

    // ⑧ 焦点窗口记录
    mCurFocusedWindow = null;   // 当前焦点窗口的 token

    // ⑨ Handler 和 Looper
    // IMS 的操作需要在特定线程上执行
    mHandler = new MyHandler(context.getMainLooper());
    // 使用主线程 Looper，因为 IMS 需要:
    //   - 响应系统广播
    //   - 处理配置变更
    //   - 与 WMS 交互

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 4: 注册内容观察者                                            │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑩ 监听输入法设置变化
    // 当用户在系统设置中切换输入法时，需要更新状态
    mSettingsObserver = new SettingsObserver(mHandler);
    mContext.getContentResolver().registerContentObserver(
        Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
        false,
        mSettingsObserver);
    // 当用户切换输入法:
    //   设置 → 语言和输入法 → 选择搜狗输入法
    //   → Settings.Secure.DEFAULT_INPUT_METHOD 被修改
    //   → mSettingsObserver.onChange() 被触发
    //   → IMS 切换到新的输入法
    //   → 绑定新的输入法服务
    //   → 显示新的键盘 UI

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 5: 注册广播接收器                                            │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑪ 监听用户切换
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_USER_SWITCHED);
    // 不同用户可能有不同的输入法设置
    mContext.registerReceiver(new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                // 用户切换 → 重新加载输入法列表
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                switchUserLocked(userId);
            }
        }
    }, filter);

    // ⑫ 监听包变更 (输入法安装/卸载)
    filter = new IntentFilter();
    filter.addAction(Intent.ACTION_PACKAGE_ADDED);
    filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
    filter.addDataScheme("package");
    mContext.registerReceiver(new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 输入法应用安装/卸载/更新 → 重新扫描输入法列表
            rebuildInputMethodListLocked();
        }
    }, filter);

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 6: 初始化完成                                                │
    // └─────────────────────────────────────────────────────────────────┘

    // ⑬ 标记系统就绪
    mSystemReady = true;

    // ⑭ 此时 IMS 已经可以:
    //   - 接受 showSoftInput() / hideSoftInput() 请求
    //   - 跟踪输入焦点变化
    //   - 绑定/切换输入法服务
    //   - 管理输入法窗口

    Slog.i(TAG, "InputMethodManagerService initialized");
    Slog.i(TAG, "Available input methods: " + mMethodList.size());
    Slog.i(TAG, "Default IME: " + mCurId);
}
```

### 5. IMS 绑定输入法服务的过程

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodManagerService.java
// 方法: bindToCurrentMethod()
// 作用: 绑定到当前选中的输入法服务
// 触发时机: 系统启动后首次需要输入法 / 用户切换输入法
// ═══════════════════════════════════════════════════════════════════════

void bindToCurrentMethod() {

    // ① 获取当前输入法信息
    InputMethodInfo info = mMethodMap.get(mCurId);
    if (info == null) {
        // 找不到输入法 → 使用系统默认
        mCurId = "com.android.inputmethod.latin/.LatinIME";
        info = mMethodMap.get(mCurId);
    }

    // ② 创建绑定 Intent
    Intent intent = new Intent(InputMethodService.SERVICE_INTERFACE);
    // SERVICE_INTERFACE = "android.view.InputMethod"
    // 这是输入法服务声明在 AndroidManifest.xml 中的 action

    intent.setComponent(new ComponentName(
            info.getPackageName(),    // 如 "com.sohu.inputmethod"
            info.getServiceName()));  // 如 ".SogouIME"

    // ③ 绑定服务
    // BIND_AUTO_CREATE: 如果服务未运行，自动创建
    boolean bound = mContext.bindService(intent,
            mMethodConnection,           // ServiceConnection 回调
            Context.BIND_AUTO_CREATE);

    if (!bound) {
        // 绑定失败 → 可能是输入法应用被禁用或卸载
        Slog.w(TAG, "Failed to bind to input method: " + mCurId);
        return;
    }

    // ④ ServiceConnection 回调 (异步)
    // 绑定成功后，系统会回调 onServiceConnected()
    // mMethodConnection 的定义:
    //
    // ServiceConnection mMethodConnection = new ServiceConnection() {
    //     @Override
    //     public void onServiceConnected(ComponentName name, IBinder service) {
    //         // ★ 绑定成功! service 是输入法的 IInputMethod Binder
    //         //
    //         // 保存输入法 Binder 引用
    //         mCurMethod = IInputMethod.Stub.asInterface(service);
    //         //
    //         // 通知输入法: 你被选中了!
    //         try {
    //             // 传递 IMS 的 token 给输入法
    //             // 输入法通过此 token 可以与 IMS 通信
    //             mCurMethod.attachToken(mToken);
    //             //
    //             // 让输入法创建会话
    //             // 输入法会回调 IMS 的 IInputMethodManager 接口
    //             mCurMethod.createSession(mSessionCallback);
    //             //
    //             // createSession() 在输入法进程中:
    //             //   InputMethodService.onCreateInputMethodSessionInterface()
    //             //   → 创建 InputMethodSession
    //             //   → 回调 IMS: session 已创建
    //         } catch (RemoteException e) {
    //             // 输入法进程可能已死亡
    //         }
    //     }
    //
    //     @Override
    //     public void onServiceDisconnected(ComponentName name) {
    //         // 输入法服务断开连接
    //         // 可能是:
    //         //   - 输入法进程崩溃
    //         //   - 用户切换了输入法
    //         //   - 输入法应用被卸载
    //         mCurMethod = null;
    //         // 尝试重新绑定或切换到默认输入法
    //         resetStateIfCurrentMethodDisconnected();
    //     }
    // };
}
```

---

## 四、输入法进程侧初始化

### 6. InputMethodService 生命周期

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodService.java
// 作用: 输入法应用继承的基类
// 生命周期: onCreate → onCreateInputMethodInterface → bindInput → ...
// ═══════════════════════════════════════════════════════════════════════

public class InputMethodService extends AbstractInputMethodService
        implements InputMethod.Callback {

    // ═══════════════════════════════════════════════════════════════════
    // 方法 1: onCreate() — 输入法服务创建
    // 由系统调用，在输入法进程的主线程执行
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();

        // ① 获取系统服务
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 用于添加/移除键盘窗口

        mInputMethodManager = IInputMethodManager.Stub.asInterface(
                ServiceManager.getService(Context.INPUT_METHOD_SERVICE));
        // 用于与 IMS 通信 (报告状态变化等)

        // ② 创建 IMS 的 Binder 回调
        // IMS 通过此 Binder 调用输入法的方法
        mIInputMethodWrapper = new IInputMethodWrapper(this);
        // 这是输入法侧的 Binder Stub
        // IMS 持有它的代理 (IInputMethod)
        // 可以调用:
        //   - attachToken()
        //   - createSession()
        //   - startInput()
        //   - hideSoftInput()
        //   - showSoftInput()

        // ③ 创建键盘窗口的 LayoutParams
        mInputViewLayoutParams = new WindowManager.LayoutParams();
        mInputViewLayoutParams.type =
                WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        // ★ 关键: type = TYPE_INPUT_METHOD
        // 告诉 WMS 这是输入法窗口
        // WMS 会:
        //   - 设置 layer = 400
        //   - 加入 mImeContainer
        //   - 记录为 mInputMethodWindow

        mInputViewLayoutParams.flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        // FLAG_NOT_FOCUSABLE: 不抢焦点 (焦点在 EditText)
        // FLAG_LAYOUT_IN_SCREEN: 全屏布局
        // FLAG_SHOW_WHEN_LOCKED: 锁屏时也能显示

        mInputViewLayoutParams.gravity = Gravity.BOTTOM;
        // 键盘在屏幕底部

        mInputViewLayoutParams.width =
                ViewGroup.LayoutParams.MATCH_PARENT;
        mInputViewLayoutParams.height =
                ViewGroup.LayoutParams.WRAP_CONTENT;
        // 宽度全屏，高度自适应

        mInputViewLayoutParams.format = PixelFormat.TRANSLUCENT;
        // 支持透明 (键盘边缘阴影)

        mInputViewLayoutParams.windowAnimations =
                com.android.internal.R.style.Animation_InputMethod;
        // 键盘弹出/收起的动画
        // 通常是底部滑入/滑出

        // ④ 子类可以重写此方法做额外初始化
        // 例如: 加载自定义键盘布局、初始化词库等
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 2: onCreateInputMethodInterface() — 创建 Binder 接口
    // 系统通过此接口与输入法通信
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public AbstractInputMethodInterface onCreateInputMethodInterface() {
        // 返回 IInputMethodSession 的包装
        return new InputMethodSessionImpl(this);
        // InputMethodSessionImpl 实现了 InputMethodSession 接口
        // 处理:
        //   - 触摸事件 (用户点击键盘)
        //   - 按键事件 (物理键盘)
        //   - 输入法命令 (切换大小写等)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 3: onBindInput() — IMS 绑定成功后的回调
    // IMS 调用 IInputMethod.attachToken() 后触发
    // ═══════════════════════════════════════════════════════════════════
    public void onBindInput(EditorInfo info) {
        // 保存编辑器信息
        mEditorInfo = info;

        // 子类可以重写此方法:
        //   - 根据 inputType 准备键盘布局
        //   - 加载词库
        //   - 初始化语音输入等
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 4: onStartInputView() — 开始显示输入视图
    // 当 EditText 获得焦点且需要显示键盘时调用
    // ═══════════════════════════════════════════════════════════════════
    public void onStartInputView(EditorInfo info, boolean restarting) {
        mEditorInfo = info;

        // ① 创建或更新键盘 UI
        View inputView = onCreateInputView();
        // 子类实现: 返回键盘布局 View
        // 例如: QWERTY 键盘、数字键盘、手写板等

        if (inputView != null) {
            setInputView(inputView);
            // setInputView() 内部:
            //   mInputView = inputView;
            //   // 将键盘 View 设置为输入法窗口的内容
        }

        // ② 显示键盘
        setInputViewShown(true);
        // 内部:
        //   if (mInputView.getWindowToken() == null) {
        //       // 首次显示 → 添加到窗口管理器
        //       mWindowManager.addView(mInputView, mInputViewLayoutParams);
        //       // → WMS.addWindow() 被调用
        //       // → 创建 WindowState (type=INPUT_METHOD, layer=400)
        //       // → 触发 performLayoutAndPlaceSurfacesLocked()
        //       // → 应用窗口被压缩
        //   }
        //   mInputView.setVisibility(View.VISIBLE);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 5: onDestroy() — 输入法服务销毁
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public void onDestroy() {
        // ① 移除键盘窗口
        if (mInputView != null) {
            mWindowManager.removeView(mInputView);
            // → WMS.removeWindowLocked() 被调用
            // → 从 mImeContainer 移除
            // → 应用窗口恢复原始高度
        }

        // ② 清理资源
        mIInputMethodWrapper = null;
        mInputMethodManager = null;

        super.onDestroy();
    }
}
```

---

## 五、启动初始化时序图

```
═══════════════════════════════════════════════════════════════════════════════════
  WMS + IMS 完整启动时序
═══════════════════════════════════════════════════════════════════════════════════

  init          Zygote        SystemServer         WMS              IMS            输入法进程
   │               │               │                 │                │                │
   │──fork+exec───▶│               │                 │                │                │
   │               │──fork────────▶│                 │                │                │
   │               │               │                 │                │                │
   │               │               │──main()────────▶│                │                │
   │               │               │                 │                │                │
   │               │               │ startOtherServices()             │                │
   │               │               │                 │                │                │
   │               │               │──new InputManagerService()──────▶│                │
   │               │               │                 │                │                │
   │               │               │──inputManager.start()───────────▶│                │
   │               │               │                 │ (启动 Reader   │                │
   │               │               │                 │  +Dispatcher   │                │
   │               │               │                 │  线程)         │                │
   │               │               │                 │                │                │
   │               │               │──WMS.main()────▶│                │                │
   │               │               │                 │                │                │
   │               │               │                 │──构造函数()────▶│                │
   │               │               │                 │  ① sInstance   │                │
   │               │               │                 │  ② mGlobalLock │                │
   │               │               │                 │  ③ mWindowMap  │                │
   │               │               │                 │  ④ mRoot       │                │
   │               │               │                 │  ⑤ mPolicy     │                │
   │               │               │                 │  ⑥ mSurface    │                │
   │               │               │                 │     Session    │                │
   │               │               │                 │  ⑦ mSystemReady│                │
   │               │               │                 │     = true     │                │
   │               │               │                 │                │                │
   │               │               │──addService───▶│                │                │
   │               │               │  ("window",wm)  │                │                │
   │               │               │                 │                │                │
   │               │               │──new IMS()──────┼───────────────▶│                │
   │               │               │                 │                │                │
   │               │               │                 │                │──构造函数()     │
   │               │               │                 │                │  ① mMethodMap  │
   │               │               │                 │                │  ② buildList() │
   │               │               │                 │                │  ③ mSystemReady│
   │               │               │                 │                │     = true     │
   │               │               │                 │                │                │
   │               │               │──addService─────┼───────────────▶│                │
   │               │               │  ("input_method")                │                │
   │               │               │                 │                │                │
   │               │               │  ... 系统继续启动 ...             │                │
   │               │               │                 │                │                │
   │               │               │  用户打开应用，EditText 获焦:                       │
   │               │               │                 │                │                │
   │               │               │──focusChanged──▶│                │                │
   │               │               │                 │──focusChanged──▶│                │
   │               │               │                 │                │                │
   │               │               │                 │                │──bindToMethod──▶│
   │               │               │                 │                │                │
   │               │               │                 │                │                │──onCreate()
   │               │               │                 │                │                │  创建键盘
   │               │               │                 │                │                │  LayoutParams
   │               │               │                 │                │                │
   │               │               │                 │                │──startInput───▶│
   │               │               │                 │                │                │
   │               │               │                 │                │                │──onStartInputView()
   │               │               │                 │                │                │  创建键盘 UI
   │               │               │                 │                │                │  addView()
   │               │               │                 │                │                │
   │               │               │                 │◀──addWindow─────┼────────────────┤
   │               │               │                 │  (type=IME)    │                │
   │               │               │                 │  layer=400     │                │
   │               │               │                 │                │                │
   │               │               │                 │──performLayout()                │
   │               │               │                 │  (压缩应用窗口) │                │
   │               │               │                 │                │                │
   │               │               │                 │                │                │
   │               │               │  ★ 输入法键盘显示完成!                              │
```

---

## 六、初始化关键节点总结

| 阶段 | 方法 | 关键动作 |
|------|------|---------|
| **InputManager 创建** | `new InputManagerService()` | 初始化 Native 层 InputReader + InputDispatcher |
| **InputManager 启动** | `inputManager.start()` | 启动 Reader/Dispatcher 两个后台线程 |
| **WMS 入口** | `WMS.main()` | 在 DisplayThread 上同步创建 WMS 实例 |
| **WMS 构造** | `new WindowManagerService()` | 初始化全局锁、窗口映射、容器树、策略、Surface 会话 |
| **WMS 就绪** | `mSystemReady = true` | 开始接受窗口操作请求 |
| **IMS 构造** | `new InputMethodManagerService()` | 扫描已安装输入法、确定默认输入法、注册监听器 |
| **IMS 就绪** | `mSystemReady = true` | 开始接受输入法管理请求 |
| **首次绑定** | `bindToCurrentMethod()` | 绑定默认输入法服务，创建 InputMethodSession |
| **输入法初始化** | `InputMethodService.onCreate()` | 创建键盘 LayoutParams (type=INPUT_METHOD) |
| **键盘显示** | `onStartInputView()` | 创建键盘 UI，addView 到 WindowManager |
