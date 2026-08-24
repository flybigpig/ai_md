# WMS 与 IMS 核心方法代码详解

---

## 一、WMS 核心方法

### 1. addWindow() — 添加窗口

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: WindowManagerService.java
// 方法: addWindow()
// 作用: 应用进程通过 Binder IPC 调用，在 WMS 中注册一个新窗口
// 调用链: 应用 → Session.add() → WMS.addWindow()
// ═══════════════════════════════════════════════════════════════════════

public int addWindow(Session session, IWindow client,
        WindowManager.LayoutParams attrs, int viewVisibility,
        int displayId, Rect outContentInsets, Rect outStableInsets,
        Rect outOutsets, DisplayCutout.ParcelableWrapper outDisplayCutout,
        InputChannel outInputChannel) {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 前置校验                                                 │
    // └─────────────────────────────────────────────────────────────────┘

    // 加锁: WMS 的所有状态操作都需要持有 mGlobalLock
    // 这是一个全局锁，保证窗口操作的线程安全
    synchronized (mGlobalLock) {

        // ① 权限检查: 某些窗口类型需要系统权限
        // 例如: TYPE_SYSTEM_ALERT, TYPE_SYSTEM_OVERLAY 需要 SYSTEM_ALERT_WINDOW 权限
        // 普通应用窗口 (TYPE_APPLICATION) 不需要额外权限
        final int type = attrs.type;  // 获取窗口类型

        // ② 检查是否已存在同名窗口 (防止重复添加)
        // mWindowMap 以 IBinder (client.asBinder()) 为 key
        // 每个窗口在客户端有唯一的 IWindow Binder 对象
        if (mWindowMap.containsKey(client.asBinder())) {
            // 窗口已存在 → 返回错误
            // 这种情况通常意味着客户端重复调用了 addWindow
            if (DEBUG_ADD_REMOVE) Slog.w(TAG,
                    "Window " + client + " is already added");
            return WindowManagerGlobal.ADD_DUPLICATE_ADD;
            // 返回码: ADD_DUPLICATE_ADD = -6
        }

        // ③ 对于子窗口 (TYPE_APPLICATION_MEDIA 等), 检查父窗口是否存在
        if (type >= TYPE_FIRST_SUB_WINDOW
                && type <= TYPE_LAST_SUB_WINDOW) {
            // 子窗口必须依附于一个顶级窗口
            // 通过 attrs.token 查找父窗口的 WindowToken
            final WindowState parentWindow = windowForClientLocked(null,
                    attrs.token, false);
            if (parentWindow == null) {
                // 父窗口不存在 → 返回错误
                return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
                // 返回码: ADD_BAD_SUBWINDOW_TOKEN = -2
            }
            // 父窗口的类型不能也是子窗口 (不允许嵌套子窗口)
            if (parentWindow.mAttrs.type >= TYPE_FIRST_SUB_WINDOW) {
                return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
            }
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 2: 创建窗口核心对象                                         │
        // └─────────────────────────────────────────────────────────────────┘

        // ④ 获取或创建 WindowToken
        // WindowToken 是窗口的"令牌"，标识窗口归属的组件 (Activity/输入法/壁纸等)
        // 同一个 Activity 的所有窗口共享同一个 WindowToken
        AppWindowToken atoken = null;
        final boolean addToToken = true;

        // 根据窗口类型确定 token 类型:
        // - TYPE_APPLICATION → AppWindowToken (Activity 窗口)
        // - TYPE_INPUT_METHOD → 输入法 token
        // - TYPE_WALLPAPER → 壁纸 token
        // - TYPE_STATUS_BAR → 状态栏 token
        token = displayContent.getDisplayContent().getTokenMap()
                .get(attrs.token);

        if (token == null) {
            // 对于普通应用窗口，token 应该已经由 ActivityManagerService 创建
            // 如果找不到，可能是非法请求
            if (type >= TYPE_FIRST_APPLICATION_WINDOW
                    && type <= TYPE_LAST_APPLICATION_WINDOW) {
                // 应用窗口但没有 token → 拒绝
                return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
            }
            // 对于系统窗口 (TYPE_INPUT_METHOD 等)，可以动态创建 token
            token = new WindowToken(builder);
        } else if (type >= TYPE_FIRST_APPLICATION_WINDOW
                && type <= TYPE_LAST_APPLICATION_WINDOW) {
            // 应用窗口找到了已有的 AppWindowToken
            atoken = token.asAppWindowToken();
        }

        // ⑤ 创建 WindowState — 这是窗口的核心表示
        // WindowState 封装了一个窗口的所有状态信息
        final WindowState win = new WindowState(this, session, client, token,
                parentWindow, appOp[0], attrs, viewVisibility,
                session.mUid, session.mCanAddInternalSystemWindow);
        // 参数说明:
        //   this          → WMS 实例
        //   session       → 客户端的 Session (与进程绑定)
        //   client        → IWindow 代理 (WMS 通过它与客户端通信)
        //   token         → WindowToken (标识归属)
        //   parentWindow  → 父窗口 (子窗口时有值)
        //   attrs         → LayoutParams (窗口属性)
        //   viewVisibility→ 初始可见性

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 3: 窗口属性初始化                                           │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑥ 确定窗口的 Z-Order 层级
        // 不同窗口类型有不同的基础层级:
        //   TYPE_APPLICATION     → 1~99
        //   TYPE_INPUT_METHOD    → 400 (输入法!)
        //   TYPE_WALLPAPER       → 300
        //   TYPE_STATUS_BAR      → 系统层级
        //   TYPE_SYSTEM_ALERT    → 1000
        win.mLayer = win.computeLayerLw();
        // computeLayerLw() 内部逻辑:
        //   switch (mAttrs.type) {
        //       case TYPE_INPUT_METHOD:
        //           return TYPE_LAYER_OFFSET + 200;  // 200 + 200 = 400
        //       case TYPE_WALLPAPER:
        //           return TYPE_LAYER_OFFSET + 100;  // 200 + 100 = 300
        //       case TYPE_APPLICATION:
        //           return mToken.windowTypeLayer();  // 1~99
        //       ...
        //   }

        // ⑦ 检查输入法相关特殊处理
        if (type == TYPE_INPUT_METHOD) {
            // ★ 输入法窗口的特殊处理!
            // 将其记录为当前输入法窗口
            displayContent.mInputMethodWindow = win;
            // 标记为输入法窗口
            win.mIsImWindow = true;
            // 输入法窗口添加到 IME 专用容器
            displayContent.mImeContainer.addWindow(win);
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 4: 加入窗口容器层级树                                       │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑧ 将窗口加入全局窗口映射
        mWindowMap.put(client.asBinder(), win);
        // key = IWindow 的 Binder 对象
        // value = WindowState 实例

        // ⑨ 将窗口加入容器层级树
        // 窗口层级树结构:
        //   RootWindowContainer
        //     └── DisplayContent (屏幕)
        //           ├── TaskStack (任务栈)
        //           │     └── Task (应用)
        //           │           └── AppWindowToken (Activity)
        //           │                 └── WindowState (窗口) ← 添加到此处
        //           └── ImeContainer (输入法容器)
        //                 └── WindowState (输入法窗口) ← 或添加到此处

        win.attach();
        // attach() 内部:
        //   mToken.addWindow(this);
        //   → 将窗口挂到对应的 AppWindowToken 下

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 5: 焦点调整                                                │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑩ 调整输入焦点
        // 新窗口可能需要获取焦点 (例如新打开的 Activity)
        boolean focusChanged = false;
        if (win.canReceiveFocus()) {
            // 检查新窗口是否可以接收焦点:
            // - 不是 FLAG_NOT_FOCUSABLE
            // - 可见
            // - 有 Surface
            focusChanged = displayContent.updateInputFocusWindowsLw(win);
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 6: 计算 insets (边距)                                       │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑪ 计算窗口的 content insets
        // content insets 表示窗口内容区域与窗口边界的距离
        // 主要受状态栏、导航栏、输入法窗口影响
        win.computeFrameLw();
        // 内部逻辑:
        //   // 获取屏幕可用区域
        //   Rect displayFrame = displayContent.getBounds();
        //
        //   // 减去状态栏高度
        //   if (mService.mStatusBar != null) {
        //       displayFrame.top += mService.mStatusBar.getFrameLw().bottom;
        //   }
        //
        //   // ★ 关键: 减去输入法窗口高度
        //   if (mService.mInputMethodWindow != null
        //           && mService.mInputMethodWindow.isVisibleLw()) {
        //       // 输入法可见时，应用窗口的可用区域要减去输入法高度!
        //       displayFrame.bottom -= mService.mInputMethodWindow.mFrame.height();
        //   }
        //
        //   // 减去导航栏高度
        //   if (mService.mNavigationBar != null) {
        //       displayFrame.bottom -= mService.mNavigationBar.getFrameLw().height();
        //   }
        //
        //   // 根据 softInputMode 决定如何处理
        //   switch (mAttrs.softInputMode) {
        //       case SOFT_INPUT_ADJUST_RESIZE:
        //           // 压缩窗口，给输入法腾空间
        //           mContainingFrame.set(displayFrame);
        //           break;
        //       case SOFT_INPUT_ADJUST_PAN:
        //           // 不压缩，只平移
        //           mContainingFrame.set(originalFrame);
        //           break;
        //       case SOFT_INPUT_ADJUST_NOTHING:
        //           // 不做任何调整
        //           break;
        //   }

        // 将 insets 返回给客户端
        outContentInsets.set(win.mContentInsets);
        outStableInsets.set(win.mStableInsets);

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 7: 创建 Surface 和 InputChannel                             │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑫ 创建窗口的 Surface
        win.createSurfaceLw();
        // 内部逻辑:
        //   // 向 SurfaceFlinger 请求创建 Surface
        //   // 实际是创建一个 BufferQueue:
        //   //   Producer (应用) → BufferQueue → Consumer (SurfaceFlinger)
        //   SurfaceControl sc = mSession.mSurfaceSession.createSurface(
        //       mName,              // 窗口名称
        //       mRequestedWidth,    // 请求宽度
        //       mRequestedHeight,   // 请求高度
        //       mAttrs.format,      // 像素格式
        //       flags);             // 标志位
        //
        //   // SurfaceControl 是 Surface 的控制句柄
        //   // 通过它可以设置位置、大小、层级、可见性等
        //   mSurfaceController = new WindowStateAnimator(sc);

        // ⑬ 创建 InputChannel (用于接收输入事件)
        if (outInputChannel != null && win.canReceiveKeys()) {
            // 创建一对 InputChannel:
            // - 一个给 WMS (InputDispatcher 用来发送事件)
            // - 一个给客户端 (用来接收事件)
            String name = win.getName();
            InputChannel[] channels = InputChannel.openInputChannelPair(name);
            // channels[0] → InputDispatcher 持有 (发送端)
            // channels[1] → 客户端持有 (接收端)

            win.mInputChannel = channels[0];
            // 将客户端的 channel 通过 IPC 传回
            channels[1].transferTo(outInputChannel);
            // 注册到 InputDispatcher
            mInputMonitor.registerInputChannelLw(win.mInputChannel,
                    win.mClient.asBinder());
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 8: 触发布局和动画                                           │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑭ 触发全局布局重算
        mRoot.performLayoutAndPlaceSurfaces();
        // 这是最关键的布局方法，内部会:
        // 1. 遍历所有 DisplayContent
        // 2. 对每个屏幕重新计算所有窗口的位置和大小
        // 3. 特别处理输入法窗口的出现/消失
        // 4. 通知 SurfaceFlinger 更新合成
        // 5. 启动必要的窗口动画

        // ⑮ 通知 IMS 焦点变化
        if (focusChanged) {
            mInputMethodMgr.focusInFrontWindowChanged(
                    win.mToken,
                    win.mAttrs.softInputMode);
            // IMS 收到后会:
            // - 检查新焦点窗口是否需要输入法
            // - 如果需要 → 显示输入法
            // - 如果不需要 → 隐藏输入法
        }

        // 返回成功
        return WindowManagerGlobal.ADD_OKAY;
        // 返回码: ADD_OKAY = 0
    }
}
```

### 2. performLayoutAndPlaceSurfacesLocked() — 布局计算

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: RootWindowContainer.java
// 方法: performLayoutAndPlaceSurfacesLocked()
// 作用: 重新计算所有窗口的位置和大小
// 触发时机: 添加/移除窗口、屏幕旋转、输入法显示/隐藏、配置变更
// ═══════════════════════════════════════════════════════════════════════

void performLayoutAndPlaceSurfacesLocked() {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 准备                                                     │
    // └─────────────────────────────────────────────────────────────────┘

    // 获取默认屏幕的 DisplayContent
    final DisplayContent dc = mService.getDefaultDisplayContentLocked();

    // 记录当前输入法窗口状态 (用于检测变化)
    final WindowState imWin = mService.mInputMethodWindow;
    final boolean imWasVisible = (imWin != null && imWin.isVisibleLw());
    // imWasVisible: 布局前输入法是否可见
    // 布局后如果可见性变化，需要额外处理

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 2: 计算输入法窗口的目标状态                                   │
    // └─────────────────────────────────────────────────────────────────┘

    // 确定输入法窗口的目标可见性和位置
    dc.computeImeTarget(true /* updateImeTarget*/);
    // 内部逻辑:
    //   // 找到当前应该显示输入法的目标窗口
    //   // 通常是当前焦点窗口 (mFocusedWindow)
    //   // 但如果焦点窗口设置了 FLAG_NOT_FOCUSABLE 等，可能需要调整
    //
    //   WindowState target = mFocusedWindow;
    //   if (target != null) {
    //       // 检查目标窗口是否需要输入法
    //       if (target.mAttrs.softInputMode
    //               == SOFT_INPUT_STATE_ALWAYS_HIDDEN) {
    //           // 目标窗口明确不需要输入法
    //           mInputMethodTarget = null;
    //       } else {
    //           mInputMethodTarget = target;
    //       }
    //   }

    // 计算输入法窗口的布局
    dc.layoutImeWindows();
    // 内部逻辑:
    //   if (mInputMethodWindow != null && mInputMethodWindow.isVisibleLw()) {
    //       // 输入法窗口可见
    //       // 计算输入法窗口的位置: 通常在屏幕底部，焦点窗口下方
    //
    //       WindowState imeWin = mInputMethodWindow;
    //
    //       // 输入法窗口的 frame 计算:
    //       // 水平方向: 全屏宽度
    //       // 垂直方向: 从屏幕底部向上，高度为输入法内容高度
    //       imeWin.mFrame.left = 0;
    //       imeWin.mFrame.right = displayWidth;
    //       imeWin.mFrame.bottom = displayHeight - navBarHeight;
    //       imeWin.mFrame.top = imeWin.mFrame.bottom - imeHeight;
    //       // imeHeight 由输入法应用通过 LayoutParams.height 指定
    //       // 通常为 WRAP_CONTENT，实际高度由键盘 UI 决定
    //   }

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 3: 遍历所有窗口进行布局                                       │
    // └─────────────────────────────────────────────────────────────────┘

    // 按 Z-Order 从底到顶遍历所有窗口
    dc.applySurfaceTransactionsTransaction();

    // 核心布局循环
    boolean didSomething = true;
    while (didSomething) {
        didSomething = false;

        // 遍历所有窗口 (按层级从低到高)
        final WindowList windows = dc.getWindowList();
        for (int i = windows.size() - 1; i >= 0; i--) {
            final WindowState win = windows.get(i);

            // ─── 计算每个窗口的 frame ───
            win.computeFrameLw();
            // 关键: 计算窗口的实际位置和大小
            //
            // 对于应用窗口:
            //   // 获取屏幕可用区域
            //   Rect frame = mContainingFrame;
            //   frame.set(displayBounds);
            //
            //   // 减去系统 UI
            //   frame.top += statusBarHeight;
            //   frame.bottom -= navBarHeight;
            //
            //   // ★ 如果输入法可见且 softInputMode=ADJUST_RESIZE
            //   if (mService.mInputMethodWindow != null
            //           && mService.mInputMethodWindow.isVisibleLw()
            //           && mAttrs.softInputMode == ADJUST_RESIZE) {
            //       // 减去输入法高度!
            //       frame.bottom -= mService.mInputMethodWindow.mFrame.height();
            //   }
            //
            // 对于输入法窗口:
            //   // 输入法窗口的位置在屏幕底部
            //   mFrame.bottom = displayBounds.bottom - navBarHeight;
            //   mFrame.top = mFrame.bottom - requestedHeight;

            // ─── 更新 Surface 位置 ───
            if (win.hasSurface()) {
                // 如果窗口位置/大小发生变化，需要更新 Surface
                final WindowStateAnimator winAnimator = win.mWinAnimator;
                winAnimator.updateSurfacePositionLw();
                // 内部:
                //   mSurfaceController.setPosition(
                //       win.mFrame.left, win.mFrame.top);
                //   mSurfaceController.setSize(
                //       win.mFrame.width(), win.mFrame.height());
            }
        }
    }

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 4: 处理输入法可见性变化                                       │
    // └─────────────────────────────────────────────────────────────────┘

    // 检查输入法可见性是否发生了变化
    final boolean imIsVisible = (imWin != null && imWin.isVisibleLw());

    if (imIsVisible != imWasVisible) {
        // ★ 输入法可见性发生了变化!
        // 需要通知所有受影响的窗口更新 insets

        if (imIsVisible) {
            // 输入法从隐藏变为可见
            // 所有 ADJUST_RESIZE 的窗口需要缩小
            notifyImeShown(true);
        } else {
            // 输入法从可见变为隐藏
            // 所有 ADJUST_RESIZE 的窗口需要恢复
            notifyImeShown(false);
        }
    }

    // 通知窗口 insets 变化
    private void notifyImeShown(boolean shown) {
        for (int i = mService.mWindowMap.size() - 1; i >= 0; i--) {
            WindowState win = mService.mWindowMap.valueAt(i);

            // 只处理需要调整大小的窗口
            if (win.mAttrs.softInputMode
                    == SOFT_INPUT_ADJUST_RESIZE) {

                // 计算新的 content insets
                Rect contentInsets = new Rect();
                win.computeContentInsetsLw(contentInsets);

                // 如果 insets 发生了变化
                if (!contentInsets.equals(win.mContentInsets)) {
                    // 记录旧的 insets
                    win.mContentInsets.set(contentInsets);

                    // ★ 通过 Binder 回调通知客户端
                    // 客户端会收到 onSizeChanged() 回调
                    try {
                        win.mClient.resized(
                            contentInsets,     // 新的 content insets
                            null,              // visible insets
                            null,              // stable insets
                            false,             // reportDraw
                            null,              // new config
                            false);            // force
                    } catch (RemoteException e) {
                        // 客户端进程可能已死亡
                        // WMS 会清理该客户端的所有窗口
                    }
                }
            }
        }
    }

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 5: 提交 Surface 事务                                        │
    // └─────────────────────────────────────────────────────────────────┘

    // 将所有 Surface 变更提交给 SurfaceFlinger
    dc.applySurfaceTransactionsTransaction();
    // 内部:
    //   SurfaceControl.Transaction t = new SurfaceControl.Transaction();
    //   for (WindowState win : windows) {
    //       if (win.hasSurface()) {
    //           t.setPosition(win.mSurfaceControl,
    //               win.mFrame.left, win.mFrame.top);
    //           t.setSize(win.mSurfaceControl,
    //               win.mFrame.width(), win.mFrame.height());
    //           t.setLayer(win.mSurfaceControl, win.mLayer);
    //           t.setVisibility(win.mSurfaceControl,
    //               win.mIsVisible);
    //       }
    //   }
    //   t.apply();  // 原子性地应用所有变更
    //   // SurfaceFlinger 会在下一个 VSync 重新合成画面
}
```

---

## 二、IMS 核心方法

### 3. showSoftInput() — 显示输入法

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodManagerService.java
// 方法: showSoftInput()
// 作用: 应用请求显示输入法键盘
// 调用链: 应用 → InputMethodManager.showSoftInput() → IMS.showSoftInput()
// ═══════════════════════════════════════════════════════════════════════

public boolean showSoftInput(IInputMethodClient client,
        int flags, ResultReceiver resultReceiver) {

    // ┌─────────────────────────────────────────────────────────────────┐
    // │ 阶段 1: 身份验证和状态检查                                        │
    // └─────────────────────────────────────────────────────────────────┘

    // 获取调用者的 UID 和 PID (Binder 自动提供)
    int uid = Binder.getCallingUid();
    int pid = Binder.getCallingPid();

    synchronized (mMethodMap) {  // IMS 的全局锁

        // ① 验证调用者身份
        // 确保调用者确实是当前活跃的输入法客户端
        // 防止其他进程冒充客户端请求显示输入法
        if (client == null || !isCalledWithValidToken(client)) {
            // 调用者的 token 与当前记录的客户端不匹配
            return false;
        }

        // ② 检查当前状态
        // mCurClient: 当前绑定的输入法客户端
        // mCurId: 当前选中的输入法 ID
        if (mCurClient == null) {
            // 没有活跃的客户端 → 无法显示输入法
            return false;
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 2: 绑定输入法服务                                           │
        // └─────────────────────────────────────────────────────────────────┘

        // ③ 确保已绑定到输入法服务
        // 如果还没有绑定，需要先绑定
        if (mCurMethod == null) {
            // 尚未绑定 → 执行绑定
            bindToCurrentMethod();
            // 内部逻辑:
            //   // 通过 PackageManager 查找输入法组件
            //   InputMethodInfo info = mMethodMap.get(mCurId);
            //   ResolveInfo ri = mPm.resolveService(
            //       new Intent().setComponent(info.getComponent()), 0);
            //
            //   // 绑定到输入法服务
            //   Intent intent = new Intent(InputMethodService.SERVICE_INTERFACE);
            //   intent.setComponent(info.getComponent());
            //   mContext.bindService(intent, mMethodConnection,
            //       Context.BIND_AUTO_CREATE);
            //   // mMethodConnection 是 ServiceConnection
            //   // 绑定成功后会回调 onServiceConnected()
            //
            //   // 在 onServiceConnected() 中:
            //   //   mCurMethod = IInputMethod.Stub.asInterface(service);
            //   //   mCurMethod.attachToken(mToken);
            //   //   mCurMethod.createSession(mSessionCallback);
            //   //   → 输入法进程创建 InputMethodSession
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 3: 启动输入会话                                             │
        // └─────────────────────────────────────────────────────────────────┘

        // ④ 通知输入法开始接收输入
        startInputUncheckedLocked(mCurClient, null);
        // 内部逻辑:
        //   // 获取当前客户端的 InputConnection
        //   // InputConnection 是输入法与应用之间的数据通道
        //   // 通过它输入法可以:
        //   //   - commitText() 提交文字
        //   //   - setComposingText() 设置候选文字
        //   //   - deleteSurroundingText() 删除文字
        //   //   - getTextBeforeCursor() 获取光标前文字
        //
        //   // 通知输入法: 新的输入会话开始
        //   mCurMethod.startInput(mCurSession, ei);
        //   // mCurSession: 当前输入法会话
        //   // ei: EditorInfo (编辑器信息)
        //   //   ei.inputType    → 输入类型 (文本/密码/数字等)
        //   //   ei.imeOptions   → IME 选项 (完成/搜索/下一步等)
        //   //   ei.hintText     → 提示文字
        //   //   ei.fieldId      → 字段 ID
        //   //   ei.packageName  → 应用包名
        //
        //   // 输入法收到 startInput() 后:
        //   //   → 根据 inputType 决定显示什么键盘布局
        //   //   → 根据 imeOptions 决定右下角按钮 (完成/搜索/下一步)
        //   //   → 创建键盘 UI 并通过 WindowManager.addView() 添加

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 4: 标记输入法为显示状态                                      │
        // └─────────────────────────────────────────────────────────────────┘

        // ⑤ 更新状态
        mInputShown = true;
        // 标记输入法正在显示
        // 后续焦点变化时会参考此状态

        // ⑥ 记录显示原因
        mShowRequested = true;
        // 标记是用户主动请求显示的
        // 区别于系统自动显示

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 5: 返回结果                                                 │
        // └─────────────────────────────────────────────────────────────────┘

        // 返回成功
        return true;
        // 注意: 返回 true 只表示请求已接受
        // 输入法实际显示是异步的，需要等输入法进程创建窗口
    }
}
```

### 4. hideSoftInput() — 隐藏输入法

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodManagerService.java
// 方法: hideSoftInput()
// 作用: 应用请求隐藏输入法键盘
// 调用链: 应用 → InputMethodManager.hideSoftInputFromWindow()
//         → IMS.hideSoftInput()
// ═══════════════════════════════════════════════════════════════════════

public boolean hideSoftInput(IInputMethodClient client,
        int flags, ResultReceiver resultReceiver) {

    synchronized (mMethodMap) {

        // ① 验证调用者身份 (同 showSoftInput)
        if (!isCalledWithValidToken(client)) {
            return false;
        }

        // ② 检查输入法是否正在显示
        if (!mInputShown) {
            // 输入法本来就没显示 → 无需隐藏
            if (resultReceiver != null) {
                resultReceiver.send(
                    InputMethodManager.RESULT_UNCHANGED_HIDDEN, null);
            }
            return true;
        }

        // ③ 通知输入法隐藏窗口
        if (mCurMethod != null) {
            try {
                // 通过 Binder IPC 调用输入法的 hideWindow()
                mCurMethod.hideSoftInput(0, null);
                // 输入法进程收到后:
                //   InputMethodService.hideWindow()
                //   → setInputViewShown(false)
                //   → WindowManager.removeView(mInputView)
                //   → 输入法窗口被移除
                //   → WMS 检测到 IME 窗口移除 → 重新布局
            } catch (RemoteException e) {
                // 输入法进程可能已死亡
            }
        }

        // ④ 更新状态
        mInputShown = false;
        // 标记输入法不再显示

        // ⑤ WMS 会自动处理后续布局
        // 当输入法窗口从 WMS 移除时:
        //   → WMS.removeWindowLocked() 被调用
        //   → mInputMethodWindow = null
        //   → performLayoutAndPlaceSurfacesLocked() 重新布局
        //   → 应用窗口恢复原始高度
        //   → IWindow.resized() 通知客户端

        return true;
    }
}
```

### 5. focusInFrontWindowChanged() — 焦点变化处理

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodManagerService.java
// 方法: focusInFrontWindowChanged() (简化版)
// 作用: WMS 通知 IMS 焦点窗口发生变化
// 调用链: WMS → IMS.focusInFrontWindowChanged()
// ═══════════════════════════════════════════════════════════════════════

// 注意: 实际代码中这个方法可能名为 windowGainedFocus 或类似名称
// 这里用简化名称便于理解

public void focusInFrontWindowChanged(IBinder focusedWindowToken,
        int softInputMode) {

    synchronized (mMethodMap) {

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 1: 记录新的焦点窗口                                         │
        // └─────────────────────────────────────────────────────────────────┘

        // ① 更新焦点窗口记录
        mCurFocusedWindow = focusedWindowToken;
        // 记录当前焦点窗口的 token
        // 后续输入法需要知道输入内容应该发送到哪个窗口

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 2: 判断是否需要显示/隐藏输入法                                │
        // └─────────────────────────────────────────────────────────────────┘

        // ② 根据 softInputMode 决定输入法行为
        // softInputMode 由 Activity 在 AndroidManifest.xml 中声明
        // 例如: android:windowSoftInputMode="adjustResize|stateHidden"

        switch (softInputMode & WindowManager.LayoutParams
                .SOFT_INPUT_MASK_STATE) {

            case SOFT_INPUT_STATE_UNSPECIFIED:
                // 未指定 → 由系统决定
                // 通常: 如果新焦点是 EditText，显示输入法
                //       如果新焦点不是 EditText，隐藏输入法
                handleUnspecifiedState(focusedWindowToken);
                break;

            case SOFT_INPUT_STATE_VISIBLE:
                // 明确要显示 → 显示输入法
                showSoftInput(mCurClient, 0, null);
                break;

            case SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                // 始终显示 → 无论什么情况都显示
                showSoftInput(mCurClient, 0, null);
                break;

            case SOFT_INPUT_STATE_HIDDEN:
                // 明确要隐藏 → 隐藏输入法
                hideSoftInput(mCurClient, 0, null);
                break;

            case SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                // 始终隐藏 → 无论什么情况都隐藏
                hideSoftInput(mCurClient, 0, null);
                break;
        }

        // ┌─────────────────────────────────────────────────────────────────┐
        // │ 阶段 3: 更新输入法的 EditorInfo                                   │
        // └─────────────────────────────────────────────────────────────────┘

        // ③ 如果输入法正在显示，需要更新编辑器信息
        if (mInputShown && mCurMethod != null) {
            // 获取新焦点窗口的 EditorInfo
            // EditorInfo 包含:
            //   - inputType: 输入类型 (文本/密码/数字/邮箱...)
            //   - imeOptions: IME 选项 (完成/搜索/下一步/发送...)
            //   - hintText: 提示文字
            //   - initialSelStart/End: 初始光标位置
            //   - initialCapsMode: 初始大写模式

            EditorInfo ei = getEditorInfo(focusedWindowToken);
            // 通过 Binder IPC 从客户端获取当前 EditText 的信息

            if (ei != null) {
                // 通知输入法更新
                mCurMethod.bindInput(ei);
                // 输入法收到后:
                //   → 根据 inputType 切换键盘布局
                //     (textPassword → 显示密码键盘)
                //     (number → 显示数字键盘)
                //     (textEmailAddress → 显示 @ 符号)
                //   → 根据 imeOptions 更新右下角按钮
                //     (actionDone → "完成")
                //     (actionSearch → "搜索")
                //     (actionNext → "下一步")
                //     (actionSend → "发送")
            }
        }
    }
}
```

---

## 三、输入法应用侧核心方法

### 6. InputMethodService — 输入法应用基类

```java
// ═══════════════════════════════════════════════════════════════════════
// 文件: InputMethodService.java
// 作用: 所有输入法应用都继承此基类
// 核心: 管理输入法窗口的创建/显示/隐藏
// ═══════════════════════════════════════════════════════════════════════

public class InputMethodService extends AbstractInputMethodService {

    // ─── 核心成员变量 ───
    private View mInputView;              // 键盘 UI 视图
    private InputConnection mInputConnection;  // 与应用的通信通道
    private EditorInfo mEditorInfo;       // 当前编辑器信息
    private WindowManager.LayoutParams mInputViewLayoutParams;
    private boolean mIsInputViewShown;    // 键盘是否正在显示

    // ═══════════════════════════════════════════════════════════════════
    // 方法 1: onCreate() — 输入法服务创建时调用
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public void onCreate() {
        super.onCreate();

        // 获取 WindowManager
        mWindowManager = getSystemService(WINDOW_SERVICE);

        // 创建键盘窗口的 LayoutParams
        mInputViewLayoutParams = new WindowManager.LayoutParams();
        // ★ 关键: 窗口类型设为 TYPE_INPUT_METHOD
        mInputViewLayoutParams.type =
            WindowManager.LayoutParams.TYPE_INPUT_METHOD;
        // 这个 type 告诉 WMS:
        //   - 这是一个输入法窗口
        //   - Z-Order 层级为 400
        //   - 位于应用窗口之上

        // 设置窗口标志
        mInputViewLayoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   // 不抢焦点
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;  // 全屏布局
        // FLAG_NOT_FOCUSABLE: 输入法窗口不获取输入焦点
        // 焦点仍然在 EditText 上，输入法只是"悬浮"在上面

        // 设置窗口位置和大小
        mInputViewLayoutParams.gravity = Gravity.BOTTOM;  // 底部对齐
        mInputViewLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        mInputViewLayoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        // WRAP_CONTENT: 高度由键盘 UI 内容决定
        // 通常 QWERTY 键盘高度约为屏幕高度的 1/3

        // 设置像素格式
        mInputViewLayoutParams.format = PixelFormat.TRANSLUCENT;
        // 支持透明背景 (键盘边缘的阴影效果)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 2: onCreateInputView() — 创建键盘 UI
    // 子类 (具体输入法) 必须重写此方法
    // ═══════════════════════════════════════════════════════════════════
    public View onCreateInputView() {
        // 子类实现: 创建键盘 UI
        // 例如:
        //   return LayoutInflater.from(this)
        //       .inflate(R.layout.keyboard, null);
        throw new UnsupportedOperationException();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 3: onStartInput() — 开始接收新的输入
    // 当 EditText 获得焦点时，IMS 通知输入法开始输入
    // ═══════════════════════════════════════════════════════════════════
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        mEditorInfo = attribute;
        // 保存编辑器信息

        // 根据输入类型做相应处理
        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_TEXT:
                // 文本输入 → 显示字母键盘
                break;
            case InputType.TYPE_CLASS_NUMBER:
                // 数字输入 → 显示数字键盘
                break;
            case InputType.TYPE_CLASS_PHONE:
                // 电话输入 → 显示拨号键盘
                break;
        }

        // 根据 imeOptions 设置右下角按钮
        switch (attribute.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_DONE:
                // 显示 "完成" 按钮
                break;
            case EditorInfo.IME_ACTION_SEARCH:
                // 显示 "搜索" 按钮
                break;
            case EditorInfo.IME_ACTION_NEXT:
                // 显示 "下一步" 按钮
                break;
            case EditorInfo.IME_ACTION_SEND:
                // 显示 "发送" 按钮
                break;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 4: setInputViewShown() — 显示/隐藏键盘 UI
    // 这是控制键盘 UI 可见性的核心方法
    // ═══════════════════════════════════════════════════════════════════
    void setInputViewShown(boolean shown) {
        if (mIsInputViewShown == shown) {
            return;  // 状态没变，跳过
        }

        mIsInputViewShown = shown;

        if (shown) {
            // ─── 显示键盘 ───
            if (mInputView == null) {
                // 首次显示 → 创建键盘 UI
                mInputView = onCreateInputView();
            }

            if (mInputView.getWindowToken() == null) {
                // 键盘 UI 还没有添加到窗口
                // ★ 关键: 通过 WindowManager 添加键盘视图
                mWindowManager.addView(
                    mInputView,
                    mInputViewLayoutParams);
                // 这会触发:
                //   WindowManagerService.addWindow()
                //   → 创建 WindowState (type=TYPE_INPUT_METHOD)
                //   → layer = 400
                //   → 加入 mImeContainer
                //   → 触发 performLayoutAndPlaceSurfacesLocked()
                //   → 应用窗口被压缩 (ADJUST_RESIZE 模式)
            }

            mInputView.setVisibility(View.VISIBLE);

        } else {
            // ─── 隐藏键盘 ───
            if (mInputView != null) {
                mInputView.setVisibility(View.GONE);

                // 从窗口管理器中移除
                mWindowManager.removeView(mInputView);
                // 这会触发:
                //   WindowManagerService.removeWindowLocked()
                //   → 从 mImeContainer 移除
                //   → mInputMethodWindow = null
                //   → 触发 performLayoutAndPlaceSurfacesLocked()
                //   → 应用窗口恢复原始高度
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 方法 5: 按键处理 — 用户点击键盘按键
    // ═══════════════════════════════════════════════════════════════════
    // 当用户点击键盘上的按键时:
    public void onKey(int primaryCode, int[] keyCodes) {
        // 获取 InputConnection (与 EditText 的通信通道)
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (primaryCode) {
            case Keyboard.KEYCODE_DELETE:
                // 退格键 → 删除光标前的字符
                ic.deleteSurroundingText(1, 0);
                // 参数: beforeLength=1, afterLength=0
                // 删除光标前 1 个字符
                break;

            case Keyboard.KEYCODE_DONE:
                // 完成键 → 执行 imeOptions 对应的动作
                // 例如: actionSearch → 触发搜索
                //        actionDone → 关闭键盘
                sendDefaultEditorAction(true);
                break;

            default:
                // 普通字符键 → 提交文字
                // ★ 核心: 通过 InputConnection 将文字发送给 EditText
                ic.commitText(String.valueOf((char) primaryCode), 1);
                // commitText() 内部:
                //   1. 通过 Binder IPC 调用应用进程的
                //      InputConnection.commitText()
                //   2. 应用进程的 EditText 收到文字
                //   3. EditText 更新显示
                // 注意: 这个数据流 不经过 WMS/IMS
                //       是输入法进程 ↔ 应用进程的直接 Binder 通信
                break;
        }
    }
}
```

---

## 四、协作流程中的关键 Binder 调用链

```
═══════════════════════════════════════════════════════════════════════════════════
  完整 Binder 调用链: 从 EditText 获焦到键盘显示
═══════════════════════════════════════════════════════════════════════════════════

  ① 应用进程                    ② WMS (system_server)           ③ IMS (system_server)
  ┌──────────────────┐          ┌──────────────────────┐        ┌──────────────────┐
  │ EditText         │          │                      │        │                  │
  │ .requestFocus()  │          │                      │        │                  │
  │      │           │          │                      │        │                  │
  │      ▼           │          │                      │        │                  │
  │ ViewRootImpl     │          │                      │        │                  │
  │ .requestFocus()  │          │                      │        │                  │
  │      │           │          │                      │        │                  │
  │      │ IPC       │          │                      │        │                  │
  │      │ (relayout)│          │                      │        │                  │
  ├──────┼───────────┼─────────▶│ addWindow()         │        │                  │
  │      │           │          │ (焦点窗口更新)        │        │                  │
  │      │           │          │      │               │        │                  │
  │      │           │          │      ▼               │        │                  │
  │      │           │          │ focusWindowChanged() │        │                  │
  │      │           │          │      │               │        │                  │
  │      │           │          │      │ IPC           │        │                  │
  │      │           │          ├──────┼───────────────┼───────▶│ focusChanged()   │
  │      │           │          │      │               │        │      │           │
  │      │           │          │      │               │        │      ▼           │
  │      │           │          │      │               │        │ showSoftInput()  │
  │      │           │          │      │               │        │      │           │
  │      │           │          │      │               │        │      │ IPC       │
  │      │           │          │      │               │        ├──────┼───────────┤
  │      │           │          │      │               │        │      │           │
  │      │           │          │      │               │        │      ▼           │
  │      │           │          │      │               │        │ 输入法进程       │
  │      │           │          │      │               │        │ startInput()     │
  │      │           │          │      │               │        │      │           │
  │      │           │          │      │               │        │      ▼           │
  │      │           │          │      │               │        │ setInputView    │
  │      │           │          │      │               │        │ Shown(true)      │
  │      │           │          │      │               │        │      │           │
  │      │           │          │      │               │        │      │ IPC       │
  │      │           │          │      │               │        │      │ (addView) │
  │      │           │          │      │               │        │      │           │
  │      │           │          ├──────┼───────────────┼───────┼──────┼───────────┤
  │      │           │          │      │               │        │      │           │
  │      │           │          │ addWindow()          │        │      │           │
  │      │           │          │ (type=INPUT_METHOD)  │        │      │           │
  │      │           │          │ layer=400            │        │      │           │
  │      │           │          │      │               │        │      │           │
  │      │           │          │      ▼               │        │      │           │
  │      │           │          │ performLayout()      │        │      │           │
  │      │           │          │ (压缩应用窗口)        │        │      │           │
  │      │           │          │      │               │        │      │           │
  │      │           │          │      │ IPC           │        │      │           │
  ├──────┼───────────┼─────────┼──────┼───────────────┼──────┼──────┼───────────┤
  │      │           │          │      │               │        │      │           │
  │      ▼           │          │      ▼               │        │      │           │
  │ onSizeChanged()  │          │                      │        │      │           │
  │ (窗口被压缩)      │          │                      │        │      │           │
  │                  │          │                      │        │      │           │
  └──────────────────┘          └──────────────────────┘        └──────────────────┘
```

---

## 五、总结：各方法职责速查

| 方法 | 所属类 | 核心职责 |
|------|--------|---------|
| `addWindow()` | WMS | 注册新窗口，确定层级，创建 Surface，调整焦点 |
| `performLayoutAndPlaceSurfacesLocked()` | RootWindowContainer | 重算所有窗口布局，处理 IME 可见性变化 |
| `computeFrameLw()` | WindowState | 计算单个窗口的 frame (受 IME 影响) |
| `showSoftInput()` | IMS | 验证身份，绑定输入法，启动输入会话 |
| `hideSoftInput()` | IMS | 通知输入法隐藏，更新状态 |
| `focusInFrontWindowChanged()` | IMS | 响应焦点变化，决定显示/隐藏输入法 |
| `onCreate()` | InputMethodService | 创建键盘窗口 LayoutParams (type=INPUT_METHOD) |
| `setInputViewShown()` | InputMethodService | 通过 WindowManager 添加/移除键盘视图 |
| `commitText()` | InputConnection | 输入法提交文字到 EditText (直接 Binder IPC) |
