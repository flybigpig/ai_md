/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * AIAgentManager — 公开包装类。App/客户端经 Context.getSystemService(Context.AI_AGENT_SERVICE)
 * 拿到本类,再调接口。仅暴露需要的能力,内部转给 IAIAgentManager Binder 代理。
 */

package android.os.aiagent;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.RemoteException;

public class AIAgentManager {
    private final Context mContext;
    private final IAIAgentManager mService;

    public AIAgentManager(Context context, IAIAgentManager service) {
        mContext = context;
        mService = service;
    }

    /** 提交一个 agent 目标。需 MANAGE_AI_AGENTS 权限(系统/特权 App)。 */
    @RequiresPermission("android.permission.MANAGE_AI_AGENTS")
    public void submitGoal(@NonNull AIAgentRequest request) {
        try {
            mService.submitGoal(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void stop() {
        try {
            mService.stop();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public String getUI() {
        try {
            return mService.getUI();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void tap(float x, float y) {
        try {
            mService.tap(x, y);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void swipe(float x1, float y1, float x2, float y2, long durationMs) {
        try {
            mService.swipe(x1, y1, x2, y2, durationMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void pressKey(int keyCode) {
        try {
            mService.pressKey(keyCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission("android.permission.MANAGE_AI_AGENTS")
    public void launchApp(@NonNull String packageName) {
        try {
            mService.launchApp(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission("android.permission.MANAGE_AI_AGENTS")
    public void setSetting(@NonNull String namespace, @NonNull String key, int value) {
        try {
            mService.setSetting(namespace, key, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public String getState() {
        try {
            return mService.getState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
