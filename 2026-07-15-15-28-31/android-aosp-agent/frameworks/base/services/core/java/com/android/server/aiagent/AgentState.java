/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * AgentState — 回环运行时状态快照。系统服务单例持有,供 getState() 序列化返回。
 * 对应 in-app agent 的 com.fly.agent.agent.AgentState(原样搬,Java 化)。
 */

package com.android.server.aiagent;

import java.util.ArrayList;
import java.util.List;

public final class AgentState {
    public volatile boolean running = false;
    public volatile String goal = "";
    public volatile int step = 0;
    public volatile String lastAction = "";
    public volatile String lastObservation = "";
    public final List<String> history = new ArrayList<>();

    public void reset(String goal) {
        this.goal = goal;
        this.step = 0;
        this.lastAction = "";
        this.lastObservation = "";
        this.history.clear();
        this.running = true;
    }

    public void record(String action, String observation) {
        this.step++;
        this.lastAction = action;
        this.lastObservation = observation;
        this.history.add("step=" + step + " action=" + action + " -> " + observation);
        if (this.history.size() > 200) this.history.remove(0);
    }

    /** 序列化为 JSON 串,便于 getState() 返回与日志。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"running\":").append(running)
          .append(",\"step\":").append(step)
          .append(",\"goal\":\"").append(esc(goal)).append("\"")
          .append(",\"lastAction\":\"").append(esc(lastAction)).append("\"")
          .append(",\"lastObservation\":\"").append(esc(lastObservation)).append("\"")
          .append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
