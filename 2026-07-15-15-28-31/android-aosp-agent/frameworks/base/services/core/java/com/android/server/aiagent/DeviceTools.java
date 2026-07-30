/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * DeviceTools — 把系统特权动作包成 Tool 集合,注册进 ToolRegistry。
 * 对应 in-app agent 的 com.fly.agent.tools.DeviceTools,但执行体从
 * AccessibilityService 换成 DeviceActions(system_server 特权 API)。
 *
 * 工具清单:
 *   get_ui     取当前界面摘要(包名/Activity/屏幕尺寸)
 *   tap_xy     屏幕坐标点击
 *   swipe      滑动
 *   press_key  按键
 *   open_app   启动 App
 *   set_setting 写系统设置
 *   finish     结束回环
 */

package com.android.server.aiagent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DeviceTools {

    public static List<Tool> build(DeviceActions dev, Runnable onFinish) {
        List<Tool> list = new ArrayList<>();

        list.add(new Tool() {
            public String name() { return "get_ui"; }
            public String description() { return "获取当前可见界面:前台包名、Activity、屏幕尺寸。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
            }
            public String execute(JSONObject a) { return dev.getUI(); }
        });

        list.add(new Tool() {
            public String name() { return "tap_xy"; }
            public String description() { return "在屏幕坐标(x,y)(像素)点击一次。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{"
                     + "\"x\":{\"type\":\"number\"},\"y\":{\"type\":\"number\"}},"
                     + "\"required\":[\"x\",\"y\"]}";
            }
            public String execute(JSONObject a) {
                dev.tap((float) a.getDouble("x"), (float) a.getDouble("y"));
                return "tapped at (" + a.getDouble("x") + "," + a.getDouble("y") + ")";
            }
        });

        list.add(new Tool() {
            public String name() { return "swipe"; }
            public String description() { return "从(x1,y1)滑动到(x2,y2),durationMs 毫秒。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{"
                     + "\"x1\":{\"type\":\"number\"},\"y1\":{\"type\":\"number\"},"
                     + "\"x2\":{\"type\":\"number\"},\"y2\":{\"type\":\"number\"},"
                     + "\"durationMs\":{\"type\":\"number\"}},"
                     + "\"required\":[\"x1\",\"y1\",\"x2\",\"y2\",\"durationMs\"]}";
            }
            public String execute(JSONObject a) {
                dev.swipe((float) a.getDouble("x1"), (float) a.getDouble("y1"),
                          (float) a.getDouble("x2"), (float) a.getDouble("y2"),
                          (long) a.getDouble("durationMs"));
                return "swiped";
            }
        });

        list.add(new Tool() {
            public String name() { return "press_key"; }
            public String description() { return "注入按键,keyCode 为 Android KeyEvent 码(如 3=HOME,4=BACK)。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{"
                     + "\"keyCode\":{\"type\":\"integer\"}},"
                     + "\"required\":[\"keyCode\"]}";
            }
            public String execute(JSONObject a) {
                dev.pressKey(a.getInt("keyCode"));
                return "pressed key " + a.getInt("keyCode");
            }
        });

        list.add(new Tool() {
            public String name() { return "open_app"; }
            public String description() { return "启动指定包名的主 Activity。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{"
                     + "\"packageName\":{\"type\":\"string\"}},"
                     + "\"required\":[\"packageName\"]}";
            }
            public String execute(JSONObject a) {
                dev.launchApp(a.getString("packageName"));
                return "launched " + a.getString("packageName");
            }
        });

        list.add(new Tool() {
            public String name() { return "set_setting"; }
            public String description() { return "写系统设置。namespace: global|secure|system。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{"
                     + "\"namespace\":{\"type\":\"string\"},\"key\":{\"type\":\"string\"},"
                     + "\"value\":{\"type\":\"integer\"}},"
                     + "\"required\":[\"namespace\",\"key\",\"value\"]}";
            }
            public String execute(JSONObject a) {
                dev.setSetting(a.getString("namespace"), a.getString("key"), a.getInt("value"));
                return "set " + a.getString("namespace") + "." + a.getString("key");
            }
        });

        list.add(new Tool() {
            public String name() { return "finish"; }
            public String description() { return "任务完成,结束回环,可附 summary。"; }
            public String schema() {
                return "{\"type\":\"object\",\"properties\":{"
                     + "\"summary\":{\"type\":\"string\"}},\"required\":[]}";
            }
            public String execute(JSONObject a) {
                if (onFinish != null) onFinish.run();
                return "finished: " + a.optString("summary", "");
            }
        });

        return list;
    }
}
