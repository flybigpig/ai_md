/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * DeviceActions — 系统特权动作接口。AIAgentManagerService 实现它(真正的"手"),
 * DeviceTools 把每个动作包成 Tool。这样 Tool 抽象与执行上下文解耦,
 * 与 in-app agent 把动作包在 AccessibilityService 上是同一套结构,只是执行体换了。
 */

package com.android.server.aiagent;

public interface DeviceActions {
    String getUI();
    void tap(float x, float y);
    void swipe(float x1, float y1, float x2, float y2, long durationMs);
    void pressKey(int keyCode);
    void launchApp(String packageName);
    void setSetting(String namespace, String key, int value);
}
