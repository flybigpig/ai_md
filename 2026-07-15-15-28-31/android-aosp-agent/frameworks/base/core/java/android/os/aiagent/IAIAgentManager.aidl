/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * IAIAgentManager — 系统内部 Binder 接口,承载 agent 感知-行动回环的"手"。
 * 注意:这是 framework 内部服务,不需要 @VintfStability(不跨 /system<->/vendor 版本契约)。
 */

package android.os.aiagent;

import android.os.aiagent.AIAgentRequest;

interface IAIAgentManager {
    /** 提交一个目标,service 内部起 AgentLoop 线程驱动回环(Mock/OpenAI)。 */
    void submitGoal(in AIAgentRequest request);

    /** 停止当前回环。 */
    void stop();

    /** 取当前可见界面摘要:前台包名/Activity + 屏幕尺寸(完整可交互树需 AccessibilityService)。 */
    String getUI();

    /** 在屏幕坐标 (x,y) 注入一次点击(系统输入注入,替代 AccessibilityService.dispatchGesture)。 */
    void tap(float x, float y);

    /** 注入滑动手势。 */
    void swipe(float x1, float y1, float x2, float y2, long durationMs);

    /** 注入一次按键(Android KeyEvent 码,如 KEYCODE_HOME=3)。 */
    void pressKey(int keyCode);

    /** 启动指定包名的主 Activity(经 system_server 特权 startActivityAsUser)。 */
    void launchApp(String packageName);

    /** 写系统设置(system_server 可写 secure/global)。namespace: global|secure|system。 */
    void setSetting(String namespace, String key, int value);

    /** 取回环状态快照(goal/step/lastAction/running)。 */
    String getState();
}
