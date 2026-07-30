/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * AIAgentManagerService — 系统服务本体。跑在 system_server 进程内,是 agent 回环的"手":
 *   - 经 Binder 暴露 IAIAgentManager(在 system_server 注册名 "aiagent")
 *   - 实现 DeviceActions,用系统特权 API 执行感知/动作,替代 in-app agent 的 AccessibilityService
 *   - 托管 AgentLoop:submitGoal 起线程跑 感知->决策->行动
 *
 * 注册位置:SystemServer.startOtherServices() 里
 *   mSystemServiceManager.startService(AIAgentManagerService.class);
 *
 * 权限:所有方法需在 SELinux 与 AndroidManifest 做好收口(见 sepolicy/ 与 patches/)。
 */

package com.android.server.aiagent;

import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.server.SystemService;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.graphics.Point;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class AIAgentManagerService extends SystemService implements DeviceActions {
    private static final String TAG = "AIAgentManager";

    private final Context mContext;
    private final AgentState mState = new AgentState();
    private final ToolRegistry mRegistry = new ToolRegistry();
    private final MockLlmClient mMock = new MockLlmClient();
    private final AtomicReference<Thread> mLoopThread = new AtomicReference<>();

    private final IBinder mStub = new IAIAgentManagerStub();

    public AIAgentManagerService(Context context) {
        super(context);
        mContext = context;
        // 注册设备工具;onFinish 在 finish 工具被调用时停止回环
        for (Tool t : DeviceTools.build(this, () -> mState.running = false)) {
            mRegistry.register(t);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.AI_AGENT_SERVICE, mStub);
    }

    // ====================== DeviceActions:系统特权动作 ======================

    @Override
    public String getUI() {
        // 前台包名/Activity + 屏幕尺寸。完整可交互树需保留一个 AccessibilityService 当"眼睛"。
        StringBuilder sb = new StringBuilder();
        try {
            // 在 system_server 内直接取本地 ATM 实例,免 GET_TASKS 权限
            List<ActivityManager.RunningTaskInfo> tasks =
                    ActivityTaskManager.getInstance().getTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RunningTaskInfo t = tasks.get(0);
                if (t.topActivity != null) {
                    sb.append("pkg=").append(t.topActivity.getPackageName())
                      .append(" activity=").append(t.topActivity.getClassName());
                }
            }
        } catch (Exception e) {
            sb.append("ui_err=").append(e.getMessage());
        }
        Point size = new Point();
        try {
            WindowManager wm = mContext.getSystemService(WindowManager.class);
            wm.getDefaultDisplay().getRealSize(size);
            sb.append(" w=").append(size.x).append(" h=").append(size.y);
        } catch (Exception e) {
            sb.append(" size_err=").append(e.getMessage());
        }
        return sb.toString();
    }

    @Override
    public void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        inject(down);
        inject(up);
    }

    @Override
    public void swipe(float x1, float y1, float x2, float y2, long durationMs) {
        long now = SystemClock.uptimeMillis();
        MotionEvent e1 = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1, y1, 0);
        MotionEvent move = MotionEvent.obtain(now, now + durationMs / 2, MotionEvent.ACTION_MOVE, x2, y2, 0);
        MotionEvent e2 = MotionEvent.obtain(now, now + durationMs, MotionEvent.ACTION_UP, x2, y2, 0);
        inject(e1);
        inject(move);
        inject(e2);
    }

    @Override
    public void pressKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        inject(down);
        inject(up);
    }

    @Override
    public void launchApp(String packageName) {
        Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    @Override
    public void setSetting(String namespace, String key, int value) {
        switch (namespace) {
            case "global":
                Settings.Global.putInt(mContext.getContentResolver(), key, value);
                break;
            case "secure":
                Settings.Secure.putInt(mContext.getContentResolver(), key, value);
                break;
            case "system":
                Settings.System.putInt(mContext.getContentResolver(), key, value);
                break;
            default:
                throw new IllegalArgumentException("unknown namespace: " + namespace);
        }
    }

    private void inject(android.view.InputEvent event) {
        // INJECT_INPUT_EVENT_MODE_ASYNC = 0;system_server(uid 1000)可注入。
        InputManager.getInstance().injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        event.recycle();
    }

    // ====================== Binder Stub ======================

    private final class IAIAgentManagerStub extends IAIAgentManager.Stub {
        @Override
        public void submitGoal(android.os.aiagent.AIAgentRequest request) {
            enforceManageAgents();
            final boolean mock = request.isUseMock();
            Thread t = new Thread(() -> {
                LlmClient client = mock ? mMock : new MockLlmClient(); // TODO: 接 OpenAI/NNAPI
                mMock.reset();
                AgentLoop loop = new AgentLoop(mRegistry, client, mState,
                        SYSTEM_PROMPT);
                loop.run(request.getGoal());
            }, "AIAgentLoop");
            mLoopThread.set(t);
            t.start();
        }

        @Override
        public void stop() {
            enforceManageAgents();
            mState.running = false;
            Thread t = mLoopThread.getAndSet(null);
            if (t != null) t.interrupt();
        }

        @Override
        public String getUI() { return AIAgentManagerService.this.getUI(); }

        @Override
        public void tap(float x, float y) { AIAgentManagerService.this.tap(x, y); }

        @Override
        public void swipe(float x1, float y1, float x2, float y2, long durationMs) {
            AIAgentManagerService.this.swipe(x1, y1, x2, y2, durationMs);
        }

        @Override
        public void pressKey(int keyCode) { AIAgentManagerService.this.pressKey(keyCode); }

        @Override
        public void launchApp(String packageName) {
            enforceManageAgents();
            AIAgentManagerService.this.launchApp(packageName);
        }

        @Override
        public void setSetting(String namespace, String key, int value) {
            enforceManageAgents();
            AIAgentManagerService.this.setSetting(namespace, key, value);
        }

        @Override
        public String getState() { return mState.toJson(); }
    }

    private void enforceManageAgents() {
        mContext.enforceCallingOrSelfPermission(
                "android.permission.MANAGE_AI_AGENTS", "AIAgentManagerService");
    }

    private static final String SYSTEM_PROMPT =
            "你是运行在 Android 系统服务内的设备 agent。你有工具可以感知界面(get_ui)、"
            + "点击(tap_xy)、滑动(swipe)、按键(press_key)、启动 App(open_app)、改设置(set_setting)。"
            + "每次只调用一个工具,完成后用 finish 结束。优先观察界面再行动。";
}
